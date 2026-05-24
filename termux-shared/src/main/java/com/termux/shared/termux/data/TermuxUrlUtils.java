package com.termux.shared.termux.data;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TermuxUrlUtils {

    public static Pattern URL_MATCH_REGEX;

    public static Pattern getUrlMatchRegex() {
        if (URL_MATCH_REGEX != null) return URL_MATCH_REGEX;

        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");                       // Begin matching group.
        regex_sb.append("(?:");                     // Begin scheme group.
        regex_sb.append("dav|");                    // The DAV proto.
        regex_sb.append("dict|");                   // The DICT proto.
        regex_sb.append("dns|");                    // The DNS proto.
        regex_sb.append("file|");                   // File path.
        regex_sb.append("finger|");                 // The Finger proto.
        regex_sb.append("ftp(?:s?)|");              // The FTP proto.
        regex_sb.append("git|");                    // The Git proto.
        regex_sb.append("gemini|");                 // The Gemini proto.
        regex_sb.append("gopher|");                 // The Gopher proto.
        regex_sb.append("http(?:s?)|");             // The HTTP proto.
        regex_sb.append("imap(?:s?)|");             // The IMAP proto.
        regex_sb.append("irc(?:[6s]?)|");           // The IRC proto.
        regex_sb.append("ip[fn]s|");                // The IPFS proto.
        regex_sb.append("ldap(?:s?)|");             // The LDAP proto.
        regex_sb.append("pop3(?:s?)|");             // The POP3 proto.
        regex_sb.append("redis(?:s?)|");            // The Redis proto.
        regex_sb.append("rsync|");                  // The Rsync proto.
        regex_sb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regex_sb.append("sftp|");                   // The SFTP proto.
        regex_sb.append("smb(?:s?)|");              // The SAMBA proto.
        regex_sb.append("smtp(?:s?)|");             // The SMTP proto.
        regex_sb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regex_sb.append("tcp|");                    // The TCP proto.
        regex_sb.append("telnet|");                 // The Telnet proto.
        regex_sb.append("tftp|");                   // The TFTP proto.
        regex_sb.append("udp|");                    // The UDP proto.
        regex_sb.append("vnc|");                    // The VNC proto.
        regex_sb.append("ws(?:s?)");                // The Websocket proto.
        regex_sb.append(")://");                    // End scheme group.
        regex_sb.append("[^\\s<>\"'`]+");           // URL body, including query strings and fragments.
        regex_sb.append(")");                       // End matching group.

        URL_MATCH_REGEX = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        return URL_MATCH_REGEX;
    }

    public static LinkedHashSet<CharSequence> extractUrls(String text) {
        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = getUrlMatchRegex().matcher(text);

        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = trimUrlMatch(text.substring(matchStart, matchEnd));
            if (!url.isEmpty()) urlSet.add(url);
        }

        return urlSet;
    }

    private static String trimUrlMatch(String url) {
        int end = url.length();
        while (end > 0 && shouldTrimTrailingChar(url, end))
            end--;
        return url.substring(0, end);
    }

    private static boolean shouldTrimTrailingChar(String url, int end) {
        char c = url.charAt(end - 1);
        switch (c) {
            case '.':
            case ',':
            case ';':
            case ':':
            case '!':
            case '?':
                return true;
            case ')':
                return hasMoreClosingThanOpening(url, end, '(', ')');
            case ']':
                return hasMoreClosingThanOpening(url, end, '[', ']');
            case '}':
                return hasMoreClosingThanOpening(url, end, '{', '}');
            default:
                return false;
        }
    }

    private static boolean hasMoreClosingThanOpening(String text, int end, char open, char close) {
        int balance = 0;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c == open)
                balance++;
            else if (c == close)
                balance--;
        }
        return balance < 0;
    }

}
