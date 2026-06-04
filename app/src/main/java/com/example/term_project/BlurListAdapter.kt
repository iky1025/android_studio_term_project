package com.example.term_project

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

data class BlurItem(
    val id: Int,
    val label: String,
    val viewRef: View,
    var isChecked: Boolean = true
)

class BlurListAdapter(private val items: List<BlurItem>) :
    RecyclerView.Adapter<BlurListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        val switchBlur: SwitchCompat = view.findViewById(R.id.switchBlur)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blur_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvLabel.text = item.label
        holder.switchBlur.setOnCheckedChangeListener(null)
        holder.switchBlur.isChecked = item.isChecked
        item.viewRef.visibility = if (item.isChecked) View.VISIBLE else View.GONE
        holder.switchBlur.setOnCheckedChangeListener { _, isChecked ->
            item.isChecked = isChecked
            item.viewRef.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    override fun getItemCount(): Int = items.size
}
