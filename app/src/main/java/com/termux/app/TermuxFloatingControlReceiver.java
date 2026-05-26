package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Permission-protected (signature) trampoline that lets AutoTermux ask Termux
 * to hide or restore its floating-terminal bubble. Direct cross-package
 * {@code startService(TermuxService)} is rejected by the framework because
 * TermuxService is not exported, so this receiver does the local hop.
 *
 * Protected by {@code com.termux.permission.RUN_COMMAND} so only apps signed
 * with the same key (or holding the same signature-level permission) can
 * trigger it.
 */
public class TermuxFloatingControlReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxFloatingControl";

    public static final String ACTION_HIDE_FLOATING = "com.termux.HIDE_FLOATING";
    public static final String ACTION_SHOW_FLOATING = "com.termux.SHOW_FLOATING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!ACTION_HIDE_FLOATING.equals(action) && !ACTION_SHOW_FLOATING.equals(action)) {
            Log.w(LOG_TAG, "Ignoring unknown action: " + action);
            return;
        }
        try {
            Intent forward = new Intent(context, TermuxService.class).setAction(action);
            context.startService(forward);
        } catch (Throwable t) {
            Log.w(LOG_TAG, "Failed to forward " + action + ": " + t.getMessage());
        }
    }
}
