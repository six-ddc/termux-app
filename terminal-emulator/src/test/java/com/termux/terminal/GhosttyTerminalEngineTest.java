package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * JVM-side smoke test for {@link GhosttyTerminalEngine}.
 *
 * The native library {@code libtermux.so} is built for Android ABIs only, so on a host JVM
 * (Linux x86_64, macOS arm64, etc.) {@link System#loadLibrary(String)} in {@link JNI} will
 * fail and {@link GhosttyTerminalEngine#isAvailable()} will throw {@link UnsatisfiedLinkError}
 * (wrapped in {@link ExceptionInInitializerError} on first class load).
 *
 * This test is deliberately structured so that:
 *  - on a host without the native library it skips via JUnit Assume (a clean PASS, not a failure);
 *  - on an Android instrumented runtime where the library is loadable it exercises the real
 *    write → snapshot → render-cell path end-to-end.
 *
 * End-to-end correctness on a real device is covered by
 * {@code scripts/check-ghostty-no-fallback.sh --runtime ...}.
 */
public class GhosttyTerminalEngineTest {

    private static boolean nativeLibraryLoadable() {
        try {
            return GhosttyTerminalEngine.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static TerminalOutput silentSession() {
        return new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
    }

    @Test
    public void isAvailableNeverThrows() {
        nativeLibraryLoadable();
    }

    @Test
    public void writeProducesCodepointsInRenderCells() {
        assumeTrue("libghostty-vt not loadable in this JVM", nativeLibraryLoadable());

        GhosttyTerminalEngine engine = new GhosttyTerminalEngine(
                silentSession(), 80, 24, 12, 24, 1000, null);

        byte[] hello = new byte[]{'h', 'e', 'l', 'l', 'o'};
        engine.append(hello, hello.length);

        int[] cells = engine.getRenderCells();
        assertNotNull("render cells must be non-null after write", cells);
        assertTrue("render cells must hold a full 80x24 grid",
                cells.length >= 80 * 24 * TerminalEngine.RENDER_CELL_STRIDE);

        char[] expected = {'h', 'e', 'l', 'l', 'o'};
        for (int i = 0; i < expected.length; i++) {
            int base = i * TerminalEngine.RENDER_CELL_STRIDE;
            assertEquals("cell " + i + " codepoint",
                    expected[i],
                    cells[base + TerminalEngine.RENDER_CELL_CODEPOINT]);
        }

        assertEquals("cursor should advance to column 5", 5, engine.getCursorCol());
        assertEquals("cursor should stay on row 0", 0, engine.getCursorRow());
    }

    @Test
    public void engineReportsGhosttyBacked() {
        assumeTrue("libghostty-vt not loadable in this JVM", nativeLibraryLoadable());

        GhosttyTerminalEngine engine = new GhosttyTerminalEngine(
                silentSession(), 80, 24, 12, 24, 1000, null);
        assertTrue("GhosttyTerminalEngine must report itself as Ghostty-backed",
                engine.isGhosttyBacked());
    }
}
