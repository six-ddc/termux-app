package com.termux.terminal;

import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented regression tests for {@link GhosttyTerminalEngine}. Unlike the
 * host-JVM {@link GhosttyTerminalEngineTest} (which skips via Assume because the
 * native library is Android-only), these run on a device/emulator where
 * {@code libtermux.so}/{@code libghostty-vt.so} load, so they exercise the real
 * write -> snapshot -> render-cell path and must pass, not skip.
 */
@RunWith(AndroidJUnit4.class)
public class GhosttyTerminalEngineInstrumentedTest {

    private static final String ESC = "\u001b";
    private static final String BEL = "\u0007";

    private TerminalOutput silentSession() {
        return new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
    }

    private GhosttyTerminalEngine newEngine(TerminalOutput session, int cols, int rows) {
        return new GhosttyTerminalEngine(session, cols, rows, 12, 24, 1000, null);
    }

    private void write(GhosttyTerminalEngine engine, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        engine.append(bytes, bytes.length);
    }

    private int codepointAt(int[] cells, int row, int col, int columns) {
        int base = (row * columns + col) * TerminalEngine.RENDER_CELL_STRIDE;
        return cells[base + TerminalEngine.RENDER_CELL_CODEPOINT];
    }

    private int widthAt(int[] cells, int row, int col, int columns) {
        int base = (row * columns + col) * TerminalEngine.RENDER_CELL_STRIDE;
        return cells[base + TerminalEngine.RENDER_CELL_WIDTH];
    }

    @Before
    public void requireNativeLibrary() {
        // On a correctly built test APK the native library must be present; if it
        // is not, fail loudly (this is the whole point of an instrumented test).
        assertTrue("libghostty-vt must be loadable on-device", GhosttyTerminalEngine.isAvailable());
    }

    @Test
    public void engineIsGhosttyBacked() {
        GhosttyTerminalEngine engine = newEngine(silentSession(), 80, 24);
        assertTrue(engine.isGhosttyBacked());
    }

    @Test
    public void writeProducesCodepointsAndAdvancesCursor() {
        GhosttyTerminalEngine engine = newEngine(silentSession(), 80, 24);
        write(engine, "hello");

        int[] cells = engine.getRenderCells();
        assertNotNull(cells);
        char[] expected = {'h', 'e', 'l', 'l', 'o'};
        for (int i = 0; i < expected.length; i++)
            assertEquals("cell " + i, expected[i], codepointAt(cells, 0, i, 80));
        assertEquals(5, engine.getCursorCol());
        assertEquals(0, engine.getCursorRow());
    }

    @Test
    public void wideCharOccupiesTwoColumns() {
        GhosttyTerminalEngine engine = newEngine(silentSession(), 80, 24);
        // U+4E16 (CJK) is an East-Asian wide character: width 2.
        write(engine, "世");

        int[] cells = engine.getRenderCells();
        assertEquals(0x4e16, codepointAt(cells, 0, 0, 80));
        assertEquals("CJK glyph must report width 2", 2, widthAt(cells, 0, 0, 80));
        assertEquals("cursor must advance by two columns", 2, engine.getCursorCol());
    }

    @Test
    public void csiCursorPositionPlacesText() {
        GhosttyTerminalEngine engine = newEngine(silentSession(), 80, 24);
        // CSI 3;5 H -> move to row 3, col 5 (1-based), then write 'X'.
        write(engine, ESC + "[3;5HX");

        int[] cells = engine.getRenderCells();
        assertEquals("X must land at row 2, col 4 (0-based)", 'X', codepointAt(cells, 2, 4, 80));
    }

    @Test
    public void resizePreservesContent() {
        GhosttyTerminalEngine engine = newEngine(silentSession(), 80, 24);
        write(engine, "RESIZE");
        engine.resize(100, 40, 12, 24);

        assertEquals(100, engine.getColumns());
        assertEquals(40, engine.getRows());
        int[] cells = engine.getRenderCells();
        assertEquals('R', codepointAt(cells, 0, 0, 100));
        assertEquals('E', codepointAt(cells, 0, 1, 100));
    }

    @Test
    public void osc52WritesDecodedTextToClipboard() {
        final AtomicReference<String> clipboard = new AtomicReference<>(null);
        TerminalOutput capturing = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) { clipboard.set(text); }
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
        GhosttyTerminalEngine engine = newEngine(capturing, 80, 24);

        String payload = "clip-from-instrumented-test";
        String b64 = Base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        write(engine, ESC + "]52;c;" + b64 + BEL);

        assertEquals("OSC 52 must deliver the decoded clipboard text", payload, clipboard.get());
    }
}
