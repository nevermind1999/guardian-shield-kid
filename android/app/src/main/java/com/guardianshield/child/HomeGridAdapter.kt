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
import com.guardianshield.child.util.GuardianPrefs.HomeItem

/**
 * Grade da Home nativa — como AppGridAdapter, mas com um 2º tipo de célula (pasta) e
 * suporte a criar pasta arrastando um app sobre o outro (ver
 * LauncherHomeActivity.setupDragToReorder, que decide reordenar vs. juntar em pasta
 * conforme a sobreposição durante o arrastar). Não reaproveita AppGridAdapter porque a
 * Gaveta (o outro lugar que usa ele) não tem — nem precisa ter — conceito de pasta.
 */
class HomeGridAdapter(
    private var items: List<HomeItem>,
    private var appsByPackage: Map<String, AppEntry>,
    private val onLaunchApp: (AppEntry) -> Unit,
    private val onOpenFolder: (HomeItem.FolderItem) -> Unit,
    private val onLongPressApp: (AppEntry, RecyclerView.ViewHolder) -> Unit,
    private val onLongPressFolder: (HomeItem.FolderItem, RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var blockedPackages: Set<String> = emptySet()
    private var isPaused: Boolean = false
    private var alwaysAvailablePackages: Set<String> = emptySet()

    // Posição que o item arrastado está pairando forte o suficiente pra virar pasta se
    // solto agora (ver LauncherHomeActivity) — só destaque visual, não mexe na lista.
    private var hoverTargetPosition: Int? = null

    fun updateItems(newItems: List<HomeItem>, newAppsByPackage: Map<String, AppEntry>) {
        // Sem mudança real (o caso comum a cada sincronização), não redesenha a grade.
        if (newItems == items && newAppsByPackage == appsByPackage) return
        items = newItems
        appsByPackage = newAppsByPackage
        notifyDataSetChanged()
    }

    fun updateBlockedState(blocked: Set<String>, paused: Boolean, alwaysAvailable: Set<String> = emptySet()) {
        if (blocked == blockedPackages && paused == isPaused && alwaysAvailable == alwaysAvailablePackages) return
        blockedPackages = blocked
        isPaused = paused
        alwaysAvailablePackages = alwaysAvailable
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): HomeItem? = items.getOrNull(position)

    fun currentItems(): List<HomeItem> = items

    fun setHoverTarget(position: Int?) {
        val old = hoverTargetPosition
        hoverTargetPosition = position
        if (old != null && old != position) notifyItemChanged(old)
        if (position != null) notifyItemChanged(position)
    }

    /** Reordena em memória — chamado pelo ItemTouchHelper enquanto arrasta sem
     * sobreposição forte o bastante pra virar pasta. */
    fun moveItem(fromPosition: Int, toPosition: Int) {
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(fromPosition)
        mutable.add(toPosition, moved)
        items = mutable
        notifyItemMoved(fromPosition, toPosition)
    }

    /**
     * Solto um item em cima do outro: App+App vira pasta nova; App+Pasta ou Pasta+App
     * entra na pasta existente. Pasta+Pasta não é permitido aqui (ver
     * LauncherHomeActivity, que já bloqueia esse caso antes de chamar isto).
     */
    fun mergeIntoFolder(draggedPosition: Int, targetPosition: Int) {
        val mutable = items.toMutableList()
        val dragged = mutable.getOrNull(draggedPosition) ?: return
        val target = mutable.getOrNull(targetPosition) ?: return

        val merged: HomeItem = when {
            target is HomeItem.FolderItem && dragged is HomeItem.AppItem ->
                HomeItem.FolderItem(target.id, target.name, target.packages + dragged.packageName)
            dragged is HomeItem.FolderItem && target is HomeItem.AppItem ->
                HomeItem.FolderItem(dragged.id, dragged.name, dragged.packages + target.packageName)
            dragged is HomeItem.AppItem && target is HomeItem.AppItem ->
                HomeItem.FolderItem("folder-" + System.currentTimeMillis(), "Pasta", listOf(target.packageName, dragged.packageName))
            else -> return // Pasta+Pasta — não deveria chegar aqui, ver comentário acima
        }

        // Remove os dois originais (do maior índice pro menor, pra não bagunçar o outro
        // índice) e insere a pasta resultante na posição do alvo.
        val firstToRemove = maxOf(draggedPosition, targetPosition)
        val secondToRemove = minOf(draggedPosition, targetPosition)
        mutable.removeAt(firstToRemove)
        mutable.removeAt(secondToRemove)
        mutable.add(secondToRemove, merged)

        items = mutable
        notifyDataSetChanged()
    }

    /** Atualiza uma pasta existente (renomear, ou depois de remover 1 app de dentro dela
     * pelo popup — ver LauncherHomeActivity.showFolderContents). Se a pasta ficou com só
     * 1 app, ela se desfaz sozinha (a "pasta" de 1 item não faz sentido). */
    fun replaceFolder(folderId: String, newName: String? = null, newPackages: List<String>? = null) {
        val mutable = items.toMutableList()
        val index = mutable.indexOfFirst { it is HomeItem.FolderItem && it.id == folderId }
        if (index == -1) return
        val current = mutable[index] as HomeItem.FolderItem
        val packages = newPackages ?: current.packages
        mutable[index] = if (packages.size <= 1) {
            // Desfaz sozinha: sobra 1 app solto (ou nada, se a pasta esvaziou de vez).
            if (packages.isEmpty()) {
                mutable.removeAt(index)
                items = mutable
                notifyDataSetChanged()
                return
            }
            HomeItem.AppItem(packages.first())
        } else {
            HomeItem.FolderItem(current.id, newName ?: current.name, packages)
        }
        items = mutable
        notifyDataSetChanged()
    }

    /** Desfaz a pasta inteira: cada app dentro volta a ser um item solto, na posição
     * onde a pasta estava. */
    fun dissolveFolder(folderId: String) {
        val mutable = items.toMutableList()
        val index = mutable.indexOfFirst { it is HomeItem.FolderItem && it.id == folderId }
        if (index == -1) return
        val folder = mutable[index] as HomeItem.FolderItem
        mutable.removeAt(index)
        folder.packages.forEachIndexed { i, pkg -> mutable.add(index + i, HomeItem.AppItem(pkg)) }
        items = mutable
        notifyDataSetChanged()
    }

    private fun isEffectivelyBlocked(pkg: String): Boolean =
        (isPaused || pkg in blockedPackages) && pkg !in alwaysAvailablePackages

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeItem.AppItem -> TYPE_APP
        is HomeItem.FolderItem -> TYPE_FOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_APP) {
            AppViewHolder(inflater.inflate(R.layout.item_app_icon, parent, false))
        } else {
            FolderViewHolder(inflater.inflate(R.layout.item_folder_icon, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val hovered = hoverTargetPosition == position
        when (val item = items[position]) {
            is HomeItem.AppItem -> {
                val app = appsByPackage[item.packageName] ?: return
                (holder as AppViewHolder).bind(
                    app, isEffectivelyBlocked(app.packageName), app.packageName in alwaysAvailablePackages,
                    hovered, onLaunchApp, onLongPressApp
                )
            }
            is HomeItem.FolderItem -> {
                val apps = item.packages.mapNotNull { appsByPackage[it] }
                (holder as FolderViewHolder).bind(item, apps, hovered, onOpenFolder) { folder -> onLongPressFolder(folder, holder) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconFrame: FrameLayout = itemView.findViewById(R.id.iconFrame)
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
        private val alwaysAvailableBadge: ImageView = itemView.findViewById(R.id.alwaysAvailableBadge)
        private val labelText: TextView = itemView.findViewById(R.id.labelText)

        fun bind(
            app: AppEntry,
            blocked: Boolean,
            alwaysAvailable: Boolean,
            hovered: Boolean,
            onLaunch: (AppEntry) -> Unit,
            onLongPress: (AppEntry, RecyclerView.ViewHolder) -> Unit
        ) {
            iconImage.setImageDrawable(app.icon)
            labelText.text = app.label
            lockIcon.visibility = if (blocked) View.VISIBLE else View.GONE
            alwaysAvailableBadge.visibility = if (alwaysAvailable) View.VISIBLE else View.GONE
            iconFrame.alpha = if (blocked) 0.35f else 1f
            iconFrame.scaleX = if (hovered) 1.12f else 1f
            iconFrame.scaleY = if (hovered) 1.12f else 1f

            if (blocked) {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                iconImage.colorFilter = ColorMatrixColorFilter(matrix)
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
                    it.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                    onLaunch(app)
                }
            }

            val longClickListener = View.OnLongClickListener { onLongPress(app, this); true }
            iconFrame.setOnLongClickListener(longClickListener)
            itemView.setOnLongClickListener(longClickListener)
        }
    }

    class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val folderFrame: FrameLayout = itemView.findViewById(R.id.folderFrame)
        private val previews = listOf<ImageView>(
            itemView.findViewById(R.id.folderPreview1),
            itemView.findViewById(R.id.folderPreview2),
            itemView.findViewById(R.id.folderPreview3),
            itemView.findViewById(R.id.folderPreview4)
        )
        private val labelText: TextView = itemView.findViewById(R.id.folderLabelText)

        fun bind(
            folder: HomeItem.FolderItem,
            apps: List<AppEntry>,
            hovered: Boolean,
            onOpen: (HomeItem.FolderItem) -> Unit,
            onLongPress: (HomeItem.FolderItem) -> Unit
        ) {
            labelText.text = folder.name
            folderFrame.scaleX = if (hovered) 1.12f else 1f
            folderFrame.scaleY = if (hovered) 1.12f else 1f
            previews.forEachIndexed { i, imageView ->
                val app = apps.getOrNull(i)
                if (app != null) {
                    imageView.setImageDrawable(app.icon)
                    imageView.visibility = View.VISIBLE
                } else {
                    imageView.visibility = View.INVISIBLE
                }
            }
            folderFrame.setOnClickListener { onOpen(folder) }
            val longClickListener = View.OnLongClickListener { onLongPress(folder); true }
            folderFrame.setOnLongClickListener(longClickListener)
            itemView.setOnLongClickListener(longClickListener)
        }
    }

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_FOLDER = 1
    }
}
