package com.termux.autotermux.state

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object ConnectionStateManager {
    @Volatile private var httpServerUp: Boolean = false
    @Volatile private var httpServerPort: Int? = null
    @Volatile private var wsServerUp: Boolean = false
    @Volatile private var wsServerPort: Int? = null
    private val wsClientCount = AtomicInteger(0)
    @Volatile private var lastEventAtMs: Long = 0L
    @Volatile private var lastHttpChangeAtMs: Long = 0L
    @Volatile private var lastWsChangeAtMs: Long = 0L

    fun markHttpServerUp(port: Int) {
        httpServerUp = true
        httpServerPort = port
        lastHttpChangeAtMs = touch()
    }

    fun markHttpServerDown() {
        httpServerUp = false
        lastHttpChangeAtMs = touch()
    }

    fun markWsServerUp(port: Int) {
        wsServerUp = true
        wsServerPort = port
        wsClientCount.set(0)
        lastWsChangeAtMs = touch()
    }

    fun markWsServerDown() {
        wsServerUp = false
        wsClientCount.set(0)
        lastWsChangeAtMs = touch()
    }

    fun onWsClientConnected() {
        wsClientCount.incrementAndGet()
        touch()
    }

    fun onWsClientDisconnected() {
        wsClientCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        touch()
    }

    fun recordEvent() {
        touch()
    }

    fun isHttpServerUp(): Boolean = httpServerUp
    fun httpServerPort(): Int? = httpServerPort
    fun isWsServerUp(): Boolean = wsServerUp
    fun wsServerPort(): Int? = wsServerPort

    fun snapshot(): JSONObject {
        return JSONObject().apply {
            put("httpServer", httpServerUp)
            put("httpServerPort", httpServerPort ?: JSONObject.NULL)
            put("wsServer", wsServerUp)
            put("wsServerPort", wsServerPort ?: JSONObject.NULL)
            put("wsClients", wsClientCount.get())
            put("lastEventAtMs", lastEventAtMs)
            put("lastHttpChangeAtMs", lastHttpChangeAtMs)
            put("lastWsChangeAtMs", lastWsChangeAtMs)
        }
    }

    private fun touch(): Long {
        val now = System.currentTimeMillis()
        lastEventAtMs = now
        return now
    }
}
