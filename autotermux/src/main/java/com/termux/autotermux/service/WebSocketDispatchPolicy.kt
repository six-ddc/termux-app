package com.termux.autotermux.service

internal enum class WebSocketDispatchBucket {
    COMMAND,
    INSTALL,
}

internal object WebSocketDispatchPolicy {
    fun bucketForNormalizedMethod(normalizedMethod: String): WebSocketDispatchBucket {
        return when {
            normalizedMethod == "install" -> WebSocketDispatchBucket.INSTALL
            else -> WebSocketDispatchBucket.COMMAND
        }
    }

    internal fun shouldTraceExecutionTiming(normalizedMethod: String): Boolean =
        normalizedMethod == "state" ||
            normalizedMethod == "packages" ||
            normalizedMethod == "screenshot"
}
