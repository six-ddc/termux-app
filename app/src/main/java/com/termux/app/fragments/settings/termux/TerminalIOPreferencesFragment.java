package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.app.activities.TermuxPlusSnippetsActivity;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

@Keep
public class TerminalIOPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TerminalIOPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey);

        Preference manageSnippetsPreference = findPreference("termuxplus_manage_snippets");
        if (manageSnippetsPreference != null) {
            manageSnippetsPreference.setOnPreferenceClickListener(preference -> {
                TermuxPlusSnippetsActivity.start(context);
                return true;
            });
        }
    }

}

class TerminalIOPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalIOPreferencesDataStore mInstance;

    private TerminalIOPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalIOPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalIOPreferencesDataStore(context);
        }
        return mInstance;
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR:
                mPreferences.setShowTerminalToolbar(value);
                break;
            case TERMUX_APP.KEY_TERMUXPLUS_SNIPPETS_ENABLED:
                mPreferences.setTermuxPlusSnippetsEnabled(value);
                break;
            case TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED:
                mPreferences.setSoftKeyboardEnabled(value);
                break;
            case TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE:
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return defValue;
        if (key == null) return defValue;

        switch (key) {
            case TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR:
                return mPreferences.shouldShowTerminalToolbar();
            case TERMUX_APP.KEY_TERMUXPLUS_SNIPPETS_ENABLED:
                return mPreferences.areTermuxPlusSnippetsEnabled();
            case TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED:
                return mPreferences.isSoftKeyboardEnabled();
            case TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE:
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware();
            default:
                return defValue;
        }
    }

}
