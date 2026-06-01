package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.View;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private static final String KEY_SNIPPETS = "SNIPPETS";
    private static final String KEY_SNIPPETS_MANAGE = "SNIPPETS_MANAGE";
    private static final String TERMUXPLUS_DEFAULT_EXTRA_KEYS =
        "[[\"LEFT\",\"UP\",\"DOWN\",\"RIGHT\",{\"key\":\"SNIPPETS\",\"display\":\"{}\"},\"PASTE\",\"KEYBOARD\",\"HOME\",\"PGUP\",\"END\",\"'\",\":\",\";\",\"!\",\"^\",\"%\",\"=\",\"`\",\"{\",\"}\",\"[\",\"]\",\"F1\",\"F2\",\"F3\",\"F4\",{\"macro\":\"CTRL _\",\"display\":\"^_\"},{\"macro\":\"CTRL W\",\"display\":\"^W\"},{\"macro\":\"CTRL R\",\"display\":\"^R\"},{\"macro\":\"CTRL X CTRL X\",\"display\":\"^XX\"},{\"macro\":\"CTRL C\",\"display\":\"^C\"},{\"macro\":\"CTRL L\",\"display\":\"^L\"}]," +
            "[{\"macro\":\"SHIFT TAB\",\"display\":\"S-TAB\"},\"CTRL\",\"ESC\",\"/\",\"ALT\",\"TAB\",\"INS\",\"DEL\",\"PGDN\",\"|\",\"\\\\\",\"?\",\"-\",\"~\",\"@\",\"$\",\"*\",\"<\",\">\",\"(\",\")\",\"F5\",\"F6\",\"F7\",\"F8\",\"F9\",\"F10\",\"F11\",\"F12\",{\"macro\":\"CTRL S\",\"display\":\"^S\"},{\"macro\":\"CTRL Z\",\"display\":\"^Z\"},{\"macro\":\"CTRL X\",\"display\":\"^X\"},{\"macro\":\"CTRL G\",\"display\":\"^G\"},{\"macro\":\"CTRL N\",\"display\":\"^N\"},{\"macro\":\"CTRL P\",\"display\":\"^P\"}]]";
    private static final String FALLBACK_EXTRA_KEYS =
        "[[\"ESC\",\"/\",{\"key\":\"-\",\"popup\":\"|\"},\"HOME\",\"UP\",\"END\",\"PGUP\"]," +
            "[\"TAB\",\"CTRL\",\"ALT\",\"LEFT\",\"DOWN\",\"RIGHT\",\"PGDN\"]]";

    private final List<ExtraKeysInfo> mExtraKeysInfos = new ArrayList<>();
    private int mCurrentExtraKeysPage;

    final TermuxActivity mActivity;
    final TermuxTerminalViewClient mTermuxTerminalViewClient;
    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    private static final String LOG_TAG = "TermuxTerminalExtraKeys";

    public TermuxTerminalExtraKeys(TermuxActivity activity, @NonNull TerminalView terminalView,
                                   TermuxTerminalViewClient termuxTerminalViewClient,
                                   TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        setExtraKeys();
    }


    /**
     * Set the terminal extra keys and style.
     */
    private void setExtraKeys() {
        mExtraKeysInfos.clear();

        String extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);
            if (extrakeys == null || extrakeys.trim().isEmpty() || TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS.equals(extrakeys))
                extrakeys = TERMUXPLUS_DEFAULT_EXTRA_KEYS;

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mExtraKeysInfos.add(new ExtraKeysInfo(extrakeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
        } catch (Exception e) {
            Logger.showToast(mActivity, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfos.add(new ExtraKeysInfo(TERMUXPLUS_DEFAULT_EXTRA_KEYS, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
            } catch (Exception e2) {
                Logger.showToast(mActivity, "Can't create default extra keys",true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e2);
                try {
                    mExtraKeysInfos.add(new ExtraKeysInfo(FALLBACK_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
                } catch (JSONException e3) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Could create fallback extra keys: ", e3);
                }
            }
        }

        if (mCurrentExtraKeysPage >= mExtraKeysInfos.size())
            mCurrentExtraKeysPage = 0;
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        if (mExtraKeysInfos.isEmpty()) return null;
        return mExtraKeysInfos.get(mCurrentExtraKeysPage);
    }

    public ExtraKeysInfo getExtraKeysInfo(int page) {
        if (mExtraKeysInfos.isEmpty()) return null;
        if (page < 0 || page >= mExtraKeysInfos.size()) return null;
        return mExtraKeysInfos.get(page);
    }

    public int getExtraKeysPageCount() {
        return mExtraKeysInfos.size();
    }

    public int getCurrentExtraKeysPage() {
        return mCurrentExtraKeysPage;
    }

    public int getMaxExtraKeysRows() {
        int maxRows = 0;
        for (ExtraKeysInfo extraKeysInfo : mExtraKeysInfos) {
            if (extraKeysInfo != null && extraKeysInfo.getMatrix() != null)
                maxRows = Math.max(maxRows, extraKeysInfo.getMatrix().length);
        }
        return maxRows;
    }

    public boolean switchExtraKeysPage(int direction) {
        int size = mExtraKeysInfos.size();
        if (size <= 1) return false;

        if (direction > 0)
            mCurrentExtraKeysPage = (mCurrentExtraKeysPage + 1) % size;
        else
            mCurrentExtraKeysPage = (mCurrentExtraKeysPage + size - 1) % size;
        return true;
    }

    public void setCurrentExtraKeysPage(int page) {
        if (page < 0 || page >= mExtraKeysInfos.size()) return;
        mCurrentExtraKeysPage = page;
    }

    public boolean isFirstExtraKeysPage() {
        return mCurrentExtraKeysPage == 0;
    }

    public void reloadExtraKeys() {
        setExtraKeys();
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            // The legacy session drawer was removed; session actions live in the top tab strip.
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mTermuxTerminalViewClient.getActivity().getTerminalView();
            if (terminalView != null && terminalView.mTerminalEngine != null)
                terminalView.mTerminalEngine.toggleAutoScrollDisabled();
        } else if (KEY_SNIPPETS.equals(key)) {
            mActivity.showTerminalToolbarSnippetsPopup();
        } else if (KEY_SNIPPETS_MANAGE.equals(key)) {
            mActivity.openTermuxPlusSnippetsManager();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
