package com.termux.autotermux

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.termux.autotermux.events.EventHub
import com.termux.autotermux.events.model.DeviceEvent
import com.termux.autotermux.events.model.EventType
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.keepalive.KeepAliveController
import com.termux.autotermux.state.AppForegroundTransitionTracker
import com.termux.autotermux.state.AppVisibilityTracker
import com.termux.autotermux.triggers.TriggerRuntime

class AutoTermuxApplication : Application() {
    private val foregroundTransitionTracker =
        AppForegroundTransitionTracker(
            onForeground = ::onAppForegrounded,
            onBackground = ::onAppBackgrounded,
        )

    override fun onCreate() {
        super.onCreate()
        val configManager = ConfigManager.getInstance(this)
        EventHub.init(configManager)
        TriggerRuntime.initialize(this)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) {
                    foregroundTransitionTracker.onActivityStarted()
                }

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    foregroundTransitionTracker.onActivityStopped()
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun onAppForegrounded() {
        AppVisibilityTracker.setForeground(true)
        EventHub.emit(DeviceEvent(EventType.APP_FOREGROUND))
        KeepAliveController.retryStartupIfEnabledAndInactive(this)?.let { reason ->
            Log.w(
                TAG,
                "Deferred keep-awake startup still blocked after app entered foreground: $reason",
            )
        }
    }

    private fun onAppBackgrounded() {
        AppVisibilityTracker.setForeground(false)
        EventHub.emit(DeviceEvent(EventType.APP_BACKGROUND))
    }

    companion object {
        private const val TAG = "AutoTermuxApplication"
    }
}
