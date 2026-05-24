package com.termux.autotermux.core

import com.termux.autotermux.model.ElementNode
import com.termux.autotermux.model.PhoneState
import com.termux.autotermux.service.ScreenRecorderService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

object JsonBuilders {

    fun screenRecorderStatus(snapshot: ScreenRecorderService.StatusSnapshot): JSONObject {
        return JSONObject().apply {
            put("state", snapshot.state.name)
            put("path", snapshot.path ?: JSONObject.NULL)
            put("durationMs", snapshot.durationMs)
            put("error", snapshot.error ?: JSONObject.NULL)
            put("bitRate", snapshot.bitRate)
            put("frameRate", snapshot.frameRate)
            put("width", snapshot.width)
            put("height", snapshot.height)
        }
    }

    fun screenRecorderStartResult(result: ScreenRecorderService.Result): JSONObject {
        return JSONObject().apply {
            put("ok", result.ok)
            put("state", result.state.name)
            put("path", result.path ?: JSONObject.NULL)
            put("durationMs", result.durationMs)
            put("error", result.error ?: JSONObject.NULL)
        }
    }

    fun screenRecorderStopResult(result: ScreenRecorderService.Result): JSONObject {
        return JSONObject().apply {
            put("ok", result.ok)
            put("state", result.state.name)
            put("path", result.path ?: JSONObject.NULL)
            put("durationMs", result.durationMs)
            put("error", result.error ?: JSONObject.NULL)
        }
    }


    fun elementNodeToJson(element: ElementNode): JSONObject {
        return elementNodeToJson(element, identitySet())
    }

    private fun elementNodeToJson(
        element: ElementNode,
        visited: MutableSet<ElementNode>
    ): JSONObject {
        if (!visited.add(element)) {
            return JSONObject().apply {
                put("index", element.overlayIndex)
                put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
                put("className", element.className)
                put("text", element.text)
                put("contentDescription", element.nodeInfo.contentDescription?.toString() ?: "")
                put("isClickable", element.nodeInfo.isClickable)
                put("isLongClickable", element.nodeInfo.isLongClickable)
                put("isScrollable", element.nodeInfo.isScrollable)
                put("isEditable", element.nodeInfo.isEditable)
                put("isEnabled", element.nodeInfo.isEnabled)
                put("isVisibleToUser", element.nodeInfo.isVisibleToUser)
                put(
                    "bounds",
                    "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}",
                )
                put("children", JSONArray())
            }
        }

        return JSONObject().apply {
            put("index", element.overlayIndex)
            put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
            put("className", element.className)
            put("text", element.text)
            put("contentDescription", element.nodeInfo.contentDescription?.toString() ?: "")
            put("isClickable", element.nodeInfo.isClickable)
            put("isLongClickable", element.nodeInfo.isLongClickable)
            put("isScrollable", element.nodeInfo.isScrollable)
            put("isEditable", element.nodeInfo.isEditable)
            put("isEnabled", element.nodeInfo.isEnabled)
            put("isVisibleToUser", element.nodeInfo.isVisibleToUser)
            put(
                "bounds",
                "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}",
            )

            val childrenArray = JSONArray()
            element.children.forEach { child ->
                if (!visited.contains(child)) {
                    childrenArray.put(elementNodeToJson(child, visited))
                }
            }
            put("children", childrenArray)
            visited.remove(element)
        }
    }

    fun phoneStateToJson(state: PhoneState): JSONObject {
        return JSONObject().apply {
            put("currentApp", state.appName)
            put("packageName", state.packageName)
            put("activityName", state.activityName ?: "")
            put("keyboardVisible", state.keyboardVisible)
            put("isEditable", state.isEditable)
            put("focusedElement", JSONObject().apply {
                put("text", state.focusedElement?.text)
                put("className", state.focusedElement?.className)
                put("resourceId", state.focusedElement?.viewIdResourceName ?: "")
            })
        }
    }

    private fun identitySet(): MutableSet<ElementNode> {
        return Collections.newSetFromMap(IdentityHashMap())
    }
}
