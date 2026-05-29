package com.termux.terminal;

/**
 * Immutable, self-consistent snapshot of everything the GPU renderer needs to
 * draw one frame.
 *
 * <p>It exists to keep the GL render thread away from the libghostty-vt native
 * context entirely. The native terminal/render-state is single-threaded by
 * design, but {@link TerminalSession} writes PTY output (and thus mutates the
 * native context) on the main thread while {@code TerminalView} renders on the
 * dedicated GLSurfaceView thread. Previously {@code onDrawFrame} issued live
 * JNI reads (columns/rows/reverse-video/cursor) and even mutated native
 * render-state via {@code clearRenderDirtyState}, racing the main thread.
 *
 * <p>{@link GhosttyTerminalEngine} builds one of these on the main thread at the
 * end of each snapshot pass and publishes it through a single {@code volatile}
 * reference. The renderer grabs the reference once per frame and reads only this
 * object — no native access, no tearing between fields.
 *
 * <p>All arrays referenced here are owned by the snapshot and must not be
 * mutated after publication. {@code colors} is a copy (the engine keeps mutating
 * its live palette in place). {@code cells}/{@code cellText}/{@code dirtyRows}
 * are freshly allocated per snapshot pass, so retaining them is safe.
 */
public final class TerminalRenderSnapshot {

    public final int[] cells;
    public final String[] cellText;
    public final int dirtyState;
    public final int[] dirtyRows;
    public final TerminalKittyGraphicsPlacement[] kittyPlacements;
    public final int[] colors;
    public final int columns;
    public final int rows;
    public final boolean reverseVideo;
    public final int cursorCol;
    public final int cursorRow;
    public final int cursorStyle;
    /** Cursor visibility ignoring the blink phase (viewport + DECTCEM + render-state). */
    public final boolean cursorVisibleIgnoringBlink;
    /** Whether the blink phase modulates cursor visibility (false for password input). */
    public final boolean cursorSubjectToBlink;
    public final boolean cursorWideTail;

    public TerminalRenderSnapshot(int[] cells, String[] cellText, int dirtyState, int[] dirtyRows,
                                  TerminalKittyGraphicsPlacement[] kittyPlacements, int[] colors,
                                  int columns, int rows, boolean reverseVideo,
                                  int cursorCol, int cursorRow, int cursorStyle,
                                  boolean cursorVisibleIgnoringBlink, boolean cursorSubjectToBlink,
                                  boolean cursorWideTail) {
        this.cells = cells;
        this.cellText = cellText;
        this.dirtyState = dirtyState;
        this.dirtyRows = dirtyRows;
        this.kittyPlacements = kittyPlacements;
        this.colors = colors;
        this.columns = columns;
        this.rows = rows;
        this.reverseVideo = reverseVideo;
        this.cursorCol = cursorCol;
        this.cursorRow = cursorRow;
        this.cursorStyle = cursorStyle;
        this.cursorVisibleIgnoringBlink = cursorVisibleIgnoringBlink;
        this.cursorSubjectToBlink = cursorSubjectToBlink;
        this.cursorWideTail = cursorWideTail;
    }
}
