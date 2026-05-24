package com.termux.autotermux.service

import com.termux.autotermux.api.ApiHandler
import com.termux.autotermux.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionDispatcherTest {
    @Test
    fun dispatchStateRoutesSimpleAndFullStateSeparately() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.getState() } returns ApiResponse.Success("state")
        every { apiHandler.getStateFull(true) } returns ApiResponse.Success("state_full")

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(ApiResponse.Success("state"), dispatcher.dispatch("state", JSONObject()))
        assertEquals(ApiResponse.Success("state_full"), dispatcher.dispatch("state_full", JSONObject()))
        verify(exactly = 1) { apiHandler.getState() }
        verify(exactly = 1) { apiHandler.getStateFull(true) }
    }

    @Test
    fun dispatchCachedLargeJsonRoutesToCacheMethods() {
        val apiHandler = mockk<ApiHandler>()
        val cached = ApiResponse.RawObject(JSONObject().put("path", "/sdcard/cache/state.json"))
        every { apiHandler.cacheState(full = true, filter = false) } returns cached
        every { apiHandler.cacheTree(full = true, filter = true) } returns cached
        every { apiHandler.cachePhoneState() } returns cached
        every { apiHandler.cachePackages() } returns cached

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(
            cached,
            dispatcher.dispatch("state_full/cache", JSONObject().put("filter", false)),
        )
        assertEquals(cached, dispatcher.dispatch("a11y_tree_full/cache", JSONObject()))
        assertEquals(cached, dispatcher.dispatch("phone_state/cache", JSONObject()))
        assertEquals(cached, dispatcher.dispatch("packages/cache", JSONObject()))
        verify(exactly = 1) { apiHandler.cacheState(full = true, filter = false) }
        verify(exactly = 1) { apiHandler.cacheTree(full = true, filter = true) }
        verify(exactly = 1) { apiHandler.cachePhoneState() }
        verify(exactly = 1) { apiHandler.cachePackages() }
    }

    @Test
    fun dispatchNodeActionPassesParamsToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val params = JSONObject()
            .put("action", "click")
            .put("textContains", "OK")
        val response = ApiResponse.RawObject(JSONObject().put("index", 7))
        every { apiHandler.performNodeAction(params) } returns response

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(response, dispatcher.dispatch("ui/action", params))
        verify(exactly = 1) { apiHandler.performNodeAction(params) }
    }

    @Test
    fun dispatchNodeQueryRoutesToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val params = JSONObject().put("textContains", "OK")
        val response = ApiResponse.RawObject(JSONObject().put("count", 1))
        val cached = ApiResponse.RawObject(JSONObject().put("path", "/sdcard/node-find.json"))
        val focused = ApiResponse.RawObject(JSONObject().put("focused", true))
        every { apiHandler.findNodes(params) } returns response
        every { apiHandler.cacheFindNodes(params) } returns cached
        every { apiHandler.getFocusedNode() } returns focused
        every { apiHandler.cacheFocusedNode() } returns cached

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(response, dispatcher.dispatch("ui/find", params))
        assertEquals(cached, dispatcher.dispatch("ui/find/cache", params))
        assertEquals(focused, dispatcher.dispatch("ui/focused", JSONObject()))
        assertEquals(cached, dispatcher.dispatch("ui/focused/cache", JSONObject()))
        verify(exactly = 1) { apiHandler.findNodes(params) }
        verify(exactly = 1) { apiHandler.cacheFindNodes(params) }
        verify(exactly = 1) { apiHandler.getFocusedNode() }
        verify(exactly = 1) { apiHandler.cacheFocusedNode() }
    }

    @Test
    fun dispatchOverlayAutoOffsetRoutesToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val status = ApiResponse.RawObject(JSONObject().put("enabled", true))
        every { apiHandler.getOverlayAutoOffsetStatus() } returns status
        every { apiHandler.setOverlayAutoOffsetEnabled(false) } returns status

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(status, dispatcher.dispatch("overlay/auto-offset/status", JSONObject()))
        assertEquals(
            status,
            dispatcher.dispatch("overlay/auto-offset/set", JSONObject().put("enabled", false)),
        )
        verify(exactly = 1) { apiHandler.getOverlayAutoOffsetStatus() }
        verify(exactly = 1) { apiHandler.setOverlayAutoOffsetEnabled(false) }
    }

    @Test
    fun dispatchDeviceAndRemoteConfigRoutesToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val identity = ApiResponse.RawObject(JSONObject().put("device_id", "device-1"))
        val config = ApiResponse.RawObject(JSONObject().put("socket_server_enabled", true))
        every { apiHandler.getDeviceIdentity() } returns identity
        every { apiHandler.getRemoteConfiguration(true) } returns config

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(identity, dispatcher.dispatch("device/identity", JSONObject()))
        assertEquals(
            config,
            dispatcher.dispatch("config/remote", JSONObject().put("showToken", true)),
        )
        verify(exactly = 1) { apiHandler.getDeviceIdentity() }
        verify(exactly = 1) { apiHandler.getRemoteConfiguration(true) }
    }

    @Test
    fun dispatchTermuxApiCompatRoutesToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val params = JSONObject().put("limit", 5)
        val response = ApiResponse.RawObject(JSONObject().put("path", "/sdcard/sms.json"))
        every { apiHandler.callTermuxApiCompat("sms-list", params) } returns response

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(response, dispatcher.dispatch("termux-api/sms-list", params))
        verify(exactly = 1) { apiHandler.callTermuxApiCompat("sms-list", params) }
    }

    @Test
    fun dispatchAppRoutingRoutesToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val response = ApiResponse.RawObject(JSONObject().put("started", true))
        val cached = ApiResponse.RawObject(JSONObject().put("path", "/sdcard/app-info.json"))
        val intentParams = JSONObject()
            .put("action", "android.intent.action.VIEW")
            .put("data", "https://example.com")
        every { apiHandler.getAppInfo("com.example") } returns response
        every { apiHandler.cacheAppInfo("com.example") } returns cached
        every { apiHandler.openUrl("https://example.com", null) } returns response
        every { apiHandler.openAppSettings("com.example", "permissions") } returns response
        every { apiHandler.startIntent(intentParams) } returns response
        every { apiHandler.requestUninstallApp("com.example") } returns response

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(response, dispatcher.dispatch("app/info", JSONObject().put("package", "com.example")))
        assertEquals(cached, dispatcher.dispatch("app/info/cache", JSONObject().put("package", "com.example")))
        assertEquals(response, dispatcher.dispatch("app/open-url", JSONObject().put("url", "https://example.com")))
        assertEquals(
            response,
            dispatcher.dispatch(
                "app/settings",
                JSONObject().put("package", "com.example").put("screen", "permissions"),
            ),
        )
        assertEquals(response, dispatcher.dispatch("app/intent", intentParams))
        assertEquals(response, dispatcher.dispatch("app/uninstall", JSONObject().put("package", "com.example")))
        verify(exactly = 1) { apiHandler.getAppInfo("com.example") }
        verify(exactly = 1) { apiHandler.cacheAppInfo("com.example") }
        verify(exactly = 1) { apiHandler.openUrl("https://example.com", null) }
        verify(exactly = 1) { apiHandler.openAppSettings("com.example", "permissions") }
        verify(exactly = 1) { apiHandler.startIntent(intentParams) }
        verify(exactly = 1) { apiHandler.requestUninstallApp("com.example") }
    }

    @Test
    fun dispatchScreenRoutesToApiHandler() {
        val apiHandler = mockk<ApiHandler>()
        val response = ApiResponse.RawObject(JSONObject().put("screen", true))
        every { apiHandler.getScreenStatus() } returns response
        every { apiHandler.wakeScreen(5000) } returns response
        every { apiHandler.lockScreen() } returns response
        every { apiHandler.getScreenOrientationStatus() } returns response
        every { apiHandler.setScreenOrientation("landscape") } returns response

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(response, dispatcher.dispatch("screen/status", JSONObject()))
        assertEquals(response, dispatcher.dispatch("screen/wake", JSONObject().put("durationMs", 5000)))
        assertEquals(response, dispatcher.dispatch("screen/lock", JSONObject()))
        assertEquals(response, dispatcher.dispatch("screen/orientation/status", JSONObject()))
        assertEquals(
            response,
            dispatcher.dispatch("screen/orientation/set", JSONObject().put("mode", "landscape")),
        )
        verify(exactly = 1) { apiHandler.getScreenStatus() }
        verify(exactly = 1) { apiHandler.wakeScreen(5000) }
        verify(exactly = 1) { apiHandler.lockScreen() }
        verify(exactly = 1) { apiHandler.getScreenOrientationStatus() }
        verify(exactly = 1) { apiHandler.setScreenOrientation("landscape") }
    }
}
