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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private static final String KEY_SNIPPETS = "SNIPPETS";
    private static final String KEY_SNIPPETS_MANAGE = "SNIPPETS_MANAGE";
    private static final String KEY_EXTRA_KEYS_TOGGLE = "TP_KEYS_TOGGLE";
    private static final int EXTRA_KEYS_COLUMN_COUNT = 6;
    private static final int PINNED_EXTRA_KEYS_ROWS = 2;
    private static final String TERMUXPLUS_PRIMARY_EXTRA_KEYS =
        "[[\"ESC\",\"TAB\",\"CTRL\",\"ALT\",\"/\",\"-\"]," +
            "[\"LEFT\",\"UP\",\"DOWN\",\"RIGHT\",{\"key\":\"SNIPPETS\",\"display\":\"{}\"}," +
            "{\"key\":\"" + KEY_EXTRA_KEYS_TOGGLE + "\",\"display\":\"⌨\"}]]";
    private static final String TERMUXPLUS_EXPANDED_EXTRA_KEYS =
        "[[\"PASTE\",\"HOME\",\"END\",\"PGUP\",\"PGDN\",{\"macro\":\"SHIFT TAB\",\"display\":\"S-TAB\"}]," +
            "[\"INS\",\"DEL\",\"|\",\"\\\\\",\"?\",\":\"]," +
            "[\";\",\"'\",\"`\",\"~\",\"=\",\"^\"]," +
            "[\"%\",\"$\",\"@\",\"!\",\"*\",\"_\"]," +
            "[\"{\",\"}\",\"[\",\"]\",\"(\",\")\"]," +
            "[\"<\",\">\",\"F1\",\"F2\",\"F3\",\"F4\"]," +
            "[\"F5\",\"F6\",\"F7\",\"F8\",\"F9\",\"F10\"]," +
            "[\"F11\",\"F12\",{\"macro\":\"CTRL C\",\"display\":\"^C\"},{\"macro\":\"CTRL L\",\"display\":\"^L\"},{\"macro\":\"CTRL R\",\"display\":\"^R\"},{\"macro\":\"CTRL W\",\"display\":\"^W\"}]," +
            "[{\"macro\":\"CTRL Z\",\"display\":\"^Z\"},{\"macro\":\"CTRL S\",\"display\":\"^S\"},{\"macro\":\"CTRL X\",\"display\":\"^X\"},{\"macro\":\"CTRL G\",\"display\":\"^G\"},{\"macro\":\"CTRL N\",\"display\":\"^N\"},{\"macro\":\"CTRL P\",\"display\":\"^P\"}]," +
            "[{\"macro\":\"CTRL _\",\"display\":\"^_\"},{\"macro\":\"CTRL X CTRL X\",\"display\":\"^XX\"}]]";
    private static final String FALLBACK_PRIMARY_EXTRA_KEYS =
        "[[\"ESC\",\"TAB\",\"CTRL\",\"ALT\",\"/\",\"-\"]," +
            "[\"LEFT\",\"UP\",\"DOWN\",\"RIGHT\",\"PASTE\",{\"key\":\"" + KEY_EXTRA_KEYS_TOGGLE + "\",\"display\":\"⌨\"}]]";
    private static final String FALLBACK_EXPANDED_EXTRA_KEYS =
        "[[\"HOME\",\"END\",\"PGUP\",\"PGDN\",\"INS\",\"DEL\"]]";

    private final List<ExtraKeysInfo> mPrimaryExtraKeysInfos = new ArrayList<>();
    private final List<ExtraKeysInfo> mExpandedExtraKeysInfos = new ArrayList<>();
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
        mPrimaryExtraKeysInfos.clear();
        mExpandedExtraKeysInfos.clear();

        String extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
        try {
            // The mMap stores the extra key and style string values while loading properties.
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);
            boolean usingDefaultExtraKeys = extrakeys == null || extrakeys.trim().isEmpty() ||
                TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS.equals(extrakeys);
            String primaryExtraKeys = TERMUXPLUS_PRIMARY_EXTRA_KEYS;
            String expandedExtraKeys = TERMUXPLUS_EXPANDED_EXTRA_KEYS;
            if (!usingDefaultExtraKeys) {
                String[] splitExtraKeys = splitExtraKeysForTermuxPlus(extrakeys);
                primaryExtraKeys = splitExtraKeys[0];
                expandedExtraKeys = splitExtraKeys[1];
            }

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mPrimaryExtraKeysInfos.add(new ExtraKeysInfo(primaryExtraKeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
            mExpandedExtraKeysInfos.add(new ExtraKeysInfo(expandedExtraKeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
        } catch (Exception e) {
            Logger.showToast(mActivity, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mPrimaryExtraKeysInfos.add(new ExtraKeysInfo(TERMUXPLUS_PRIMARY_EXTRA_KEYS, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
                mExpandedExtraKeysInfos.add(new ExtraKeysInfo(TERMUXPLUS_EXPANDED_EXTRA_KEYS, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
            } catch (Exception e2) {
                Logger.showToast(mActivity, "Can't create default extra keys",true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e2);
                try {
                    mPrimaryExtraKeysInfos.add(new ExtraKeysInfo(FALLBACK_PRIMARY_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
                    mExpandedExtraKeysInfos.add(new ExtraKeysInfo(FALLBACK_EXPANDED_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES));
                } catch (JSONException e3) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Could create fallback extra keys: ", e3);
                }
            }
        }

        if (mCurrentExtraKeysPage >= mPrimaryExtraKeysInfos.size())
            mCurrentExtraKeysPage = 0;
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return getPrimaryExtraKeysInfo(mCurrentExtraKeysPage);
    }

    public ExtraKeysInfo getExtraKeysInfo(int page) {
        return getPrimaryExtraKeysInfo(page);
    }

    public ExtraKeysInfo getPrimaryExtraKeysInfo(int page) {
        if (mPrimaryExtraKeysInfos.isEmpty()) return null;
        if (page < 0 || page >= mPrimaryExtraKeysInfos.size()) return null;
        return mPrimaryExtraKeysInfos.get(page);
    }

    public ExtraKeysInfo getExpandedExtraKeysInfo(int page) {
        if (mExpandedExtraKeysInfos.isEmpty()) return null;
        if (page < 0 || page >= mExpandedExtraKeysInfos.size()) return null;
        return mExpandedExtraKeysInfos.get(page);
    }

    public int getExtraKeysPageCount() {
        return mPrimaryExtraKeysInfos.size();
    }

    public int getCurrentExtraKeysPage() {
        return mCurrentExtraKeysPage;
    }

    public int getMaxExtraKeysRows() {
        return PINNED_EXTRA_KEYS_ROWS;
    }

    public int getPinnedExtraKeysRows() {
        return PINNED_EXTRA_KEYS_ROWS;
    }

    public int getExpandedExtraKeysRows() {
        int maxRows = 0;
        for (ExtraKeysInfo extraKeysInfo : mExpandedExtraKeysInfos) {
            if (extraKeysInfo != null && extraKeysInfo.getMatrix() != null)
                maxRows = Math.max(maxRows, extraKeysInfo.getMatrix().length);
        }
        return maxRows;
    }

    public boolean switchExtraKeysPage(int direction) {
        int size = mPrimaryExtraKeysInfos.size();
        if (size <= 1) return false;

        if (direction > 0)
            mCurrentExtraKeysPage = (mCurrentExtraKeysPage + 1) % size;
        else
            mCurrentExtraKeysPage = (mCurrentExtraKeysPage + size - 1) % size;
        return true;
    }

    public void setCurrentExtraKeysPage(int page) {
        if (page < 0 || page >= mPrimaryExtraKeysInfos.size()) return;
        mCurrentExtraKeysPage = page;
    }

    public boolean isFirstExtraKeysPage() {
        return mCurrentExtraKeysPage == 0;
    }

    public void reloadExtraKeys() {
        setExtraKeys();
    }

    private String[] splitExtraKeysForTermuxPlus(String extraKeys) throws JSONException {
        JSONArray flattenedKeys = flattenExtraKeys(extraKeys);
        JSONArray primaryRows = new JSONArray();
        JSONArray expandedRows = new JSONArray();
        int sourceIndex = 0;

        for (int row = 0; row < PINNED_EXTRA_KEYS_ROWS; row++) {
            JSONArray primaryRow = new JSONArray();
            primaryRows.put(primaryRow);
            for (int col = 0; col < EXTRA_KEYS_COLUMN_COUNT; col++) {
                if (row == PINNED_EXTRA_KEYS_ROWS - 1 && col == EXTRA_KEYS_COLUMN_COUNT - 1) {
                    primaryRow.put(createExtraKeysToggleConfig());
                } else if (sourceIndex < flattenedKeys.length()) {
                    primaryRow.put(flattenedKeys.get(sourceIndex++));
                }
            }
        }

        JSONArray expandedRow = null;
        for (int col = 0; sourceIndex < flattenedKeys.length(); sourceIndex++, col++) {
            if (col % EXTRA_KEYS_COLUMN_COUNT == 0) {
                expandedRow = new JSONArray();
                expandedRows.put(expandedRow);
            }
            expandedRow.put(flattenedKeys.get(sourceIndex));
        }

        return new String[]{primaryRows.toString(), expandedRows.toString()};
    }

    private JSONArray flattenExtraKeys(String extraKeys) throws JSONException {
        JSONArray rows = new JSONArray(extraKeys);
        JSONArray flattenedKeys = new JSONArray();
        for (int row = 0; row < rows.length(); row++) {
            JSONArray keys = rows.getJSONArray(row);
            for (int col = 0; col < keys.length(); col++)
                flattenedKeys.put(keys.get(col));
        }
        return flattenedKeys;
    }

    private JSONObject createExtraKeysToggleConfig() throws JSONException {
        JSONObject toggle = new JSONObject();
        toggle.put("key", KEY_EXTRA_KEYS_TOGGLE);
        toggle.put("display", "⌨");
        return toggle;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if (KEY_EXTRA_KEYS_TOGGLE.equals(key)) {
            mActivity.toggleTermuxPlusExtraKeysPanel();
        } else if ("KEYBOARD".equals(key)) {
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
