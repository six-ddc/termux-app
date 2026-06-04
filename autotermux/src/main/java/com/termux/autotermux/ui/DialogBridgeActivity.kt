package com.termux.autotermux.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.termux.autotermux.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DialogBridgeActivity : Activity() {
    companion object {
        private const val EXTRA_PARAMS = "params"

        private val lock = Any()
        private var pendingRequest: PendingRequest? = null

        fun request(context: android.content.Context, params: JSONObject, timeoutMs: Long): DialogResult {
            val request = PendingRequest()
            synchronized(lock) {
                if (pendingRequest != null) {
                    return DialogResult(success = false, error = "dialog request already in progress")
                }
                pendingRequest = request
            }

            return try {
                val intent = android.content.Intent(context, DialogBridgeActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_PARAMS, params.toString())
                }
                context.startActivity(intent)
                if (!request.latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)) {
                    synchronized(lock) {
                        if (pendingRequest === request) pendingRequest = null
                    }
                    DialogResult(success = false, error = "dialog timed out")
                } else {
                    request.result ?: DialogResult(success = false, error = "dialog returned no result")
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (pendingRequest === request) pendingRequest = null
                }
                DialogResult(success = false, error = "failed to start dialog: ${e.message}")
            }
        }

        private fun complete(result: DialogResult) {
            synchronized(lock) {
                pendingRequest?.let {
                    it.result = result
                    it.latch.countDown()
                }
                pendingRequest = null
            }
        }
    }

    private lateinit var params: JSONObject
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        params = JSONObject(intent.getStringExtra(EXTRA_PARAMS).orEmpty().ifBlank { "{}" })
        showDialogFor(params.optString("widget", params.optString("input_method", "text")).ifBlank { "text" })
    }

    override fun onDestroy() {
        if (!completed) completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = ""))
        super.onDestroy()
    }

    private fun showDialogFor(widget: String) {
        when (widget.lowercase(Locale.US)) {
            "confirm" -> showConfirm()
            "text" -> showText()
            "checkbox" -> showCheckbox()
            "radio", "sheet", "spinner" -> showSingleChoice()
            "counter" -> showCounter()
            "date" -> showDate()
            "time" -> showTime()
            else -> completeResult(DialogResult(success = false, error = "Unknown dialog widget: $widget"))
        }
    }

    private fun showConfirm() {
        AlertDialog.Builder(this)
            .setTitle(title())
            .setMessage(params.optString("hint", params.optString("input_hint", "Confirm")))
            .setPositiveButton("Yes") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_POSITIVE, text = "yes")) }
            .setNegativeButton("No") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "no")) }
            .setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .show()
    }

    private fun showText() {
        val input = EditText(this)
        styleDialogInput(input)
        input.hint = params.optString("hint", params.optString("input_hint", ""))
        var type = if (params.optBoolean("numeric", false)) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (params.optBoolean("password", false)) {
            type = if (params.optBoolean("numeric", false)) {
                type or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                type or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        if (params.optBoolean("multiple_lines", params.optBoolean("multipleLines", false))) {
            type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            input.minLines = 4
            input.gravity = Gravity.TOP
        }
        input.inputType = type
        val container = paddedContainer()
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title())
            .setView(container)
            .setPositiveButton("OK") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_POSITIVE, text = input.text.toString())) }
            .setNegativeButton("Cancel") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .show()
    }

    private fun showCheckbox() {
        val values = values()
        val checks = values.map {
            CheckBox(this).apply {
                text = it
                textSize = 15f
                typeface = Typeface.MONOSPACE
                setTextColor(color(R.color.text_white))
                buttonTintList = ColorStateList.valueOf(color(R.color.autotermux_primary))
            }
        }
        val layout = paddedContainer()
        checks.forEach { layout.addView(it) }
        AlertDialog.Builder(this)
            .setTitle(title())
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("OK") { _, _ ->
                val selected = checks.mapIndexedNotNull { index, check ->
                    if (check.isChecked) DialogValue(index, check.text.toString()) else null
                }
                completeResult(
                    DialogResult(
                        success = true,
                        code = AlertDialog.BUTTON_POSITIVE,
                        text = selected.joinToString(prefix = "[", postfix = "]") { it.text },
                        values = selected,
                    ),
                )
            }
            .setNegativeButton("Cancel") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .show()
    }

    private fun showSingleChoice() {
        val values = values()
        var selected = -1
        AlertDialog.Builder(this)
            .setTitle(title())
            .setSingleChoiceItems(values.toTypedArray(), -1) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                completeResult(
                    DialogResult(
                        success = true,
                        code = AlertDialog.BUTTON_POSITIVE,
                        text = values.getOrNull(selected).orEmpty(),
                        index = selected,
                    ),
                )
            }
            .setNegativeButton("Cancel") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .show()
    }

    private fun showCounter() {
        val range = range()
        val picker = NumberPicker(this).apply {
            minValue = range.first
            maxValue = range.second
            value = range.third.coerceIn(range.first, range.second)
        }
        val container = paddedContainer()
        container.addView(picker)
        AlertDialog.Builder(this)
            .setTitle(title())
            .setView(container)
            .setPositiveButton("OK") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_POSITIVE, text = picker.value.toString())) }
            .setNegativeButton("Cancel") { _, _ -> completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            .show()
    }

    private fun showDate() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            calendar.set(year, month, day, 0, 0, 0)
            val format = params.optString("date_format", params.optString("dateFormat", ""))
            val text = if (format.isNotBlank()) {
                runCatching { SimpleDateFormat(format, Locale.getDefault()).format(calendar.time) }
                    .getOrElse { calendar.time.toString() }
            } else {
                calendar.time.toString()
            }
            completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_POSITIVE, text = text))
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
            .apply {
                setTitle(title())
                setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            }
            .show()
    }

    private fun showTime() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_POSITIVE, text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)))
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true)
            .apply {
                setTitle(title())
                setOnCancelListener { completeResult(DialogResult(success = true, code = AlertDialog.BUTTON_NEGATIVE, text = "")) }
            }
            .show()
    }

    private fun title(): String = params.optString("title", params.optString("input_title", ""))

    private fun values(): List<String> {
        val raw = params.opt("values") ?: params.opt("input_values")
        return when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> raw.split("(?<!\\\\),".toRegex()).map { it.trim().replace("\\,", ",") }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun range(): Triple<Int, Int, Int> {
        val raw = params.opt("range") ?: params.opt("input_range")
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optInt(it) }
            is String -> raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            else -> emptyList()
        }
        val min = values.getOrNull(0) ?: 0
        val max = values.getOrNull(1) ?: 100
        val start = values.getOrNull(2) ?: ((max - min) / 2)
        return Triple(kotlin.math.min(min, max), kotlin.math.max(min, max), start)
    }

    private fun paddedContainer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }

    private fun styleDialogInput(input: EditText) {
        input.typeface = Typeface.MONOSPACE
        input.textSize = 14f
        input.setTextColor(color(R.color.text_white))
        input.setHintTextColor(color(R.color.text_gray_light))
        input.backgroundTintList = ColorStateList.valueOf(color(R.color.autotermux_primary))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun completeResult(result: DialogResult) {
        if (completed) return
        completed = true
        complete(result)
        finish()
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        var result: DialogResult? = null
    }

    data class DialogResult(
        val success: Boolean,
        val code: Int = 0,
        val text: String = "",
        val index: Int = -1,
        val values: List<DialogValue> = emptyList(),
        val error: String = "",
    )

    data class DialogValue(val index: Int, val text: String)
}
