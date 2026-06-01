package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.termux.terminal.TerminalEngine;
import com.termux.terminal.TerminalKittyGraphicsPlacement;
import com.termux.terminal.TerminalRenderSnapshot;
import com.termux.terminal.TextStyle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class TerminalGpuRenderer implements GLSurfaceView.Renderer {

    private static final String LOG_TAG = "TerminalGpuRenderer";
    private static final int ATLAS_SIZE = 2048;
    private static final int MAX_BATCH_QUADS = 8192;
    private static final int UNDERLINE_NONE = 0;
    private static final int UNDERLINE_SINGLE = 1;
    private static final int UNDERLINE_DOUBLE = 2;
    private static final int UNDERLINE_CURLY = 3;
    private static final int UNDERLINE_DOTTED = 4;
    private static final int UNDERLINE_DASHED = 5;

    private final TerminalView mView;
    private final FloatBuffer mVertexBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer mGlyphBatchBuffer = ByteBuffer.allocateDirect(MAX_BATCH_QUADS * 6 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    // Access-ordered so eviction sweeps the least-recently-used glyphs first
    // once a real per-row LRU allocator replaces the current "atlas full =
    // clear all" policy in createGlyph(). The current eviction below still
    // does a full clear, but ordering is now in place for the upcoming work.
    private final Map<Long, Glyph> mCodepointGlyphs = new java.util.LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, Glyph> mGlyphs = new java.util.LinkedHashMap<>(256, 0.75f, true);
    // LRU-ordered Kitty texture cache (access-order). Eviction policy:
    //   max 64 entries OR 64 MiB of GPU memory, whichever hits first.
    // When the cap is exceeded we drop the least-recently-used entry and
    // release its GL texture object.
    private static final int KITTY_TEXTURE_MAX_ENTRIES = 64;
    private static final long KITTY_TEXTURE_MAX_BYTES = 64L * 1024L * 1024L;
    private final java.util.LinkedHashMap<Long, KittyTexture> mKittyTextures =
        new java.util.LinkedHashMap<>(16, 0.75f, true);
    private long mKittyTexturesByteCount;
    private final int[] mSelection = new int[]{-1, -1, -1, -1};
    private final int[] mLastSelection = new int[]{-1, -1, -1, -1};

    private int mProgram;
    private int mPositionLocation;
    private int mTexCoordLocation;
    private int mColorLocation;
    private int mTexturedLocation;
    private int mTextureLocation;
    private int mAtlasTexture;
    private int mFramebuffer;
    private int mFramebufferTexture;
    private int mFramebufferWidth;
    private int mFramebufferHeight;
    private boolean mFramebufferContentValid;
    private int mWidth;
    private int mHeight;
    private Bitmap mAtlasBitmap;
    private Canvas mAtlasCanvas;
    private Paint mGlyphPaint;
    private int mAtlasX;
    private int mAtlasY;
    private int mAtlasRowHeight;
    private int mGlyphCacheTextSize;
    private Typeface mGlyphCacheTypeface;
    private boolean mLoggedDirectRenderPath;
    private boolean mLoggedMissingDirectRenderState;
    private int mLoggedFrameStats;
    private long mFrameStartNanos;
    private int mFrameDrawCalls;
    private int mFrameGlyphs;
    private int mFrameRects;
    private int mFrameKittyImages;
    private int mFrameDirtyState;
    private int mFrameDirtyRows;
    private int mFrameTopRow;
    private int mGlyphBatchVertexCount;
    private int mGlyphBatchColor;
    private int mLastCursorRow = -1;
    private int mLastTopRow;
    /** Identity of the snapshot last fully rendered into the framebuffer; a repeat
     * frame of the same snapshot (e.g. cursor blink) needs no dirty-row redraw. */
    private TerminalRenderSnapshot mLastRenderedSnapshot;
    /** Engine whose content currently lives in the framebuffer. The FBO is shared
     * across sessions, so when the engine changes (tab switch) the FBO holds the
     * previous session's pixels and a full redraw is mandatory — incremental
     * dirty-row drawing would leave the old session showing through. */
    private TerminalEngine mLastRenderedEngine;
    /** Grid/cell geometry of the framebuffer's current content. When the font
     * size changes (pinch zoom) the column/row count and cell pixel size change
     * but the FBO keeps the old-geometry pixels; the resize may not be reported
     * as fully dirty, so without forcing a full redraw the incremental path
     * paints onto stale geometry and the terminal goes blank. */
    private int mLastColumns = -1;
    private int mLastRows = -1;
    private float mLastCellWidth = -1f;
    private float mLastCellHeight = -1f;
    private int mLastRenderRowOffset = -1;
    /** Default-background alpha for this frame, mirrored from
     * {@link TerminalView#getBackgroundAlpha()} at the top of every frame.
     * 1 = opaque (default). When < 1 the default terminal background is rendered
     * translucent and the FBO is composited over the wallpaper; explicit cell
     * background colours stay opaque. */
    private float mBackgroundAlpha = 1f;
    /** Background alpha the framebuffer content was last drawn with; a change
     * forces a full redraw so the FBO is re-cleared at the new alpha. */
    private float mLastBackgroundAlpha = 1f;
    /** Cursor-blink phase the frame was last drawn with. BLINK-attributed text
     * follows the same phase (there is no separate blink timer); a phase change
     * forces a full redraw when any blinking text is present so it animates. */
    private boolean mLastBlinkOn = true;

    TerminalGpuRenderer(TerminalView view) {
        mView = view;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Texture handles are tied to the previous EGL context which is now
        // gone; just drop our references — the driver reclaimed the GPU memory
        // with the context.
        mKittyTextures.clear();
        mKittyTexturesByteCount = 0;
        // The glyph atlas texture and every cached glyph's atlas coordinates are
        // gone with the old context too. Drop the glyph caches and reset the
        // atlas cursor; otherwise after the atlas is recreated below we'd sample
        // stale coordinates and render garbage/misplaced glyphs until a font
        // change happened to flush the cache.
        mGlyphs.clear();
        mCodepointGlyphs.clear();
        mAtlasX = 0;
        mAtlasY = 0;
        mAtlasRowHeight = 0;
        mGlyphCacheTypeface = null;
        mGlyphCacheTextSize = 0;
        mFramebufferContentValid = false;
        mLastRenderedSnapshot = null;
        mProgram = createProgram();
        mPositionLocation = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordLocation = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mColorLocation = GLES20.glGetUniformLocation(mProgram, "uColor");
        mTexturedLocation = GLES20.glGetUniformLocation(mProgram, "uTextured");
        mTextureLocation = GLES20.glGetUniformLocation(mProgram, "uTexture");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mAtlasTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mAtlasBitmap = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888);
        mAtlasCanvas = new Canvas(mAtlasBitmap);
        mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGlyphPaint.setColor(Color.WHITE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mAtlasBitmap, 0);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        Log.i(LOG_TAG, "OpenGL ES terminal renderer initialized; renderer=" + gl.glGetString(GL10.GL_RENDERER) +
            ", version=" + gl.glGetString(GL10.GL_VERSION));
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mWidth = width;
        mHeight = height;
        GLES20.glViewport(0, 0, width, height);
        recreateFramebuffer(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            drawFrameGuarded(gl);
        } catch (Throwable t) {
            // A throw here would kill the GLSurfaceView render thread, leaving the
            // terminal permanently blank until the surface is recreated. Catch it,
            // log it (with GL error + geometry), and keep the thread alive so the
            // next frame can recover.
            int glErr = GLES20.glGetError();
            Log.e(LOG_TAG, "onDrawFrame threw; glError=" + glErr
                + " atlasX=" + mAtlasX + " atlasY=" + mAtlasY + " rowH=" + mAtlasRowHeight
                + " glyphCacheSize=" + mGlyphs.size() + "/" + mCodepointGlyphs.size(), t);
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glViewport(0, 0, mWidth, mHeight);
                GLES20.glClearColor(0, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            } catch (Throwable ignored) {
            }
        }
    }

    private void drawFrameGuarded(GL10 gl) {
        TerminalEngine engine = mView.mTerminalEngine;
        TerminalRenderer renderer = mView.mRenderer;
        // Mirror the view's background alpha for this frame. Read once up front so
        // even the degenerate early-return clears below let the wallpaper through
        // instead of flashing opaque black when transparency is enabled.
        mBackgroundAlpha = mView.getBackgroundAlpha();
        if (engine == null || renderer == null || mWidth <= 0 || mHeight <= 0) {
            clearScreenBackground();
            return;
        }
        if (!engine.isGhosttyBacked()) {
            Log.e(LOG_TAG, "Refusing to render non-Ghostty terminal engine on GPU path");
            clearScreenBackground();
            return;
        }

        // P0 thread safety: the GL render thread reads ONLY this immutable
        // snapshot (built and published on the main thread). No native context
        // access, no live JNI, no render-state mutation happens here.
        TerminalRenderSnapshot snapshot = engine.getRenderSnapshot();
        if (snapshot == null || snapshot.cells == null) {
            if (!mLoggedMissingDirectRenderState) {
                Log.e(LOG_TAG, "Ghostty render-state snapshot is unavailable; refusing TerminalBuffer fallback");
                mLoggedMissingDirectRenderState = true;
            }
            clearScreenBackground();
            return;
        }
        beginFrameStats();

        renderer.copyGlyphPaintTo(mGlyphPaint);
        ensureGlyphCacheForRenderer(renderer);
        mView.copyRenderSelectors(mSelection);

        int[] palette = snapshot.colors;
        int background = palette[snapshot.reverseVideo ? TextStyle.COLOR_INDEX_FOREGROUND : TextStyle.COLOR_INDEX_BACKGROUND];

        int columns = snapshot.columns;
        int rows = snapshot.rows;
        int topRow = mView.mTopRow;
        int renderRowOffset = mView.getRenderRowOffset();
        mFrameTopRow = topRow;
        int cursorCol = snapshot.cursorCol;
        int cursorRow = snapshot.cursorRow;
        boolean blinkOn = engine.isCursorBlinkOn();
        boolean cursorVisible = snapshot.cursorVisibleIgnoringBlink
            && (!snapshot.cursorSubjectToBlink || blinkOn);
        boolean cursorWideTail = snapshot.cursorWideTail;
        // A repeat frame of an already-rendered snapshot (cursor blink, selection
        // tweak) is treated as CLEAN so only cursor/selection rows redraw — this
        // replaces the old GL-thread clearRenderDirtyState() call.
        // The framebuffer is shared across sessions; if the engine changed since
        // the last frame (tab switch) its pixels are stale and we must repaint in
        // full, never incrementally.
        boolean engineChanged = engine != mLastRenderedEngine;
        boolean snapshotConsumed = !engineChanged && snapshot == mLastRenderedSnapshot;
        mFrameDirtyState = snapshotConsumed ? TerminalEngine.RENDER_DIRTY_CLEAN : snapshot.dirtyState;
        int[] dirtyRows = snapshotConsumed ? null : snapshot.dirtyRows;
        mFrameDirtyRows = countDirtyRows(dirtyRows, rows);
        float cellWidth = renderer.mFontWidth;
        float cellHeight = renderer.mFontLineSpacing;
        int[] renderCells = snapshot.cells;
        String[] renderCellText = snapshot.cellText;
        TerminalKittyGraphicsPlacement[] kittyPlacements = sortedKittyPlacements(snapshot.kittyPlacements);
        int requiredRenderCells = columns * rows * TerminalEngine.RENDER_CELL_STRIDE;

        if (renderCells.length >= requiredRenderCells) {
            if (!mLoggedDirectRenderPath) {
                Log.i(LOG_TAG, "Rendering Ghostty render-state cells with OpenGL ES; size=" + columns + "x" + rows);
                mLoggedDirectRenderPath = true;
            }
            boolean geometryChanged = columns != mLastColumns || rows != mLastRows ||
                cellWidth != mLastCellWidth || cellHeight != mLastCellHeight ||
                renderRowOffset != mLastRenderRowOffset ||
                mBackgroundAlpha != mLastBackgroundAlpha;
            // When the blink phase flips, blinking text must repaint everywhere it
            // appears, not just on the cursor row the incremental path would touch.
            boolean blinkForcesRedraw = blinkOn != mLastBlinkOn
                && snapshotHasBlinkingText(renderCells, columns, rows);
            boolean fullRedraw = engineChanged || geometryChanged || blinkForcesRedraw ||
                !mFramebufferContentValid || topRow != mLastTopRow ||
                mFrameDirtyState == TerminalEngine.RENDER_DIRTY_FULL ||
                (kittyPlacements != null && kittyPlacements.length > 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
            GLES20.glViewport(0, 0, mWidth, mHeight);
            if (fullRedraw) {
                // Clear the FBO to the default background with the configured
                // alpha; glClear replaces, so the alpha channel is set exactly
                // (no blend) for the whole surface in one shot.
                setClearColor(background, mBackgroundAlpha);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            drawGhosttyRenderStateFrame(snapshot.cursorStyle, snapshot.reverseVideo, renderer, renderCells,
                renderCellText, palette, columns, rows, topRow, cursorCol, cursorRow, cursorVisible, cursorWideTail,
                cellWidth, cellHeight, renderRowOffset, background, fullRedraw, dirtyRows, kittyPlacements, blinkOn);
            mFramebufferContentValid = true;
            mLastRenderedSnapshot = snapshot;
            mLastRenderedEngine = engine;
            mLastColumns = columns;
            mLastRows = rows;
            mLastCellWidth = cellWidth;
            mLastCellHeight = cellHeight;
            mLastRenderRowOffset = renderRowOffset;
            mLastBackgroundAlpha = mBackgroundAlpha;
            mLastBlinkOn = blinkOn;
            mLastCursorRow = cursorRow;
            mLastTopRow = topRow;
            System.arraycopy(mSelection, 0, mLastSelection, 0, mSelection.length);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, mWidth, mHeight);
            // Clear the screen transparent when transparency is on so the
            // wallpaper shows where the blit writes a translucent pixel, then
            // composite the FBO over it (premultiplied).
            clearScreenBackground();
            drawFramebufferToScreen();
            finishFrameStats("ghostty-render-state", columns, rows);
            return;
        }

        if (!mLoggedMissingDirectRenderState) {
            Log.e(LOG_TAG, "Ghostty render-state cells are unavailable; refusing TerminalBuffer fallback");
            mLoggedMissingDirectRenderState = true;
        }
        clearScreenBackground();
        finishFrameStats("missing-ghostty-render-state", columns, rows);
    }

    private void drawGhosttyRenderStateFrame(int cursorShape, boolean reverseVideo, TerminalRenderer renderer,
                                             int[] renderCells, String[] renderCellText,
                                             int[] palette, int columns, int rows, int topRow, int cursorCol, int cursorRow,
                                             boolean cursorVisible, boolean cursorWideTail, float cellWidth, float cellHeight,
                                             int renderRowOffset, int defaultBackground, boolean fullRedraw, int[] dirtyRows,
                                             TerminalKittyGraphicsPlacement[] kittyPlacements, boolean blinkTextVisible) {
        int cursorRenderCol = cursorWideTail ? Math.max(0, cursorCol - 1) : cursorCol;

        for (int row = 0; row < rows; row++) {
            if (!rowNeedsFramebufferUpdate(row, topRow, fullRedraw, dirtyRows, cursorRow))
                continue;
            float rowY = (row - renderRowOffset) * cellHeight;
            if (!fullRedraw)
                fillDefaultBackground(0, rowY, columns * cellWidth, cellHeight, defaultBackground);
            for (int column = 0; column < columns; ) {
                int base = renderCellBase(row, column, columns);
                int widthColumns = normalizedCellWidth(renderCells, base, column, columns);
                if (widthColumns <= 0) {
                    column++;
                    continue;
                }
                int effect = renderCells[base + TerminalEngine.RENDER_CELL_EFFECT];
                boolean cursor = cursorVisible && row == cursorRow && cursorRenderCol == column;
                boolean blockCursor = cursor && cursorShape == TerminalEngine.TERMINAL_CURSOR_STYLE_BLOCK;
                boolean selected = renderCells[base + TerminalEngine.RENDER_CELL_SELECTED] != 0;
                int fg = resolveCellForeground(renderCells[base + TerminalEngine.RENDER_CELL_FOREGROUND], effect, palette);
                int bg = resolveCellBackground(renderCells[base + TerminalEngine.RENDER_CELL_BACKGROUND], palette);
                if (shouldSwapColors(reverseVideo, effect, selected, blockCursor)) {
                    int swap = fg;
                    fg = bg;
                    bg = swap;
                }
                float x = column * cellWidth;
                float cellSpanWidth = widthColumns * cellWidth;
                if (bg != defaultBackground || selected || blockCursor)
                    drawRect(x, rowY, cellSpanWidth, cellHeight, bg);
                if (cursor && cursorShape != TerminalEngine.TERMINAL_CURSOR_STYLE_BLOCK)
                    drawCursorShape(x, rowY,
                        cursorShape == TerminalEngine.TERMINAL_CURSOR_STYLE_UNDERLINE ? cellSpanWidth : cellWidth,
                        cellHeight, cursorShape, palette);
                column += widthColumns;
            }
        }

        drawKittyGraphicsPlacements(kittyPlacements, cellWidth, cellHeight, renderRowOffset, false);

        for (int row = 0; row < rows; row++) {
            if (!rowNeedsFramebufferUpdate(row, topRow, fullRedraw, dirtyRows, cursorRow))
                continue;
            float rowY = (row - renderRowOffset) * cellHeight;
            for (int column = 0; column < columns; ) {
                int base = renderCellBase(row, column, columns);
                int codePoint = renderCells[base + TerminalEngine.RENDER_CELL_CODEPOINT];
                int cellIndex = row * columns + column;
                String text = renderCellText != null && cellIndex < renderCellText.length ? renderCellText[cellIndex] : null;
                int effect = renderCells[base + TerminalEngine.RENDER_CELL_EFFECT];
                int widthColumns = normalizedCellWidth(renderCells, base, column, columns);
                if (widthColumns <= 0) {
                    column++;
                    continue;
                }
                boolean cursor = cursorVisible && row == cursorRow && cursorRenderCol == column;
                boolean selected = renderCells[base + TerminalEngine.RENDER_CELL_SELECTED] != 0;
                int fg = resolveCellForeground(renderCells[base + TerminalEngine.RENDER_CELL_FOREGROUND], effect, palette);
                int bg = resolveCellBackground(renderCells[base + TerminalEngine.RENDER_CELL_BACKGROUND], palette);
                if (shouldSwapColors(reverseVideo, effect, selected, cursor && cursorShape == TerminalEngine.TERMINAL_CURSOR_STYLE_BLOCK)) {
                    int swap = fg;
                    fg = bg;
                    bg = swap;
                }
                fg = applyDimEffect(fg, effect);

                // BLINK text is hidden during the blink-off phase (the cell
                // background, already painted above, stays). The cursor cell is
                // exempt so the cursor itself never blinks out with the text.
                boolean blinkHidden = !blinkTextVisible && !cursor
                    && (effect & TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0;

                if (!blinkHidden && (text != null || (codePoint > 0 && codePoint != ' ')) && (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
                    boolean bold = (effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0;
                    boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
                    Glyph glyph = text != null ? getGlyph(renderer, text, bold, italic, widthColumns, cellWidth) :
                        getGlyph(renderer, codePoint, bold, italic, widthColumns, cellWidth);
                    drawGlyph(glyph, column * cellWidth, rowY, fg);
                }
                if (!blinkHidden) {
                    int underlineColor = resolveUnderlineColor(renderCells[base + TerminalEngine.RENDER_CELL_UNDERLINE_COLOR], fg, palette);
                    int underlineStyle = renderCells[base + TerminalEngine.RENDER_CELL_UNDERLINE_STYLE];
                    boolean overline = renderCells[base + TerminalEngine.RENDER_CELL_OVERLINE] != 0;
                    drawTextDecorations(column * cellWidth, rowY, widthColumns * cellWidth, cellHeight,
                        fg, effect, underlineColor, underlineStyle, overline);
                }
                column += widthColumns;
            }
        }
        flushGlyphBatch();
        drawKittyGraphicsPlacements(kittyPlacements, cellWidth, cellHeight, renderRowOffset, true);
    }

    private TerminalKittyGraphicsPlacement[] sortedKittyPlacements(TerminalKittyGraphicsPlacement[] placements) {
        if (placements == null || placements.length == 0)
            return placements;
        TerminalKittyGraphicsPlacement[] sorted = placements.clone();
        Arrays.sort(sorted, (left, right) -> Integer.compare(left.zIndex, right.zIndex));
        return sorted;
    }

    private void drawKittyGraphicsPlacements(TerminalKittyGraphicsPlacement[] placements, float cellWidth,
                                             float cellHeight, int renderRowOffset, boolean aboveText) {
        if (placements == null)
            return;
        for (TerminalKittyGraphicsPlacement placement : placements) {
            if (placement == null || !placement.isTextureUploadSupported())
                continue;
            boolean placementAboveText = placement.zIndex >= 0;
            if (placementAboveText != aboveText)
                continue;
            KittyTexture texture = getKittyTexture(placement);
            if (texture == null)
                continue;

            float x = placement.viewportColumn * cellWidth + placement.xOffset;
            float y = (placement.viewportRow - renderRowOffset) * cellHeight + placement.yOffset;
            float width = Math.max(1, placement.pixelWidth);
            float height = Math.max(1, placement.pixelHeight);
            float u1 = (float) placement.sourceX / Math.max(1, placement.imageWidth);
            float v1 = (float) placement.sourceY / Math.max(1, placement.imageHeight);
            float u2 = (float) (placement.sourceX + placement.sourceWidth) / Math.max(1, placement.imageWidth);
            float v2 = (float) (placement.sourceY + placement.sourceHeight) / Math.max(1, placement.imageHeight);
            drawTextureQuad(texture.textureId, x, y, width, height, u1, v1, u2, v2);
            mFrameKittyImages++;
        }
    }

    private KittyTexture getKittyTexture(TerminalKittyGraphicsPlacement placement) {
        long key = (((long) placement.imageId) << 32) ^
            (((long) placement.imageDataHash) & 0xffffffffL) ^
            (((long) placement.imageData.length) << 1);
        KittyTexture cached = mKittyTextures.get(key);
        if (cached != null && cached.width == placement.imageWidth && cached.height == placement.imageHeight &&
            cached.format == placement.imageFormat) {
            return cached;
        }

        int glFormat;
        if (placement.imageFormat == TerminalKittyGraphicsPlacement.FORMAT_RGBA)
            glFormat = GLES20.GL_RGBA;
        else if (placement.imageFormat == TerminalKittyGraphicsPlacement.FORMAT_RGB)
            glFormat = GLES20.GL_RGB;
        else
            return null;

        ByteBuffer buffer = ByteBuffer.allocateDirect(placement.imageData.length).order(ByteOrder.nativeOrder());
        buffer.put(placement.imageData);
        buffer.position(0);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, glFormat, placement.imageWidth, placement.imageHeight, 0,
            glFormat, GLES20.GL_UNSIGNED_BYTE, buffer);

        int sizeBytes = placement.imageData.length;
        KittyTexture texture = new KittyTexture(textureId, placement.imageWidth, placement.imageHeight,
            placement.imageFormat, sizeBytes);
        mKittyTextures.put(key, texture);
        mKittyTexturesByteCount += sizeBytes;
        evictKittyTexturesIfNeeded();
        return texture;
    }

    private void evictKittyTexturesIfNeeded() {
        java.util.Iterator<java.util.Map.Entry<Long, KittyTexture>> it = mKittyTextures.entrySet().iterator();
        int[] handle = new int[1];
        while (it.hasNext()
                && (mKittyTextures.size() > KITTY_TEXTURE_MAX_ENTRIES
                    || mKittyTexturesByteCount > KITTY_TEXTURE_MAX_BYTES)) {
            java.util.Map.Entry<Long, KittyTexture> oldest = it.next();
            KittyTexture evicted = oldest.getValue();
            handle[0] = evicted.textureId;
            GLES20.glDeleteTextures(1, handle, 0);
            mKittyTexturesByteCount -= evicted.sizeBytes;
            it.remove();
        }
        if (mKittyTexturesByteCount < 0) mKittyTexturesByteCount = 0;
    }

    private boolean rowNeedsFramebufferUpdate(int row, int topRow, boolean fullRedraw, int[] dirtyRows, int cursorRow) {
        if (fullRedraw)
            return true;
        if (row == cursorRow || row == mLastCursorRow)
            return true;
        if (rowHasSelection(mSelection, topRow + row) || rowHasSelection(mLastSelection, topRow + row))
            return true;
        return dirtyRows != null && row < dirtyRows.length && dirtyRows[row] != 0;
    }

    private boolean rowHasSelection(int[] selection, int row) {
        int y1 = selection[0];
        int y2 = selection[1];
        return y1 >= 0 && row >= y1 && row <= y2;
    }

    private int renderCellBase(int row, int column, int columns) {
        return (row * columns + column) * TerminalEngine.RENDER_CELL_STRIDE;
    }

    private int normalizedCellWidth(int[] renderCells, int base, int column, int columns) {
        int widthColumns = renderCells[base + TerminalEngine.RENDER_CELL_WIDTH];
        if (widthColumns <= 0)
            return 0;
        return Math.max(1, Math.min(widthColumns, columns - column));
    }

    private boolean shouldSwapColors(boolean reverseVideo, int effect, boolean selected, boolean blockCursor) {
        boolean swap = reverseVideo || selected || blockCursor;
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
            swap = !swap;
        return swap;
    }

    private int resolveCellForeground(int color, int effect, int[] palette) {
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0 && color >= 0 && color < 8)
            color += 8;
        return resolveColor(color, palette);
    }

    private int resolveCellBackground(int color, int[] palette) {
        return resolveColor(color, palette);
    }

    private int resolveUnderlineColor(int color, int fallbackColor, int[] palette) {
        return color == TerminalEngine.RENDER_CELL_COLOR_DEFAULT ? fallbackColor : resolveColor(color, palette);
    }

    private int resolveColor(int color, int[] palette) {
        if ((color & 0xff000000) == 0xff000000)
            return color;
        if (color >= 0 && color < palette.length)
            return palette[color];
        return Color.WHITE;
    }

    private int applyDimEffect(int color, int effect) {
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) == 0)
            return color;
        int red = (0xFF & (color >> 16)) * 2 / 3;
        int green = (0xFF & (color >> 8)) * 2 / 3;
        int blue = (0xFF & color) * 2 / 3;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private void ensureGlyphCacheForRenderer(TerminalRenderer renderer) {
        if (mGlyphCacheTypeface == renderer.mTypeface && mGlyphCacheTextSize == renderer.mTextSize)
            return;
        mGlyphs.clear();
        mCodepointGlyphs.clear();
        mGlyphCacheTypeface = renderer.mTypeface;
        mGlyphCacheTextSize = renderer.mTextSize;
        mAtlasBitmap.eraseColor(Color.TRANSPARENT);
        mAtlasX = 0;
        mAtlasY = 0;
        mAtlasRowHeight = 0;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mAtlasBitmap, 0);
    }

    private Glyph getGlyph(TerminalRenderer renderer, int codePoint, boolean bold, boolean italic, int widthColumns, float cellWidth) {
        mGlyphPaint.setFakeBoldText(bold);
        mGlyphPaint.setTextSkewX(italic ? -0.35f : 0.f);
        long key = ((long) codePoint << 4) | ((long) Math.min(3, Math.max(1, widthColumns)) << 2) |
            (bold ? 1L : 0L) | (italic ? 2L : 0L);
        Glyph cached = mCodepointGlyphs.get(key);
        if (cached != null) return cached;
        Glyph glyph = createGlyph(renderer, new String(Character.toChars(codePoint)), maxGlyphPixelWidth(widthColumns, cellWidth));
        mCodepointGlyphs.put(key, glyph);
        return glyph;
    }

    private Glyph getGlyph(TerminalRenderer renderer, String text, boolean bold, boolean italic, int widthColumns, float cellWidth) {
        mGlyphPaint.setFakeBoldText(bold);
        mGlyphPaint.setTextSkewX(italic ? -0.35f : 0.f);
        String key = bold + ":" + italic + ":" + widthColumns + ":" + text;
        Glyph cached = mGlyphs.get(key);
        if (cached != null) return cached;
        Glyph glyph = createGlyph(renderer, text, maxGlyphPixelWidth(widthColumns, cellWidth));
        mGlyphs.put(key, glyph);
        return glyph;
    }

    private int maxGlyphPixelWidth(int widthColumns, float cellWidth) {
        return Math.max(1, Math.min(ATLAS_SIZE, (int) Math.ceil(Math.max(1, widthColumns) * cellWidth) + 4));
    }

    private Glyph createGlyph(TerminalRenderer renderer, String text, int maxGlyphWidth) {
        int measuredGlyphWidth = Math.max(1, (int) Math.ceil(mGlyphPaint.measureText(text)) + 4);
        int glyphWidth = Math.min(measuredGlyphWidth, maxGlyphWidth);
        int glyphHeight = Math.max(1, renderer.mFontLineSpacing);
        if (mAtlasX + glyphWidth >= ATLAS_SIZE) {
            mAtlasX = 0;
            mAtlasY += mAtlasRowHeight;
            mAtlasRowHeight = 0;
        }
        if (mAtlasY + glyphHeight >= ATLAS_SIZE) {
            mGlyphs.clear();
            mCodepointGlyphs.clear();
            mAtlasBitmap.eraseColor(Color.TRANSPARENT);
            mAtlasX = 0;
            mAtlasY = 0;
            mAtlasRowHeight = 0;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mAtlasBitmap, 0);
        }

        // Defensive: the glyph region must stay inside the atlas, otherwise
        // Bitmap.createBitmap() below throws IllegalArgumentException and (without
        // the onDrawFrame guard) kills the render thread, blanking the terminal
        // permanently. Clamp and log if a font size ever produces an oversized
        // glyph so the real dimensions show up on-device.
        if (glyphWidth > ATLAS_SIZE - mAtlasX || glyphHeight > ATLAS_SIZE - mAtlasY) {
            Log.e(LOG_TAG, "oversized glyph clamped: w=" + glyphWidth + " h=" + glyphHeight
                + " atlasX=" + mAtlasX + " atlasY=" + mAtlasY + " lineSpacing=" + renderer.mFontLineSpacing
                + " fontWidth=" + renderer.mFontWidth + " textSize=" + renderer.mTextSize);
            glyphWidth = Math.max(1, Math.min(glyphWidth, ATLAS_SIZE - mAtlasX));
            glyphHeight = Math.max(1, Math.min(glyphHeight, ATLAS_SIZE - mAtlasY));
        }

        float baseline = mAtlasY + renderer.mFontLineSpacing - renderer.mFontLineSpacingAndAscent;
        int saveCount = mAtlasCanvas.save();
        mAtlasCanvas.clipRect(mAtlasX, mAtlasY, mAtlasX + glyphWidth, mAtlasY + glyphHeight);
        mAtlasCanvas.drawText(text, mAtlasX + 2, baseline, mGlyphPaint);
        mAtlasCanvas.restoreToCount(saveCount);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);
        Bitmap glyphBitmap = Bitmap.createBitmap(mAtlasBitmap, mAtlasX, mAtlasY, glyphWidth, glyphHeight);
        boolean colored = bitmapHasColor(glyphBitmap);
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, mAtlasX, mAtlasY, glyphBitmap);
        glyphBitmap.recycle();

        Glyph glyph = new Glyph(mAtlasX, mAtlasY, glyphWidth, glyphHeight, colored);
        mAtlasX += glyphWidth;
        mAtlasRowHeight = Math.max(mAtlasRowHeight, glyphHeight);
        return glyph;
    }

    /**
     * Detect whether a freshly rasterized glyph carries its own color. Monochrome
     * text is drawn with a white paint, so every opaque pixel is gray
     * (R==G==B) and only alpha varies; color emoji / color-font glyphs ignore the
     * paint color and produce chromatic pixels. Any opaque chromatic pixel marks
     * the glyph as colored. Runs once per newly-cached glyph only.
     */
    private boolean bitmapHasColor(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0)
            return false;
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pixel : pixels) {
            if ((pixel >>> 24) == 0)
                continue;
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8) & 0xff;
            int b = pixel & 0xff;
            if (r != g || g != b)
                return true;
        }
        return false;
    }

    private void drawGlyph(Glyph glyph, float x, float y, int color) {
        mFrameGlyphs++;
        float u1 = (float) glyph.x / ATLAS_SIZE;
        float v1 = (float) glyph.y / ATLAS_SIZE;
        float u2 = (float) (glyph.x + glyph.width) / ATLAS_SIZE;
        float v2 = (float) (glyph.y + glyph.height) / ATLAS_SIZE;
        if (glyph.colored) {
            drawColorGlyph(x, y, glyph.width, glyph.height, u1, v1, u2, v2);
            return;
        }
        drawTexturedQuadBatched(x, y, glyph.width, glyph.height, u1, v1, u2, v2, color);
    }

    /**
     * Draw a color glyph (emoji / color font) using the full-RGBA texture branch
     * so its real colors survive instead of being flattened to the cell
     * foreground. The atlas is uploaded by GLUtils as premultiplied alpha, so
     * this quad blends with premultiplied factors (GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
     * to avoid darkened edges; the default straight-alpha blend is restored after.
     */
    private void drawColorGlyph(float x, float y, float width, float height,
                                float u1, float v1, float u2, float v2) {
        flushGlyphBatch();
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawTextureQuad(mAtlasTexture, x, y, width, height, u1, v1, u2, v2);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawRect(float x, float y, float width, float height, int color) {
        flushGlyphBatch();
        mFrameRects++;
        drawQuad(x, y, width, height, 0, 0, 0, 0, color, false);
    }

    private void drawTextureQuad(int texture, float x, float y, float width, float height,
                                 float u1, float v1, float u2, float v2) {
        flushGlyphBatch();
        float left = (x / mWidth) * 2f - 1f;
        float right = ((x + width) / mWidth) * 2f - 1f;
        float top = 1f - (y / mHeight) * 2f;
        float bottom = 1f - ((y + height) / mHeight) * 2f;
        mVertexBuffer.clear();
        putVertex(mVertexBuffer, left, top, u1, v1);
        putVertex(mVertexBuffer, left, bottom, u1, v2);
        putVertex(mVertexBuffer, right, top, u2, v1);
        putVertex(mVertexBuffer, right, bottom, u2, v2);
        mVertexBuffer.position(0);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform4f(mColorLocation, 1f, 1f, 1f, 1f);
        GLES20.glUniform1f(mTexturedLocation, 2f);
        GLES20.glUniform1i(mTextureLocation, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionLocation, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionLocation);
        mVertexBuffer.position(2);
        GLES20.glVertexAttribPointer(mTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordLocation);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        mFrameDrawCalls++;
    }

    private void beginFrameStats() {
        mFrameStartNanos = System.nanoTime();
        mFrameDrawCalls = 0;
        mFrameGlyphs = 0;
        mFrameRects = 0;
        mFrameKittyImages = 0;
        mGlyphBatchVertexCount = 0;
    }

    private void finishFrameStats(String source, int columns, int rows) {
        if (mLoggedFrameStats >= 16)
            return;
        long elapsedMicros = (System.nanoTime() - mFrameStartNanos) / 1000L;
        Log.i(LOG_TAG, "Frame " + source + " " + columns + "x" + rows +
            ": " + (elapsedMicros / 1000f) + "ms, drawCalls=" + mFrameDrawCalls +
            ", glyphs=" + mFrameGlyphs + ", rects=" + mFrameRects +
            ", kittyImages=" + mFrameKittyImages +
            ", dirtyState=" + mFrameDirtyState + ", dirtyRows=" + mFrameDirtyRows +
            ", topRow=" + mFrameTopRow);
        mLoggedFrameStats++;
    }

    private int countDirtyRows(int[] dirtyRows, int rows) {
        if (mFrameDirtyState == TerminalEngine.RENDER_DIRTY_FULL)
            return rows;
        if (mFrameDirtyState == TerminalEngine.RENDER_DIRTY_CLEAN || dirtyRows == null)
            return 0;
        int count = 0;
        for (int i = 0; i < rows && i < dirtyRows.length; i++) {
            if (dirtyRows[i] != 0)
                count++;
        }
        return count;
    }

    private void drawCursorShape(float x, float y, float width, float height, int cursorShape, int[] palette) {
        int cursorColor = palette[TextStyle.COLOR_INDEX_CURSOR];
        if (cursorShape == TerminalEngine.TERMINAL_CURSOR_STYLE_UNDERLINE)
            drawRect(x, y + height - Math.max(1f, height / 4f), width, Math.max(1f, height / 4f), cursorColor);
        else if (cursorShape == TerminalEngine.TERMINAL_CURSOR_STYLE_BAR)
            drawRect(x, y, Math.max(1f, width / 4f), height, cursorColor);
    }

    private void drawTextDecorations(float x, float y, float width, float height, int color, int effect,
                                     int underlineColor, int underlineStyle, boolean overline) {
        float stroke = Math.max(1f, height / 14f);
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0)
            drawUnderline(x, y + height - stroke * 2f, width, stroke, underlineColor, underlineStyle);
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0)
            drawRect(x, y + height * 0.55f, width, stroke, color);
        if (overline)
            drawRect(x, y + stroke, width, stroke, underlineColor);
    }

    private void drawUnderline(float x, float y, float width, float stroke, int color, int style) {
        switch (style) {
            case UNDERLINE_DOUBLE:
                drawRect(x, y - stroke * 1.5f, width, stroke, color);
                drawRect(x, y + stroke * 1.5f, width, stroke, color);
                break;
            case UNDERLINE_DOTTED:
                drawSegmentedLine(x, y, width, stroke, color, stroke * 1.25f, stroke * 2.5f);
                break;
            case UNDERLINE_DASHED:
                drawSegmentedLine(x, y, width, stroke, color, stroke * 5f, stroke * 3f);
                break;
            case UNDERLINE_CURLY:
                drawCurlyUnderline(x, y, width, stroke, color);
                break;
            case UNDERLINE_NONE:
            case UNDERLINE_SINGLE:
            default:
                drawRect(x, y, width, stroke, color);
                break;
        }
    }

    private void drawSegmentedLine(float x, float y, float width, float stroke, int color, float segmentWidth, float gapWidth) {
        float cursor = x;
        float end = x + width;
        while (cursor < end) {
            float currentWidth = Math.min(segmentWidth, end - cursor);
            drawRect(cursor, y, currentWidth, stroke, color);
            cursor += segmentWidth + gapWidth;
        }
    }

    private void drawCurlyUnderline(float x, float y, float width, float stroke, int color) {
        float segmentWidth = Math.max(stroke * 2f, 2f);
        float cursor = x;
        float end = x + width;
        boolean high = false;
        while (cursor < end) {
            float currentWidth = Math.min(segmentWidth, end - cursor);
            drawRect(cursor, y + (high ? -stroke : stroke), currentWidth, stroke, color);
            cursor += segmentWidth;
            high = !high;
        }
    }

    private void drawQuad(float x, float y, float width, float height, float u1, float v1, float u2, float v2, int color, boolean textured) {
        float left = (x / mWidth) * 2f - 1f;
        float right = ((x + width) / mWidth) * 2f - 1f;
        float top = 1f - (y / mHeight) * 2f;
        float bottom = 1f - ((y + height) / mHeight) * 2f;
        mVertexBuffer.clear();
        putVertex(mVertexBuffer, left, top, u1, v1);
        putVertex(mVertexBuffer, left, bottom, u1, v2);
        putVertex(mVertexBuffer, right, top, u2, v1);
        putVertex(mVertexBuffer, right, bottom, u2, v2);
        mVertexBuffer.position(0);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform4f(mColorLocation, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f);
        GLES20.glUniform1f(mTexturedLocation, textured ? 1f : 0f);
        GLES20.glUniform1i(mTextureLocation, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionLocation, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionLocation);
        mVertexBuffer.position(2);
        GLES20.glVertexAttribPointer(mTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordLocation);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        mFrameDrawCalls++;
    }

    private void drawTexturedQuadBatched(float x, float y, float width, float height, float u1, float v1, float u2, float v2, int color) {
        if (mGlyphBatchVertexCount > 0 && mGlyphBatchColor != color)
            flushGlyphBatch();
        if (mGlyphBatchVertexCount + 6 > MAX_BATCH_QUADS * 6)
            flushGlyphBatch();
        if (mGlyphBatchVertexCount == 0)
            mGlyphBatchColor = color;

        float left = (x / mWidth) * 2f - 1f;
        float right = ((x + width) / mWidth) * 2f - 1f;
        float top = 1f - (y / mHeight) * 2f;
        float bottom = 1f - ((y + height) / mHeight) * 2f;
        mGlyphBatchBuffer.position(mGlyphBatchVertexCount * 4);
        putVertex(mGlyphBatchBuffer, left, top, u1, v1);
        putVertex(mGlyphBatchBuffer, left, bottom, u1, v2);
        putVertex(mGlyphBatchBuffer, right, top, u2, v1);
        putVertex(mGlyphBatchBuffer, right, top, u2, v1);
        putVertex(mGlyphBatchBuffer, left, bottom, u1, v2);
        putVertex(mGlyphBatchBuffer, right, bottom, u2, v2);
        mGlyphBatchVertexCount += 6;
    }

    private void putVertex(FloatBuffer buffer, float x, float y, float u, float v) {
        buffer.put(x);
        buffer.put(y);
        buffer.put(u);
        buffer.put(v);
    }

    private void flushGlyphBatch() {
        if (mGlyphBatchVertexCount == 0)
            return;

        mGlyphBatchBuffer.position(0);
        GLES20.glUseProgram(mProgram);
        GLES20.glUniform4f(mColorLocation, Color.red(mGlyphBatchColor) / 255f, Color.green(mGlyphBatchColor) / 255f,
            Color.blue(mGlyphBatchColor) / 255f, Color.alpha(mGlyphBatchColor) / 255f);
        GLES20.glUniform1f(mTexturedLocation, 1f);
        GLES20.glUniform1i(mTextureLocation, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);

        mGlyphBatchBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionLocation, 2, GLES20.GL_FLOAT, false, 16, mGlyphBatchBuffer);
        GLES20.glEnableVertexAttribArray(mPositionLocation);
        mGlyphBatchBuffer.position(2);
        GLES20.glVertexAttribPointer(mTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, mGlyphBatchBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordLocation);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mGlyphBatchVertexCount);
        mFrameDrawCalls++;
        mGlyphBatchVertexCount = 0;
    }

    private void recreateFramebuffer(int width, int height) {
        if (mFramebufferTexture != 0) {
            int[] textures = new int[]{mFramebufferTexture};
            GLES20.glDeleteTextures(1, textures, 0);
            mFramebufferTexture = 0;
        }
        if (mFramebuffer != 0) {
            int[] framebuffers = new int[]{mFramebuffer};
            GLES20.glDeleteFramebuffers(1, framebuffers, 0);
            mFramebuffer = 0;
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mFramebufferTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFramebufferTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        int[] framebuffers = new int[1];
        GLES20.glGenFramebuffers(1, framebuffers, 0);
        mFramebuffer = framebuffers[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, mFramebufferTexture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Terminal framebuffer is incomplete: " + status);

        mFramebufferWidth = width;
        mFramebufferHeight = height;
        mFramebufferContentValid = false;
    }

    private void drawFramebufferToScreen() {
        drawTextureToScreen(mFramebufferTexture, 0, 1, 1, 0, mBackgroundAlpha < 1f);
    }

    private void drawTextureToScreen(int texture, float u1, float v1, float u2, float v2, boolean premultiply) {
        mVertexBuffer.clear();
        putVertex(mVertexBuffer, -1f, 1f, u1, v1);
        putVertex(mVertexBuffer, -1f, -1f, u1, v2);
        putVertex(mVertexBuffer, 1f, 1f, u2, v1);
        putVertex(mVertexBuffer, 1f, -1f, u2, v2);
        mVertexBuffer.position(0);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform4f(mColorLocation, 1f, 1f, 1f, 1f);
        // uTextured 3 = premultiply the (straight-alpha) FBO before writing it to
        // the screen, paired with a GL_ONE/GL_ZERO replace so SurfaceFlinger gets
        // the premultiplied pixels it expects for a translucent surface and
        // composites them correctly over the wallpaper (any default bg colour,
        // not just black). 2 = plain copy for the fully opaque path.
        if (premultiply) {
            GLES20.glUniform1f(mTexturedLocation, 3f);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO);
        } else {
            GLES20.glUniform1f(mTexturedLocation, 2f);
        }
        GLES20.glUniform1i(mTextureLocation, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionLocation, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionLocation);
        mVertexBuffer.position(2);
        GLES20.glVertexAttribPointer(mTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordLocation);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        mFrameDrawCalls++;
        if (premultiply)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Whether any visible cell carries the BLINK attribute. Scanned only on a
     * blink-phase flip to decide whether the whole frame must repaint. */
    private boolean snapshotHasBlinkingText(int[] cells, int columns, int rows) {
        int count = columns * rows;
        for (int i = 0; i < count; i++) {
            int effect = cells[i * TerminalEngine.RENDER_CELL_STRIDE + TerminalEngine.RENDER_CELL_EFFECT];
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0)
                return true;
        }
        return false;
    }

    /** Clear the default framebuffer (screen) for this frame: transparent when
     * background transparency is enabled (so the window/wallpaper shows through),
     * opaque black otherwise. */
    private void clearScreenBackground() {
        GLES20.glClearColor(0, 0, 0, mBackgroundAlpha < 1f ? 0f : 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    /** Fill an area of the FBO with the default terminal background, honoring
     * background transparency. When translucent we must REPLACE the destination
     * (including its alpha) rather than blend, otherwise the previous frame's
     * content bleeds through and the alpha never settles; so blending is disabled
     * for the fill and restored afterwards. */
    private void fillDefaultBackground(float x, float y, float width, float height, int color) {
        if (mBackgroundAlpha >= 1f) {
            drawRect(x, y, width, height, color);
            return;
        }
        int alpha = Math.round(mBackgroundAlpha * 255f);
        int translucent = (alpha << 24) | (color & 0x00ffffff);
        flushGlyphBatch();
        GLES20.glDisable(GLES20.GL_BLEND);
        drawRect(x, y, width, height, translucent);
        GLES20.glEnable(GLES20.GL_BLEND);
    }

    private void setClearColor(int color, float alpha) {
        GLES20.glClearColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, alpha);
    }

    private static int createProgram() {
        String vertexShader =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){ gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aTexCoord; }";
        String fragmentShader =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "uniform float uTextured;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){ if (uTextured > 2.5) { vec4 c = texture2D(uTexture, vTexCoord); gl_FragColor = vec4(c.rgb * c.a, c.a); } else if (uTextured > 1.5) { gl_FragColor = texture2D(uTexture, vTexCoord); } else { float a = uTextured > 0.5 ? texture2D(uTexture, vTexCoord).a : 1.0; gl_FragColor = vec4(uColor.rgb, uColor.a * a); } }";
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertexShader));
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader));
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link terminal GPU shader program: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile terminal GPU shader: " + log);
        }
        return shader;
    }

    private static final class Glyph {
        final int x;
        final int y;
        final int width;
        final int height;
        /** True when the rasterized glyph carries its own color (emoji / color
         * fonts) and must be drawn via the full-RGBA path instead of being tinted
         * by the cell foreground through the alpha-coverage path. */
        final boolean colored;

        Glyph(int x, int y, int width, int height, boolean colored) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.colored = colored;
        }
    }

    private static final class KittyTexture {
        final int textureId;
        final int width;
        final int height;
        final int format;
        final int sizeBytes;

        KittyTexture(int textureId, int width, int height, int format, int sizeBytes) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.format = format;
            this.sizeBytes = sizeBytes;
        }
    }
}
