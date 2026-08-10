package com.guardianshield.child

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.util.GuardianPrefs.TaskItem

/**
 * Grade horizontal de tarefas diárias na Home nativa (mesmo esquema do AppGridAdapter,
 * mas pra cards de tarefa). Toque abre a câmera — só quando o status permite reenvio
 * ('pending' ou 'rejected'; 'submitted'/'approved' ficam sem clique).
 */
class TaskCardAdapter(
    private val onTap: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskCardAdapter.ViewHolder>() {

    private var tasks: List<TaskItem> = emptyList()

    fun updateTasks(newTasks: List<TaskItem>) {
        tasks = newTasks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tasks[position], onTap)
    }

    override fun getItemCount(): Int = tasks.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconText: TextView = itemView.findViewById(R.id.taskIcon)
        private val titleText: TextView = itemView.findViewById(R.id.taskTitle)
        private val rewardText: TextView = itemView.findViewById(R.id.taskReward)
        private val statusText: TextView = itemView.findViewById(R.id.taskStatus)

        fun bind(task: TaskItem, onTap: (TaskItem) -> Unit) {
            iconText.text = task.icon
            titleText.text = task.title
            rewardText.text = "+${task.rewardMinutes} min"

            val (label, tappable) = when (task.status) {
                "submitted" -> "⏳ Aguardando aprovação" to false
                "approved" -> "✅ Aprovada" to false
                "rejected" -> (task.rejectedReason?.let { "❌ $it" } ?: "❌ Recusada, toque p/ reenviar") to true
                else -> "📸 Toque para enviar foto" to true
            }
            statusText.text = label
            itemView.alpha = if (task.status == "approved") 0.7f else 1f

            if (tappable) {
                itemView.isClickable = true
                itemView.setOnClickListener { onTap(task) }
            } else {
                itemView.isClickable = false
                itemView.setOnClickListener(null)
            }
        }
    }
}
