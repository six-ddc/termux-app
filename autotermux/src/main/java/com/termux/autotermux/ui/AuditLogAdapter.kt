package com.termux.autotermux.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.termux.autotermux.R
import com.termux.autotermux.audit.AuditEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuditLogAdapter : RecyclerView.Adapter<AuditLogAdapter.ViewHolder>() {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val expandedKeys = mutableSetOf<String>()
    private var entries: List<AuditEntry> = emptyList()

    fun submit(nextEntries: List<AuditEntry>) {
        entries = nextEntries
        expandedKeys.retainAll(entries.map(::entryKey).toSet())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.audit_log_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timeView: TextView = itemView.findViewById(R.id.audit_time)
        private val kindView: TextView = itemView.findViewById(R.id.audit_kind)
        private val summaryView: TextView = itemView.findViewById(R.id.audit_summary)
        private val detailsView: TextView = itemView.findViewById(R.id.audit_details)

        fun bind(entry: AuditEntry) {
            val key = entryKey(entry)
            val chipColor = colorForKind(entry.kind)
            timeView.text = timeFormat.format(Date(entry.timestampMs))
            kindView.text = labelForKind(entry.kind)
            kindView.setTextColor(chipColor)
            kindView.background = chipBackground(chipColor)
            summaryView.text = entry.summary
            detailsView.text = entry.details.orEmpty()
            detailsView.visibility =
                if (!entry.details.isNullOrBlank() && expandedKeys.contains(key)) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                if (entry.details.isNullOrBlank()) return@setOnClickListener
                if (!expandedKeys.add(key)) expandedKeys.remove(key)
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
            }
        }

        private fun colorForKind(kind: AuditEntry.Kind): Int {
            val resId = when (kind) {
                AuditEntry.Kind.ARMED_CHANGED -> R.color.autotermux_warning
                AuditEntry.Kind.SERVICE_CONNECTED,
                AuditEntry.Kind.SERVICE_DISCONNECTED,
                -> R.color.text_gray_light
                AuditEntry.Kind.TRIGGER_FIRED -> R.color.autotermux_primary_light
                AuditEntry.Kind.TRIGGER_BLOCKED_DISARMED,
                AuditEntry.Kind.BRIDGE_BLOCKED_DISARMED,
                -> R.color.autotermux_warning
                AuditEntry.Kind.TRIGGER_FAILED -> R.color.autotermux_error
                AuditEntry.Kind.BRIDGE_ACTION -> R.color.autotermux_primary
                AuditEntry.Kind.AUTO_ACCEPT_FIRED -> R.color.autotermux_warning
            }
            return ContextCompat.getColor(itemView.context, resId)
        }

        private fun chipBackground(strokeColor: Int): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = itemView.resources.displayMetrics.density * 4
                setColor(Color.TRANSPARENT)
                setStroke(1, strokeColor)
            }
    }

    private fun labelForKind(kind: AuditEntry.Kind): String =
        when (kind) {
            AuditEntry.Kind.ARMED_CHANGED -> "ARMED"
            AuditEntry.Kind.SERVICE_CONNECTED,
            AuditEntry.Kind.SERVICE_DISCONNECTED,
            -> "SERVICE"
            AuditEntry.Kind.TRIGGER_FIRED,
            AuditEntry.Kind.TRIGGER_BLOCKED_DISARMED,
            AuditEntry.Kind.TRIGGER_FAILED,
            -> "TRIGGER"
            AuditEntry.Kind.BRIDGE_ACTION,
            AuditEntry.Kind.BRIDGE_BLOCKED_DISARMED,
            -> "BRIDGE"
            AuditEntry.Kind.AUTO_ACCEPT_FIRED -> "ACCEPT"
        }

    private fun entryKey(entry: AuditEntry): String =
        "${entry.timestampMs}:${entry.kind}:${entry.summary}"
}
