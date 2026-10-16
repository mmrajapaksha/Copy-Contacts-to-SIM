package com.mithun.simcontacts

import android.content.res.TypedArray
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mithun.simcontacts.databinding.ItemContactBinding

class ContactAdapter(
    private val onItemClick: (PhoneContact) -> Unit
) : ListAdapter<PhoneContact, ContactAdapter.ContactViewHolder>(ContactDiff()) {

    private var avatarColors: IntArray = intArrayOf()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val ta: TypedArray = recyclerView.context.resources.obtainTypedArray(R.array.avatar_colors)
        avatarColors = IntArray(ta.length()) { ta.getColor(it, 0) }
        ta.recycle()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
        }

        fun bind(contact: PhoneContact) {
            binding.contactCheck.isChecked = contact.selected
            binding.contactName.text = contact.name
            binding.contactDetail.text = "${contact.number}  ·  ${contact.type}  ·  ${contact.source}"

            // Avatar: colored circle with first initial
            val initial = contact.name.firstOrNull()?.uppercase() ?: "?"
            binding.avatar.text = initial
            val colorIndex = (contact.name.hashCode() and Int.MAX_VALUE) % avatarColors.size.coerceAtLeast(1)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (avatarColors.isNotEmpty()) avatarColors[colorIndex] else 0xFF1565C0.toInt())
            binding.avatar.background = bg
        }
    }

    private class ContactDiff : DiffUtil.ItemCallback<PhoneContact>() {
        override fun areItemsTheSame(old: PhoneContact, new: PhoneContact) = old.id == new.id
        override fun areContentsTheSame(old: PhoneContact, new: PhoneContact) =
            old.name == new.name && old.number == new.number && old.selected == new.selected
    }
}
