package com.guardianshield.child

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.model.AppEntry

/**
 * Grade de apps da Gaveta nativa. Apps bloqueados (Pausa Geral ou bloqueio individual)
 * ficam em escala de cinza, apagados e SEM click listener — o toque não faz nada.
 */
class AppGridAdapter(
    private val allApps: List<AppEntry>,
    private val onLaunch: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {

    private var visibleApps: List<AppEntry> = allApps
    private var blockedPackages: Set<String> = emptySet()
    private var isPaused: Boolean = false

    fun filter(query: String) {
        val normalized = query.trim().lowercase()
        visibleApps = if (normalized.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase().contains(normalized) }
        }
        notifyDataSetChanged()
    }

    fun updateBlockedState(blocked: Set<String>, paused: Boolean) {
        blockedPackages = blocked
        isPaused = paused
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_icon, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = visibleApps[position]
        val blocked = isPaused || app.packageName in blockedPackages
        holder.bind(app, blocked, onLaunch)
    }

    override fun getItemCount(): Int = visibleApps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconFrame: FrameLayout = itemView.findViewById(R.id.iconFrame)
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
        private val labelText: TextView = itemView.findViewById(R.id.labelText)

        fun bind(app: AppEntry, blocked: Boolean, onLaunch: (AppEntry) -> Unit) {
            iconImage.setImageDrawable(app.icon)
            labelText.text = app.label
            lockIcon.visibility = if (blocked) View.VISIBLE else View.GONE
            iconFrame.alpha = if (blocked) 0.35f else 1f

            if (blocked) {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                iconImage.colorFilter = ColorMatrixColorFilter(matrix)
                iconFrame.setOnClickListener(null)
                iconFrame.isClickable = false
            } else {
                iconImage.colorFilter = null
                iconFrame.isClickable = true
                iconFrame.setOnClickListener { onLaunch(app) }
            }
        }
    }
}
