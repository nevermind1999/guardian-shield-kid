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
 * Grade de apps reutilizada pela Gaveta (todos os apps, com busca) e pela Home nativa
 * (só os fixados, reordenável por arrastar). Apps bloqueados (Pausa Geral ou bloqueio
 * individual) ficam em escala de cinza, apagados e SEM click listener — o toque não faz
 * nada. Toque e segure aciona [onLongPress] com o próprio ViewHolder (pra quem chamou
 * poder abrir um menu ancorado nele ou iniciar um arrastar via ItemTouchHelper).
 */
class AppGridAdapter(
    allApps: List<AppEntry>,
    private val onLaunch: (AppEntry) -> Unit,
    private val onLongPress: ((AppEntry, RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {

    private var allApps: List<AppEntry> = allApps
    private var visibleApps: List<AppEntry> = allApps
    private var blockedPackages: Set<String> = emptySet()
    private var isPaused: Boolean = false
    private var query: String = ""

    fun filter(text: String) {
        query = text.trim().lowercase()
        applyFilter()
    }

    /** Troca a lista completa de apps exibidos (ex: Home atualizando os apps fixados). */
    fun updatePinnedApps(apps: List<AppEntry>) {
        allApps = apps
        applyFilter()
    }

    private fun applyFilter() {
        visibleApps = if (query.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(query) }
        notifyDataSetChanged()
    }

    fun updateBlockedState(blocked: Set<String>, paused: Boolean) {
        blockedPackages = blocked
        isPaused = paused
        notifyDataSetChanged()
    }

    /** Reordena em memória (usado pelo ItemTouchHelper durante o arrastar). Não filtra. */
    fun moveItem(fromPosition: Int, toPosition: Int) {
        val mutable = visibleApps.toMutableList()
        val moved = mutable.removeAt(fromPosition)
        mutable.add(toPosition, moved)
        visibleApps = mutable
        allApps = mutable
        notifyItemMoved(fromPosition, toPosition)
    }

    /** Ordem atual dos pacotes — chamado ao soltar o arrastar, pra persistir. */
    fun currentPackageOrder(): List<String> = visibleApps.map { it.packageName }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_icon, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = visibleApps[position]
        val blocked = isPaused || app.packageName in blockedPackages
        holder.bind(app, blocked, onLaunch, onLongPress)
    }

    override fun getItemCount(): Int = visibleApps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconFrame: FrameLayout = itemView.findViewById(R.id.iconFrame)
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
        private val labelText: TextView = itemView.findViewById(R.id.labelText)

        fun bind(
            app: AppEntry,
            blocked: Boolean,
            onLaunch: (AppEntry) -> Unit,
            onLongPress: ((AppEntry, RecyclerView.ViewHolder) -> Unit)?
        ) {
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

            // Toque e segure funciona mesmo com o app bloqueado (não depende do clique normal).
            // Precisa ir no iconFrame (não só no itemView) porque é ele quem realmente
            // captura o toque na área do ícone — o itemView sozinho só veria toques na
            // margem/rótulo, que o usuário raramente toca.
            val longClickListener = if (onLongPress != null) {
                View.OnLongClickListener { onLongPress(app, this); true }
            } else {
                null
            }
            iconFrame.setOnLongClickListener(longClickListener)
            itemView.setOnLongClickListener(longClickListener)
        }
    }
}
