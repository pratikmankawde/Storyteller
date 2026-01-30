package com.dramebaz.app.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R

data class ActionCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val onClick: () -> Unit
)

class ActionCardAdapter : ListAdapter<ActionCard, ActionCardAdapter.VH>(Diff) {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.action_icon)
        val title: TextView = itemView.findViewById(R.id.action_title)
        val subtitle: TextView = itemView.findViewById(R.id.action_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_action_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val action = getItem(position)
        holder.icon.setImageResource(action.iconRes)
        holder.title.text = action.title
        holder.subtitle.text = action.subtitle
        holder.itemView.setOnClickListener { action.onClick() }
        
        // Add animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(position * 100L)
            .start()
    }

    object Diff : DiffUtil.ItemCallback<ActionCard>() {
        override fun areItemsTheSame(a: ActionCard, b: ActionCard) = a.id == b.id
        override fun areContentsTheSame(a: ActionCard, b: ActionCard) = a == b
    }
}
