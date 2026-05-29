package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/** A client which receives callbacks from events triggered by feeding input to a {@link TerminalEngine}. */
public abstract class TerminalOutput {

    /** Write a string using the UTF-8 encoding to the terminal client. */
    public final void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write bytes to the terminal client. */
    public abstract void write(byte[] data, int offset, int count);

    /** Notify the terminal client that the terminal title has changed. */
    public abstract void titleChanged(String oldTitle, String newTitle);

    /** Notify the terminal client that text should be copied to clipboard. */
    public abstract void onCopyTextToClipboard(String text);

    /** Notify the terminal client that text should be pasted from clipboard. */
    public abstract void onPasteTextFromClipboard();

    /** Notify the terminal client that the program requested a desktop
     * notification (OSC 9 / OSC 777). Default is a no-op so non-UI clients are
     * unaffected. {@code title} may be null (OSC 9 carries only a body). */
    public void onShowNotification(String title, String body) {
    }

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received. */
    public abstract void onBell();

    public abstract void onColorsChanged();

}
