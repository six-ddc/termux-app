package com.termux.autotermux.keepalive

class KeepAliveStartupException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)
