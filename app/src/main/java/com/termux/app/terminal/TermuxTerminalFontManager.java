package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Typeface;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

public final class TermuxTerminalFontManager {

    private static final String LOG_TAG = "TermuxTerminalFontManager";
    private static final String DEFAULT_FONT_ASSET_PATH = "termuxplus/fonts/JetBrainsMonoNerdFontMono-Regular.ttf";

    // Typeface.createFromFile() does NOT cache, so it re-reads and re-parses the whole TTF on every
    // call (user Nerd Fonts can be several MB). loadUserTypeface() is invoked from ~6 main-thread
    // sites (activity start/reload and each sheet/panel open), so memoize the parsed Typeface keyed
    // by the font file's absolute path + lastModified(). An edited font changes lastModified() and
    // naturally invalidates the cache.
    private static final Object USER_TYPEFACE_LOCK = new Object();
    private static String sUserTypefaceCachePath;
    private static long sUserTypefaceCacheLastModified;
    private static Typeface sUserTypefaceCache;

    private TermuxTerminalFontManager() {}

    public static Typeface loadTerminalTypeface(Context context) {
        Typeface userTypeface = loadUserTypeface();
        if (userTypeface != null) {
            Logger.logInfo(LOG_TAG,
                "Loaded user terminal font from \"" + TermuxConstants.TERMUX_FONT_FILE_PATH + "\"");
            return userTypeface;
        }

        Typeface bundledTypeface = loadBundledTypeface(context);
        if (bundledTypeface != null) {
            Logger.logInfo(LOG_TAG, "Loaded bundled terminal font from \"" + DEFAULT_FONT_ASSET_PATH + "\"");
            return bundledTypeface;
        }

        Logger.logInfo(LOG_TAG, "Using Android monospace terminal font fallback");
        return Typeface.MONOSPACE;
    }

    private static Typeface loadUserTypeface() {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        if (!fontFile.isFile() || fontFile.length() <= 0)
            return null;

        String path = fontFile.getAbsolutePath();
        long lastModified = fontFile.lastModified();

        synchronized (USER_TYPEFACE_LOCK) {
            if (sUserTypefaceCache != null
                    && path.equals(sUserTypefaceCachePath)
                    && lastModified == sUserTypefaceCacheLastModified) {
                return sUserTypefaceCache;
            }

            try {
                Typeface typeface = Typeface.createFromFile(fontFile);
                sUserTypefaceCache = typeface;
                sUserTypefaceCachePath = path;
                sUserTypefaceCacheLastModified = lastModified;
                return typeface;
            } catch (Exception e) {
                sUserTypefaceCache = null;
                sUserTypefaceCachePath = null;
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to load user terminal font at \"" + TermuxConstants.TERMUX_FONT_FILE_PATH + "\"", e);
                return null;
            }
        }
    }

    private static Typeface loadBundledTypeface(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), DEFAULT_FONT_ASSET_PATH);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to load bundled terminal font at \"" + DEFAULT_FONT_ASSET_PATH + "\"", e);
            return null;
        }
    }
}
