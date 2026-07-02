package com.termux.terminal;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Streaming scanner that taps the raw PTY output byte stream to recover OSC
 * sequences which the packaged libghostty-vt engine parses internally but does
 * not expose through its C API: OSC 52 (clipboard write) and OSC 9 / OSC 777
 * (desktop notification).
 *
 * <p>libghostty-vt consumes these sequences but offers no terminal callback nor
 * {@code ghostty_terminal_get} data accessor for their payloads (only the window
 * title and pwd are queryable), and its standalone OSC parser can only extract
 * the window-title string. So to deliver clipboard/notification we re-scan the
 * exact same bytes here, as a pure observer — the bytes are still handed
 * unchanged to the engine.
 *
 * <p>The scanner keeps state across {@link #feed} calls so sequences split
 * across writes are still recovered. It recognises only the OSC introducer
 * {@code ESC ]} (0x1b 0x5d) terminated by BEL (0x07) or ST ({@code ESC \}).
 * Recognised payloads are queued and drained by {@link GhosttyTerminalEngine}.
 */
final class TermuxPlusOscInterceptor {

    /** Hard cap on a single OSC payload we will buffer, to bound memory on
     * malformed or hostile input. OSC 52 clipboard writes can be sizeable but a
     * few hundred KB is a sane ceiling; oversized sequences are dropped. */
    private static final int MAX_OSC_BYTES = 256 * 1024;
    /** Cap on queued, not-yet-drained results so a flood cannot grow unbounded. */
    private static final int MAX_QUEUED = 32;

    private static final int STATE_GROUND = 0;
    private static final int STATE_ESC = 1;      // saw ESC, expecting ']'
    private static final int STATE_OSC = 2;      // inside OSC body
    private static final int STATE_OSC_ESC = 3;  // saw ESC inside OSC, expecting '\' (ST)

    private int mState = STATE_GROUND;
    private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream(256);
    private boolean mOverflowed;

    static final class ClipboardWrite {
        final String text;
        ClipboardWrite(String text) { this.text = text; }
    }

    static final class Notification {
        final String title;
        final String body;
        Notification(String title, String body) { this.title = title; this.body = body; }
    }

    private final ArrayDeque<ClipboardWrite> mClipboard = new ArrayDeque<>();
    private final ArrayDeque<Notification> mNotifications = new ArrayDeque<>();

    /** Feed the raw bytes handed to the engine. Pure observer; never mutates the array. */
    void feed(byte[] buffer, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i < end; i++)
            step(buffer[i] & 0xff);
    }

    private void step(int b) {
        // CAN (0x18) and SUB (0x1a) abort any in-progress escape/control string
        // anywhere in the stream (ECMA-48). libghostty honours this, so if we did
        // not, a program that begins an OSC 52 and then cancels it with CAN would
        // leave us buffering until the next BEL/ST and emit a clipboard write the
        // real terminal discarded. Cancel and return to ground on either.
        if (b == 0x18 || b == 0x1a) {
            mBuffer.reset();
            mOverflowed = false;
            mState = STATE_GROUND;
            return;
        }
        switch (mState) {
            case STATE_GROUND:
                if (b == 0x1b) mState = STATE_ESC;
                break;
            case STATE_ESC:
                if (b == 0x5d) {            // ']' -> OSC start
                    mState = STATE_OSC;
                    mBuffer.reset();
                    mOverflowed = false;
                } else {
                    // Not an OSC. Re-evaluate this byte from ground; it may itself
                    // be the ESC that starts the next sequence.
                    mState = (b == 0x1b) ? STATE_ESC : STATE_GROUND;
                }
                break;
            case STATE_OSC:
                if (b == 0x07) {            // BEL terminator
                    finishOsc();
                } else if (b == 0x1b) {     // possible ST (ESC '\')
                    mState = STATE_OSC_ESC;
                } else {
                    appendOscByte(b);
                }
                break;
            case STATE_OSC_ESC:
                if (b == 0x5c) {            // '\' -> ST terminator
                    finishOsc();
                } else {
                    // Lost sync; abandon this OSC and re-evaluate from ground.
                    mBuffer.reset();
                    mOverflowed = false;
                    mState = (b == 0x1b) ? STATE_ESC : STATE_GROUND;
                }
                break;
            default:
                mState = STATE_GROUND;
                break;
        }
    }

    private void appendOscByte(int b) {
        if (mBuffer.size() >= MAX_OSC_BYTES) {
            mOverflowed = true;
            return;
        }
        mBuffer.write(b);
    }

    private void finishOsc() {
        if (!mOverflowed && mBuffer.size() > 0)
            parseOsc(mBuffer.toByteArray());
        mBuffer.reset();
        mOverflowed = false;
        mState = STATE_GROUND;
    }

    private void parseOsc(byte[] data) {
        int semi = indexOf(data, (byte) ';', 0);
        if (semi <= 0) return;
        String command = new String(data, 0, semi, StandardCharsets.US_ASCII);
        switch (command) {
            case "52":
                parseClipboard(data, semi + 1);
                break;
            case "9":
                enqueueNotification(null, new String(data, semi + 1, data.length - (semi + 1), StandardCharsets.UTF_8));
                break;
            case "777":
                parseOsc777(data, semi + 1);
                break;
            default:
                break;
        }
    }

    /** OSC 52: {@code 52 ; <targets> ; <base64|?>}. A "?" data field is a read
     * request (program asks for clipboard contents) which we do not service. */
    private void parseClipboard(byte[] data, int from) {
        int semi = indexOf(data, (byte) ';', from);
        if (semi < 0) return;
        int dataStart = semi + 1;
        int dataLen = data.length - dataStart;
        if (dataLen <= 0) return;
        if (dataLen == 1 && data[dataStart] == '?') return; // read request, unsupported
        String base64 = new String(data, dataStart, dataLen, StandardCharsets.US_ASCII).trim();
        byte[] decoded;
        try {
            decoded = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (decoded == null || decoded.length == 0) return;
        enqueueClipboard(new String(decoded, StandardCharsets.UTF_8));
    }

    /** OSC 777: {@code 777 ; notify ; <title> ; <body>}. */
    private void parseOsc777(byte[] data, int from) {
        int semi = indexOf(data, (byte) ';', from);
        if (semi < 0) return;
        String kind = new String(data, from, semi - from, StandardCharsets.US_ASCII);
        if (!"notify".equals(kind)) return;
        int titleStart = semi + 1;
        int titleSemi = indexOf(data, (byte) ';', titleStart);
        if (titleSemi < 0) {
            enqueueNotification(null, new String(data, titleStart, data.length - titleStart, StandardCharsets.UTF_8));
        } else {
            String title = new String(data, titleStart, titleSemi - titleStart, StandardCharsets.UTF_8);
            String body = new String(data, titleSemi + 1, data.length - (titleSemi + 1), StandardCharsets.UTF_8);
            enqueueNotification(title, body);
        }
    }

    private void enqueueClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        if (mClipboard.size() >= MAX_QUEUED) mClipboard.pollFirst();
        mClipboard.addLast(new ClipboardWrite(text));
    }

    private void enqueueNotification(String title, String body) {
        if (body == null) return;
        if (mNotifications.size() >= MAX_QUEUED) mNotifications.pollFirst();
        mNotifications.addLast(new Notification(title, body));
    }

    ClipboardWrite pollClipboard() {
        return mClipboard.pollFirst();
    }

    Notification pollNotification() {
        return mNotifications.pollFirst();
    }

    /** Drop all parser state and queued results (terminal reset). */
    void reset() {
        mState = STATE_GROUND;
        mBuffer.reset();
        mOverflowed = false;
        mClipboard.clear();
        mNotifications.clear();
    }

    private static int indexOf(byte[] data, byte target, int from) {
        for (int i = from; i < data.length; i++)
            if (data[i] == target) return i;
        return -1;
    }
}
