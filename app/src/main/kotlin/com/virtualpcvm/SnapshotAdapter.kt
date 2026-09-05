package com.virtualpcvm

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.virtualpcvm.databinding.ItemSnapshotBinding

class SnapshotAdapter(
    private var items: List<VmSnapshot>,
    private val onRevert: (VmSnapshot) -> Unit,
    private val onDelete: (VmSnapshot) -> Unit
) : RecyclerView.Adapter<SnapshotAdapter.VH>() {

    class VH(val b: ItemSnapshotBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSnapshotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val snap = items[position]
        holder.b.apply {
            tvSnapshotName.text = snap.tag
            tvSnapshotInfo.text = "ID: ${snap.id}  •  ${snap.date}  •  ${snap.vmSize}"
            btnRevertSnapshot.setOnClickListener { onRevert(snap) }
            btnDeleteSnapshot.setOnClickListener { onDelete(snap) }
        }
    }

    fun updateData(newItems: List<VmSnapshot>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
