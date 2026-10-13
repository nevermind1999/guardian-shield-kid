package com.guardianshield.child

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.model.AppEntry
import com.guardianshield.child.util.GuardianPrefs

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
    private val onLongPress: ((AppEntry, RecyclerView.ViewHolder) -> Unit)? = null,
    // true só na Gaveta: manda os apps bloqueados pro final da lista, pra ficar mais fácil
    // escanear "o que dá pra abrir" visualmente. Na Home fica false — lá a ordem é a que o
    // usuário escolheu arrastando manualmente, reordenar por bloqueio quebraria isso.
    private val sortBlockedLast: Boolean = false
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {

    private var allApps: List<AppEntry> = allApps
    private var visibleApps: List<AppEntry> = allApps
    private var blockedPackages: Set<String> = emptySet()
    private var alwaysAvailablePackages: Set<String> = emptySet()
    private var isPaused: Boolean = false
    private var query: String = ""
    private var categoryFilter: String? = null // null = todas as categorias

    fun filter(text: String) {
        query = text.trim().lowercase()
        applyFilter()
    }

    /** null mostra todas as categorias — ver os chips em LauncherDrawerActivity. */
    fun filterByCategory(category: String?) {
        categoryFilter = category
        applyFilter()
    }

    /** Troca a lista completa de apps exibidos (ex: Home atualizando os apps fixados). */
    fun updatePinnedApps(apps: List<AppEntry>) {
        allApps = apps
        applyFilter()
    }

    private fun applyFilter() {
        var matched = if (query.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(query) }
        categoryFilter?.let { category -> matched = matched.filter { it.category == category } }
        // sortedBy é estável: dentro de cada grupo (bloqueado/não bloqueado) a ordem
        // alfabética original de allApps é preservada.
        visibleApps = if (sortBlockedLast) matched.sortedBy { if (isEffectivelyBlocked(it.packageName)) 1 else 0 } else matched
        notifyDataSetChanged()
    }

    private fun isEffectivelyBlocked(pkg: String): Boolean =
        (isPaused || pkg in blockedPackages) && pkg !in alwaysAvailablePackages

    fun updateBlockedState(blocked: Set<String>, paused: Boolean, alwaysAvailable: Set<String> = emptySet()) {
        if (blocked == blockedPackages && paused == isPaused && alwaysAvailable == alwaysAvailablePackages) return
        blockedPackages = blocked
        isPaused = paused
        alwaysAvailablePackages = alwaysAvailable
        // O bloqueio pode ter mudado quais apps contam como bloqueados — refaz a ordenação
        // (só tem efeito de verdade quando sortBlockedLast=true, ver applyFilter).
        applyFilter()
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
        val blocked = isEffectivelyBlocked(app.packageName)
        val alwaysAvailable = app.packageName in alwaysAvailablePackages
        holder.bind(app, blocked, alwaysAvailable, onLaunch, onLongPress)
    }

    override fun getItemCount(): Int = visibleApps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconFrame: FrameLayout = itemView.findViewById(R.id.iconFrame)
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
        private val alwaysAvailableBadge: ImageView = itemView.findViewById(R.id.alwaysAvailableBadge)
        private val labelText: TextView = itemView.findViewById(R.id.labelText)

        fun bind(
            app: AppEntry,
            blocked: Boolean,
            alwaysAvailable: Boolean,
            onLaunch: (AppEntry) -> Unit,
            onLongPress: ((AppEntry, RecyclerView.ViewHolder) -> Unit)?
        ) {
            iconImage.setImageDrawable(app.icon)
            labelText.text = app.label
            lockIcon.visibility = if (blocked) View.VISIBLE else View.GONE
            // Nunca aparece junto do lockIcon: um app "sempre disponível" nunca chega
            // aqui com blocked=true (ver isEffectivelyBlocked).
            alwaysAvailableBadge.visibility = if (alwaysAvailable) View.VISIBLE else View.GONE
            iconFrame.alpha = if (blocked) 0.35f else 1f

            if (blocked) {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                iconImage.colorFilter = ColorMatrixColorFilter(matrix)
                // Antes o toque não fazia NADA num ícone bloqueado (listener removido) —
                // parecia quebrado, sem explicar por que não abre. Agora explica o motivo
                // de verdade (Pausa Geral, tarefa pendente específica, horário, tempo
                // esgotado) quando é um bloqueio geral; cai num texto genérico só quando é
                // esse app específico que foi bloqueado (sem motivo geral ativo).
                iconFrame.isClickable = true
                iconFrame.setOnClickListener {
                    val reason = GuardianPrefs.currentGlobalBlockLabel(it.context)
                        ?: "${app.label} foi bloqueado pelos seus pais"
                    Toast.makeText(it.context, "🔒 $reason", Toast.LENGTH_SHORT).show()
                }
            } else {
                iconImage.colorFilter = null
                iconFrame.isClickable = true
                iconFrame.setOnClickListener {
                    // Feedback tátil visual (aperta e volta) antes de abrir o app — a
                    // troca de tela normalmente já cobre o fim da animação.
                    it.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                    onLaunch(app)
                }
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
