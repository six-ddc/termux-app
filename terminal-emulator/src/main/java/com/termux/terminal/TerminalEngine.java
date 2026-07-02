package com.termux.terminal;

/**
 * Terminal VT engine used by {@link TerminalSession}.
 *
 * The app runtime requires the Ghostty-backed implementation provided by
 * {@link GhosttyTerminalEngine}. There is no Java VT fallback.
 */
public interface TerminalEngine {

    int RENDER_CELL_STRIDE = 9;
    int RENDER_CELL_CODEPOINT = 0;
    int RENDER_CELL_FOREGROUND = 1;
    int RENDER_CELL_BACKGROUND = 2;
    int RENDER_CELL_EFFECT = 3;
    int RENDER_CELL_WIDTH = 4;
    int RENDER_CELL_SELECTED = 5;
    int RENDER_CELL_UNDERLINE_COLOR = 6;
    int RENDER_CELL_UNDERLINE_STYLE = 7;
    int RENDER_CELL_OVERLINE = 8;
    int RENDER_CELL_COLOR_DEFAULT = Integer.MIN_VALUE;

    int RENDER_DIRTY_CLEAN = 0;
    int RENDER_DIRTY_PARTIAL = 1;
    int RENDER_DIRTY_FULL = 2;

    int MOUSE_LEFT_BUTTON = 0;
    int MOUSE_LEFT_BUTTON_MOVED = 32;
    int MOUSE_WHEELUP_BUTTON = 64;
    int MOUSE_WHEELDOWN_BUTTON = 65;

    int UNICODE_REPLACEMENT_CHAR = 0xFFFD;

    int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;

    int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    int TERMINAL_CURSOR_STYLE_BAR = 2;
    int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;

    int KEYMOD_ALT = 0x80000000;
    int KEYMOD_CTRL = 0x40000000;
    int KEYMOD_SHIFT = 0x20000000;
    int KEYMOD_NUM_LOCK = 0x10000000;

    void updateTerminalSessionClient(TerminalSessionClient client);

    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    void append(byte[] buffer, int length);

    /**
     * Rebuild the render snapshot if any {@link #append(byte[], int)} since the last snapshot left
     * one pending. Called once before a screen-update is delivered so a burst of appends (drained in
     * a loop by TerminalSession) rebuilds the snapshot a single time instead of per chunk.
     */
    void flushPendingSnapshot();

    void reset();

    boolean isGhosttyBacked();

    int[] getRenderCells();

    String[] getRenderCellText();

    int getRenderDirtyState();

    int[] getRenderDirtyRows();

    void clearRenderDirtyState();

    /**
     * Immutable, self-consistent render snapshot for the GL thread. Built on the
     * main thread; consumed without any native access. See
     * {@link TerminalRenderSnapshot}.
     */
    TerminalRenderSnapshot getRenderSnapshot();

    /** Current cursor blink phase (true = cursor "on"). Volatile-safe to read off-thread. */
    boolean isCursorBlinkOn();

    TerminalKittyGraphicsPlacement[] getKittyGraphicsPlacements();

    int getColumns();

    int getRows();

    int getCursorCol();

    int getCursorRow();

    boolean isCursorWideTail();

    boolean isCursorPasswordInput();

    int getCursorStyle();

    boolean isReverseVideo();

    boolean isCursorEnabled();

    boolean shouldCursorBeVisible();

    void setCursorBlinkingEnabled(boolean cursorBlinkingEnabled);

    void setCursorBlinkState(boolean cursorBlinkState);

    boolean isKeypadApplicationMode();

    boolean isCursorKeysApplicationMode();

    boolean isMouseTrackingActive();

    boolean isAlternateBufferActive();

    boolean sendKeyEvent(int keyCode, int keyMod);

    boolean sendCodePoint(int codePoint, boolean controlDown, boolean altDown);

    void sendMouseEvent(int mouseButton, int column, int row, boolean pressed);

    void sendFocusEvent(boolean focused);

    void paste(String text);

    int getScrollCounter();

    void clearScrollCounter();

    int getScrollbackRows();

    int getViewportTopRow();

    void scrollViewport(int rowDelta);

    void scrollViewportToBottom();

    boolean isAutoScrollDisabled();

    void toggleAutoScrollDisabled();

    void setSelection(int x1, int y1, int x2, int y2, boolean active);

    String getSelectedText(int x1, int y1, int x2, int y2);

    String getWordAtLocation(int x, int y);

    String getHyperlinkAtLocation(int x, int y);

    String[] getHyperlinks();

    int[] getWordBoundsAtLocation(int x, int y);

    String getTranscriptText(boolean linesJoined, boolean trim);

    String getTitle();

    /** Working directory reported by the shell via OSC 7, or null/empty when
     * the shell hasn't published one. */
    String getPwd();

    int[] getCurrentColors();

    void resetColors();
}
