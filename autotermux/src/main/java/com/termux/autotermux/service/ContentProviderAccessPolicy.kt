package com.termux.autotermux.service

import android.content.Context

internal object ContentProviderAccessPolicy {
    private const val ROOT_UID = 0
    // `adb shell content ...` calls come from the shell user on all supported versions.
    private const val SHELL_UID = 2000
    private const val TERMUX_PACKAGE_NAME = "com.termux"

    fun isUidAllowed(context: Context, callingUid: Int, appUid: Int): Boolean {
        if (callingUid == appUid || callingUid == SHELL_UID || callingUid == ROOT_UID) {
            return true
        }

        return context.packageManager
            .getPackagesForUid(callingUid)
            ?.contains(TERMUX_PACKAGE_NAME) == true
    }
}
