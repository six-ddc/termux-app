package com.termux.app.autotermux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AutoTermuxInstallReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_COMPANION = "com.termux.autotermux.INSTALL_COMPANION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_COMPANION.equals(intent.getAction())) {
            return;
        }

        Intent installIntent = new Intent(context, AutoTermuxInstallActivity.class);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(installIntent);
    }
}
