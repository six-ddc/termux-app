package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link TerminalEngine} backed by libghostty-vt.
 *
 * The Android bridge loads libghostty-vt dynamically so this class can live in
 * the app before the Zig-built shared library is bundled. Ghostty render-state
 * cells are exposed directly to the Android GPU renderer, and text extraction
 * paths use Ghostty selection/formatter APIs.
 */
final class GhosttyTerminalEngine implements TerminalEngine, AutoCloseable {

    private static final int DATA_COLS = 1;
    private static final int DATA_ROWS = 2;
    private static final int DATA_CURSOR_X = 3;
    private static final int DATA_CURSOR_Y = 4;
    private static final int DATA_ACTIVE_SCREEN = 6;
    private static final int DATA_CURSOR_VISIBLE = 7;
    private static final int DATA_MOUSE_TRACKING = 11;
    private static final int DATA_TITLE = 12;
    private static final int DATA_SCROLLBACK_ROWS = 15;

    private static final int SCREEN_ALTERNATE = 1;

    private static final int SCROLL_VIEWPORT_BOTTOM = 1;
    private static final int SCROLL_VIEWPORT_DELTA = 2;

    private static final int MODE_DECCKM = 1;
    private static final int MODE_REVERSE_COLORS = 5;
    private static final int MODE_CURSOR_VISIBLE = 25;
    private static final int MODE_KEYPAD_KEYS = 66;
    private static final int MODE_MOUSE_PRESS_RELEASE = 1000;
    private static final int MODE_MOUSE_BUTTON_EVENT = 1002;
    private static final int MODE_MOUSE_ANY_EVENT = 1003;
    private static final int MODE_FOCUS_EVENT = 1004;
    private static final int MODE_MOUSE_PROTOCOL_SGR = 1006;

    private final TerminalOutput mSession;
    private TerminalSessionClient mClient;
    /** Recovers OSC 52 (clipboard) and OSC 9/777 (notification) from the raw PTY
     * byte stream, which libghostty-vt parses but does not surface via its API. */
    private final TermuxPlusOscInterceptor mOscInterceptor = new TermuxPlusOscInterceptor();
    private final int mTranscriptRows;
    private final TerminalColors mColors = new TerminalColors();
    private volatile long mNativeContext;
    private volatile int[] mRenderCells;
    private volatile String[] mRenderCellText;
    private volatile TerminalKittyGraphicsPlacement[] mKittyGraphicsPlacements = new TerminalKittyGraphicsPlacement[0];
    private volatile int mRenderDirtyState = RENDER_DIRTY_FULL;
    private volatile int[] mRenderDirtyRows;
    /** Single publication point consumed by the GL render thread (P0 thread safety). */
    private volatile TerminalRenderSnapshot mRenderSnapshot;
    private int mColumns;
    private int mRows;
    private int mCellWidthPixels;
    private int mCellHeightPixels;
    private int mCursorViewportCol;
    private int mCursorViewportRow;
    private int mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE;
    private boolean mCursorInViewport = true;
    private boolean mCursorWideTail;
    private boolean mCursorPasswordInput;
    private boolean mRenderStateCursorVisible = true;
    private boolean mRenderStateCursorBlinking = true;
    private boolean mCursorBlinkingEnabled;
    private volatile boolean mCursorBlinkState = true;
    private int mScrollCounter;
    private int mViewportTopRow;
    private String mLastTitle;
    private boolean mAutoScrollDisabled;
    private boolean mLoggedKeyEncoderPath;
    private boolean mLoggedTextInputEncoderPath;
    private boolean mLoggedMouseEncoderPath;
    private boolean mLoggedFocusEncoderPath;
    private boolean mLoggedPasteEncoderPath;
    private Boolean mLastSentFocusState;
    private Boolean mObservedFocus;
    private boolean mPrevFocusReportingEnabled;

    static boolean isAvailable() {
        return JNI.ghosttyIsAvailable();
    }

    GhosttyTerminalEngine(TerminalOutput session, int columns, int rows, int cellWidthPixels,
                          int cellHeightPixels, Integer transcriptRows, TerminalSessionClient client) {
        mSession = session;
        mClient = client;
        mColumns = columns;
        mRows = rows;
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        mTranscriptRows = getTerminalTranscriptRows(transcriptRows);
        mNativeContext = JNI.ghosttyCreate(columns, rows, mTranscriptRows, cellWidthPixels, cellHeightPixels);
        applyDefaultColors();
        syncScreenSnapshot();
        mLastTitle = getTitle();
    }

    private int getTerminalTranscriptRows(Integer transcriptRows) {
        if (transcriptRows == null || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN || transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX)
            return DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        else
            return transcriptRows;
    }

    @Override
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
    }

    @Override
    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mNativeContext != 0)
            JNI.ghosttyResize(mNativeContext, columns, rows, cellWidthPixels, cellHeightPixels);
        drainGhosttyEffects();
        mColumns = columns;
        mRows = rows;
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        if (mNativeContext != 0)
            syncScreenSnapshot();
    }

    @Override
    public void append(byte[] buffer, int length) {
        if (mNativeContext != 0) {
            int scrollbackRowsBefore = getScrollbackRows();
            // Observe the same bytes for OSC 52/9/777 before handing them to the
            // engine; the engine consumes them but exposes no payload for these.
            mOscInterceptor.feed(buffer, 0, length);
            JNI.ghosttyWrite(mNativeContext, buffer, 0, length);
            drainGhosttyEffects();
            drainOscEffects();
            syncScreenSnapshot();
            int scrollbackRowsAfter = getScrollbackRows();
            if (scrollbackRowsAfter > scrollbackRowsBefore)
                mScrollCounter += scrollbackRowsAfter - scrollbackRowsBefore;
        }
    }

    @Override
    public void reset() {
        if (mNativeContext != 0) {
            JNI.ghosttyReset(mNativeContext);
            mOscInterceptor.reset();
            applyDefaultColors();
            syncScreenSnapshot();
        }
    }

    private void syncScreenSnapshot() {
        int[] cells = JNI.ghosttySnapshotCells(mNativeContext, mColumns, mRows);
        int cellCount = mColumns * mRows;
        if (cells == null || cells.length < cellCount * RENDER_CELL_STRIDE) {
            mRenderCells = null;
            mRenderDirtyState = RENDER_DIRTY_FULL;
            mRenderDirtyRows = null;
            mRenderSnapshot = null;
            return;
        }
        parseDirtyMetadata(cells, cellCount);
        syncColorsSnapshot();
        syncCursorSnapshot();
        mRenderCells = cells;
        mRenderCellText = JNI.ghosttySnapshotCellText(mNativeContext, mColumns, mRows);
        syncKittyGraphicsPlacements();
        syncViewportTopRow();
        publishRenderSnapshotAndClearNativeDirty();
    }

    /**
     * Build the immutable {@link TerminalRenderSnapshot} from the just-captured
     * state (main thread) and publish it for the GL render thread. Everything the
     * renderer needs — including values that used to be live JNI reads
     * (columns/rows/reverse-video/cursor visibility) — is resolved here so the GL
     * thread never touches the native context. After capturing, the native dirty
     * tracking is reset on this (main) thread so the next snapshot only reports
     * newly-changed rows.
     */
    private void publishRenderSnapshotAndClearNativeDirty() {
        boolean reverseVideo = JNI.ghosttyGetMode(mNativeContext, MODE_REVERSE_COLORS);
        boolean dectcem = JNI.ghosttyGetBoolean(mNativeContext, DATA_CURSOR_VISIBLE);

        boolean visibleIgnoringBlink;
        boolean subjectToBlink;
        if (!mCursorInViewport) {
            visibleIgnoringBlink = false;
            subjectToBlink = false;
        } else if (mCursorPasswordInput) {
            visibleIgnoringBlink = true;
            subjectToBlink = false;
        } else if (!mRenderStateCursorVisible || !dectcem) {
            visibleIgnoringBlink = false;
            subjectToBlink = false;
        } else {
            visibleIgnoringBlink = true;
            subjectToBlink = mCursorBlinkingEnabled && mRenderStateCursorBlinking;
        }

        int cursorStyle = mCursorPasswordInput ? TERMINAL_CURSOR_STYLE_BLOCK : mCursorStyle;
        int[] colorsCopy = mColors.mCurrentColors.clone();

        mRenderSnapshot = new TerminalRenderSnapshot(mRenderCells, mRenderCellText, mRenderDirtyState,
            mRenderDirtyRows, mKittyGraphicsPlacements, colorsCopy, mColumns, mRows, reverseVideo,
            mCursorViewportCol, mCursorViewportRow, cursorStyle, visibleIgnoringBlink, subjectToBlink,
            mCursorWideTail);

        // Reset native dirty tracking now that the snapshot owns a copy of the
        // dirty rows; done on the main thread so the GL thread never mutates the
        // native render-state.
        JNI.ghosttyClearRenderDirtyState(mNativeContext);
    }

    private void syncKittyGraphicsPlacements() {
        Object[] placements = JNI.ghosttySnapshotKittyGraphicsPlacements(mNativeContext);
        if (placements == null || placements.length == 0) {
            mKittyGraphicsPlacements = new TerminalKittyGraphicsPlacement[0];
            return;
        }

        TerminalKittyGraphicsPlacement[] typedPlacements = new TerminalKittyGraphicsPlacement[placements.length];
        int count = 0;
        for (Object placement : placements) {
            if (placement instanceof TerminalKittyGraphicsPlacement)
                typedPlacements[count++] = (TerminalKittyGraphicsPlacement) placement;
        }
        if (count != typedPlacements.length) {
            TerminalKittyGraphicsPlacement[] compactPlacements = new TerminalKittyGraphicsPlacement[count];
            System.arraycopy(typedPlacements, 0, compactPlacements, 0, count);
            typedPlacements = compactPlacements;
        }
        mKittyGraphicsPlacements = typedPlacements;
    }

    private void drainGhosttyEffects() {
        if (mNativeContext == 0) return;

        byte[] pendingPtyWrite = JNI.ghosttyDrainPendingPtyWrite(mNativeContext);
        if (pendingPtyWrite != null && pendingPtyWrite.length > 0)
            mSession.write(pendingPtyWrite, 0, pendingPtyWrite.length);

        int bellCount = JNI.ghosttyConsumeBellCount(mNativeContext);
        for (int i = 0; i < bellCount; i++)
            mSession.onBell();

        if (JNI.ghosttyConsumeTitleChanged(mNativeContext))
            syncTitleChangedEffect();

        maybeReplayFocusOnModeTransition();
    }

    /** Dispatch OSC 52 clipboard writes and OSC 9/777 notifications recovered by
     * {@link #mOscInterceptor} to the session client. */
    private void drainOscEffects() {
        TermuxPlusOscInterceptor.ClipboardWrite clipboard;
        while ((clipboard = mOscInterceptor.pollClipboard()) != null)
            mSession.onCopyTextToClipboard(clipboard.text);
        TermuxPlusOscInterceptor.Notification notification;
        while ((notification = mOscInterceptor.pollNotification()) != null)
            mSession.onShowNotification(notification.title, notification.body);
    }

    private void syncTitleChangedEffect() {
        String oldTitle = mLastTitle;
        String newTitle = getTitle();
        mLastTitle = newTitle;
        if (!Objects.equals(oldTitle, newTitle))
            mSession.titleChanged(oldTitle, newTitle);
    }

    private void parseDirtyMetadata(int[] cells, int cellCount) {
        int metadataOffset = cellCount * RENDER_CELL_STRIDE;
        if (cells.length < metadataOffset + 1 + mRows) {
            mRenderDirtyState = RENDER_DIRTY_FULL;
            mRenderDirtyRows = null;
            return;
        }

        mRenderDirtyState = cells[metadataOffset];
        int[] dirtyRows = new int[mRows];
        System.arraycopy(cells, metadataOffset + 1, dirtyRows, 0, mRows);
        mRenderDirtyRows = dirtyRows;
    }

    private void syncColorsSnapshot() {
        int[] colors = JNI.ghosttySnapshotColors(mNativeContext);
        if (colors == null || colors.length < TextStyle.NUM_INDEXED_COLORS)
            return;
        // Detect OSC 4 / 10 / 11 / 12 runtime palette changes by diffing against
        // the previous snapshot; fire onColorsChanged so the Activity can repaint
        // its surrounding chrome (background, status bar) to match.
        //
        // The palette is always updated unconditionally before any diff: a
        // previous guard that bailed out when foreground==background or when any
        // entry was not fully opaque silently dropped legitimate updates. With
        // terminal transparency, non-0xff alpha on the default background is the
        // normal case, so such a guard must never gate the copy.
        boolean changed = false;
        for (int i = 0; i < TextStyle.NUM_INDEXED_COLORS; i++) {
            if (mColors.mCurrentColors[i] != colors[i]) {
                changed = true;
                break;
            }
        }
        System.arraycopy(colors, 0, mColors.mCurrentColors, 0, TextStyle.NUM_INDEXED_COLORS);
        if (changed)
            mSession.onColorsChanged();
    }

    private void syncCursorSnapshot() {
        int[] cursor = JNI.ghosttySnapshotCursor(mNativeContext);
        if (cursor == null || cursor.length < 5)
            return;
        mCursorInViewport = cursor[0] != 0;
        mCursorViewportCol = cursor[1];
        mCursorViewportRow = cursor[2];
        mCursorStyle = mapGhosttyCursorStyle(cursor[3]);
        mRenderStateCursorVisible = cursor[4] != 0;
        mCursorWideTail = cursor.length >= 6 && cursor[5] != 0;
        mRenderStateCursorBlinking = cursor.length < 7 || cursor[6] != 0;
        mCursorPasswordInput = cursor.length >= 8 && cursor[7] != 0;
    }

    private int mapGhosttyCursorStyle(int ghosttyStyle) {
        switch (ghosttyStyle) {
            case 0:
                return TERMINAL_CURSOR_STYLE_BAR;
            case 2:
                return TERMINAL_CURSOR_STYLE_UNDERLINE;
            case 1:
            case 3:
            default:
                return TERMINAL_CURSOR_STYLE_BLOCK;
        }
    }

    private void syncViewportTopRow() {
        int scrollbackRows = getScrollbackRows();
        int[] scrollbar = JNI.ghosttyGetScrollbar(mNativeContext);
        if (scrollbar == null || scrollbar.length < 3) {
            mViewportTopRow = 0;
            return;
        }
        int viewportOffset = scrollbar[1];
        mViewportTopRow = Math.max(-scrollbackRows, Math.min(0, viewportOffset - scrollbackRows));
    }

    @Override
    public boolean isGhosttyBacked() {
        return true;
    }

    @Override
    public int[] getRenderCells() {
        return mRenderCells;
    }

    @Override
    public String[] getRenderCellText() {
        return mRenderCellText;
    }

    @Override
    public int getRenderDirtyState() {
        return mRenderDirtyState;
    }

    @Override
    public int[] getRenderDirtyRows() {
        return mRenderDirtyRows;
    }

    @Override
    public void clearRenderDirtyState() {
        // Native dirty tracking is now reset on the main thread inside
        // publishRenderSnapshotAndClearNativeDirty(); the GL render thread no
        // longer calls this. Kept for the TerminalEngine contract and any
        // main-thread caller; must not be invoked off the main thread because it
        // mutates the native render-state.
        if (mNativeContext != 0)
            JNI.ghosttyClearRenderDirtyState(mNativeContext);
        mRenderDirtyState = RENDER_DIRTY_CLEAN;
        int[] dirtyRows = mRenderDirtyRows;
        if (dirtyRows != null) {
            for (int i = 0; i < dirtyRows.length; i++)
                dirtyRows[i] = 0;
        }
    }

    @Override
    public TerminalRenderSnapshot getRenderSnapshot() {
        return mRenderSnapshot;
    }

    @Override
    public boolean isCursorBlinkOn() {
        return mCursorBlinkState;
    }

    @Override
    public TerminalKittyGraphicsPlacement[] getKittyGraphicsPlacements() {
        return mKittyGraphicsPlacements;
    }

    @Override
    public int getColumns() {
        return mNativeContext == 0 ? mColumns : JNI.ghosttyGetInt(mNativeContext, DATA_COLS);
    }

    @Override
    public int getRows() {
        return mNativeContext == 0 ? mRows : JNI.ghosttyGetInt(mNativeContext, DATA_ROWS);
    }

    @Override
    public int getCursorCol() {
        return mNativeContext == 0 ? 0 : mCursorViewportCol;
    }

    @Override
    public int getCursorRow() {
        return mNativeContext == 0 ? 0 : mCursorViewportRow;
    }

    @Override
    public boolean isCursorWideTail() {
        return mNativeContext != 0 && mCursorWideTail;
    }

    @Override
    public boolean isCursorPasswordInput() {
        return mNativeContext != 0 && mCursorPasswordInput;
    }

    @Override
    public int getCursorStyle() {
        if (mCursorPasswordInput)
            return TERMINAL_CURSOR_STYLE_BLOCK;
        return mCursorStyle;
    }

    @Override
    public boolean isReverseVideo() {
        return mNativeContext != 0 && JNI.ghosttyGetMode(mNativeContext, MODE_REVERSE_COLORS);
    }

    @Override
    public boolean isCursorEnabled() {
        return mNativeContext == 0 || JNI.ghosttyGetMode(mNativeContext, MODE_CURSOR_VISIBLE);
    }

    @Override
    public boolean shouldCursorBeVisible() {
        if (mNativeContext != 0) {
            if (!mCursorInViewport)
                return false;
            if (mCursorPasswordInput)
                return true;
            if (!mRenderStateCursorVisible || !JNI.ghosttyGetBoolean(mNativeContext, DATA_CURSOR_VISIBLE))
                return false;
        }
        return !mCursorBlinkingEnabled || !mRenderStateCursorBlinking || mCursorBlinkState;
    }

    @Override
    public void setCursorBlinkingEnabled(boolean cursorBlinkingEnabled) {
        mCursorBlinkingEnabled = cursorBlinkingEnabled;
    }

    @Override
    public void setCursorBlinkState(boolean cursorBlinkState) {
        mCursorBlinkState = cursorBlinkState;
        if (mClient != null)
            mClient.onTerminalCursorStateChange(cursorBlinkState);
    }

    @Override
    public boolean isKeypadApplicationMode() {
        return mNativeContext != 0 && JNI.ghosttyGetMode(mNativeContext, MODE_KEYPAD_KEYS);
    }

    @Override
    public boolean isCursorKeysApplicationMode() {
        return mNativeContext != 0 && JNI.ghosttyGetMode(mNativeContext, MODE_DECCKM);
    }

    @Override
    public boolean isMouseTrackingActive() {
        return mNativeContext != 0 && (JNI.ghosttyGetBoolean(mNativeContext, DATA_MOUSE_TRACKING) ||
            JNI.ghosttyGetMode(mNativeContext, MODE_MOUSE_PRESS_RELEASE) ||
            JNI.ghosttyGetMode(mNativeContext, MODE_MOUSE_BUTTON_EVENT) ||
            JNI.ghosttyGetMode(mNativeContext, MODE_MOUSE_ANY_EVENT));
    }

    @Override
    public boolean isAlternateBufferActive() {
        return mNativeContext != 0 && JNI.ghosttyGetInt(mNativeContext, DATA_ACTIVE_SCREEN) == SCREEN_ALTERNATE;
    }

    @Override
    public boolean sendKeyEvent(int keyCode, int keyMod) {
        if (mNativeContext == 0) return false;
        byte[] encoded = JNI.ghosttyEncodeKey(mNativeContext, keyCode, keyMod);
        if (encoded == null || encoded.length == 0) return false;
        if (!mLoggedKeyEncoderPath) {
            Logger.logInfo(mClient, "GhosttyTerminalEngine", "Encoding terminal key events with libghostty-vt key encoder");
            mLoggedKeyEncoderPath = true;
        }
        mSession.write(encoded, 0, encoded.length);
        return true;
    }

    @Override
    public boolean sendCodePoint(int codePoint, boolean controlDown, boolean altDown) {
        if (mNativeContext == 0) return false;
        byte[] encoded = JNI.ghosttyEncodeCodePoint(mNativeContext, codePoint, controlDown, altDown);
        if (encoded == null || encoded.length == 0) return false;
        if (!mLoggedTextInputEncoderPath) {
            Logger.logInfo(mClient, "GhosttyTerminalEngine", "Encoding text input with libghostty-vt key encoder");
            mLoggedTextInputEncoderPath = true;
        }
        mSession.write(encoded, 0, encoded.length);
        return true;
    }

    @Override
    public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
        if (mNativeContext == 0) return;

        if (column < 1) column = 1;
        if (column > mColumns) column = mColumns;
        if (row < 1) row = 1;
        if (row > mRows) row = mRows;

        byte[] encoded = JNI.ghosttyEncodeMouse(mNativeContext, mouseButton, column, row, pressed,
            mColumns, mRows, mCellWidthPixels, mCellHeightPixels);
        if (encoded == null || encoded.length == 0) return;
        if (!mLoggedMouseEncoderPath) {
            Logger.logInfo(mClient, "GhosttyTerminalEngine", "Encoding terminal mouse events with libghostty-vt mouse encoder");
            mLoggedMouseEncoderPath = true;
        }
        mSession.write(encoded, 0, encoded.length);
    }

    @Override
    public void sendFocusEvent(boolean focused) {
        // Always remember the latest observed focus, even when reporting is
        // disabled, so we can replay it when the program turns reporting back
        // on (DECSET 1004 mid-session). Otherwise tmux / nvim that disable
        // then re-enable focus reporting would miss the current focus state.
        mObservedFocus = focused;
        if (mNativeContext == 0) return;
        if (!JNI.ghosttyGetMode(mNativeContext, MODE_FOCUS_EVENT))
            return;
        if (mLastSentFocusState != null && mLastSentFocusState.booleanValue() == focused)
            return;
        emitFocusBytes(focused);
    }

    private void emitFocusBytes(boolean focused) {
        byte[] encoded = JNI.ghosttyEncodeFocus(focused);
        if (encoded == null || encoded.length == 0) return;
        if (!mLoggedFocusEncoderPath) {
            Logger.logInfo(mClient, "GhosttyTerminalEngine", "Encoding terminal focus events with libghostty-vt focus encoder");
            mLoggedFocusEncoderPath = true;
        }
        mSession.write(encoded, 0, encoded.length);
        mLastSentFocusState = focused;
    }

    private void maybeReplayFocusOnModeTransition() {
        if (mNativeContext == 0) return;
        boolean enabled = JNI.ghosttyGetMode(mNativeContext, MODE_FOCUS_EVENT);
        if (enabled && !mPrevFocusReportingEnabled
                && mObservedFocus != null
                && !Objects.equals(mLastSentFocusState, mObservedFocus)) {
            emitFocusBytes(mObservedFocus.booleanValue());
        }
        mPrevFocusReportingEnabled = enabled;
    }

    @Override
    public void paste(String text) {
        if (text == null) return;
        if (mNativeContext == 0) return;
        byte[] encoded = JNI.ghosttyEncodePaste(mNativeContext, text.getBytes(StandardCharsets.UTF_8));
        if (encoded == null || encoded.length == 0) return;
        if (!mLoggedPasteEncoderPath) {
            Logger.logInfo(mClient, "GhosttyTerminalEngine", "Encoding paste data with libghostty-vt paste encoder");
            mLoggedPasteEncoderPath = true;
        }
        mSession.write(encoded, 0, encoded.length);
    }

    @Override
    public int getScrollCounter() {
        return mScrollCounter;
    }

    @Override
    public void clearScrollCounter() {
        mScrollCounter = 0;
    }

    @Override
    public int getScrollbackRows() {
        return mNativeContext == 0 ? 0 : JNI.ghosttyGetInt(mNativeContext, DATA_SCROLLBACK_ROWS);
    }

    @Override
    public int getViewportTopRow() {
        return mViewportTopRow;
    }

    @Override
    public void scrollViewport(int rowDelta) {
        if (mNativeContext == 0 || rowDelta == 0) return;
        JNI.ghosttyScrollViewport(mNativeContext, SCROLL_VIEWPORT_DELTA, rowDelta);
        syncScreenSnapshot();
    }

    @Override
    public void scrollViewportToBottom() {
        if (mNativeContext == 0) return;
        JNI.ghosttyScrollViewport(mNativeContext, SCROLL_VIEWPORT_BOTTOM, 0);
        syncScreenSnapshot();
    }

    @Override
    public boolean isAutoScrollDisabled() {
        return mAutoScrollDisabled;
    }

    @Override
    public void toggleAutoScrollDisabled() {
        mAutoScrollDisabled = !mAutoScrollDisabled;
    }

    @Override
    public void setSelection(int x1, int y1, int x2, int y2, boolean active) {
        if (mNativeContext == 0) return;
        JNI.ghosttySetSelection(mNativeContext, x1, y1, x2, y2, active);
        syncScreenSnapshot();
    }

    @Override
    public String getSelectedText(int x1, int y1, int x2, int y2) {
        if (mNativeContext == 0) return "";
        return utf8String(JNI.ghosttyFormatSelection(mNativeContext, x1, y1, x2, y2, true, true));
    }

    @Override
    public String getWordAtLocation(int x, int y) {
        if (mNativeContext == 0) return "";
        return utf8String(JNI.ghosttySelectWord(mNativeContext, x, y));
    }

    @Override
    public String getHyperlinkAtLocation(int x, int y) {
        if (mNativeContext == 0) return "";
        return utf8String(JNI.ghosttyGetHyperlinkAtLocation(mNativeContext, x, y));
    }

    @Override
    public String[] getHyperlinks() {
        if (mNativeContext == 0) return new String[0];
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        int rows = getRows();
        int columns = getColumns();
        int startRow = -getScrollbackRows();
        for (int y = startRow; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                String hyperlink = getHyperlinkAtLocation(x, y);
                if (hyperlink != null && !hyperlink.isEmpty())
                    links.add(hyperlink);
            }
        }
        return links.toArray(new String[0]);
    }

    @Override
    public int[] getWordBoundsAtLocation(int x, int y) {
        return mNativeContext == 0 ? null : JNI.ghosttySelectWordBounds(mNativeContext, x, y);
    }

    @Override
    public String getTranscriptText(boolean linesJoined, boolean trim) {
        if (mNativeContext == 0) return "";
        String text = utf8String(JNI.ghosttyFormatSelection(mNativeContext, 0, -getScrollbackRows(),
            mColumns - 1, mRows - 1, linesJoined, trim));
        return trim ? text.trim() : text;
    }

    private String utf8String(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String getTitle() {
        return mNativeContext == 0 ? null : JNI.ghosttyGetTitle(mNativeContext);
    }

    @Override
    public String getPwd() {
        return mNativeContext == 0 ? null : JNI.ghosttyGetPwd(mNativeContext);
    }

    @Override
    public int[] getCurrentColors() {
        return mColors.mCurrentColors;
    }

    @Override
    public void resetColors() {
        applyDefaultColors();
        if (mNativeContext != 0)
            syncScreenSnapshot();
    }

    private void applyDefaultColors() {
        mColors.reset();
        if (mNativeContext != 0 && !JNI.ghosttyApplyDefaultColors(mNativeContext, TerminalColors.COLOR_SCHEME.mDefaultColors))
            throw new IllegalStateException("Failed to apply Termux colors to libghostty-vt");
    }

    /**
     * Explicitly release the native libghostty-vt context. Idempotent and
     * thread-safe. Should be called from {@link TerminalSession} when the
     * session is finished rather than relying on the GC finalizer, so the (up to
     * 128 MiB) native Kitty image storage is not held until an arbitrary future
     * GC. The native handle is zeroed before being freed so any concurrent
     * guard (`mNativeContext != 0`) short-circuits instead of touching freed
     * memory.
     */
    @Override
    public synchronized void close() {
        long ctx = mNativeContext;
        if (ctx != 0) {
            mNativeContext = 0;
            mRenderSnapshot = null;
            JNI.ghosttyFree(ctx);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
