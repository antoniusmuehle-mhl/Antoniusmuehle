package com.example.antoniusmuehle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MenuRecyclerAdapter(
    private var rows: List<MenuRow>,
    private val onCategoryClick: (MenuRow.CategoryRow) -> Unit,
    private val onSubCategoryClick: (MenuRow.SubCategoryRow) -> Unit,
    private val onItemClick: (MenuItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var expandedCategoryKey: String? = null
    private var expandedSubKey: String? = null

    companion object {
        private const val TYPE_CATEGORY = 0
        private const val TYPE_SUBCATEGORY = 1
        private const val TYPE_ITEM = 2
    }

    fun update(newRows: List<MenuRow>, expandedCat: String?, expandedSub: String?) {
        rows = newRows
        expandedCategoryKey = expandedCat
        expandedSubKey = expandedSub
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is MenuRow.CategoryRow -> TYPE_CATEGORY
            is MenuRow.SubCategoryRow -> TYPE_SUBCATEGORY
            is MenuRow.ItemRow -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            TYPE_CATEGORY -> {
                val v = inflater.inflate(R.layout.row_menu_group, parent, false)
                CategoryVH(v)
            }
            TYPE_SUBCATEGORY -> {
                val v = inflater.inflate(R.layout.row_menu_subgroup, parent, false)
                SubCategoryVH(v)
            }
            else -> {
                val v = inflater.inflate(R.layout.row_menu_item, parent, false)
                ItemVH(v)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MenuRow.CategoryRow -> (holder as CategoryVH).bind(row)
            is MenuRow.SubCategoryRow -> (holder as SubCategoryVH).bind(row)
            is MenuRow.ItemRow -> (holder as ItemVH).bind(row)
        }
    }

    // ================= CATEGORY =================
    inner class CategoryVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.groupTitle)
        private val arrow: TextView = itemView.findViewById(R.id.groupArrow)

        fun bind(row: MenuRow.CategoryRow) {
            title.text = row.title
            val isOpen = row.key == expandedCategoryKey
            arrow.text = if (isOpen) "⌄" else "›"

            itemView.setOnClickListener { onCategoryClick(row) }
        }
    }

    // ================= SUBCATEGORY =================
    inner class SubCategoryVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.subGroupTitle)
        private val arrow: TextView? = itemView.findViewById(R.id.subGroupArrow)

        fun bind(row: MenuRow.SubCategoryRow) {
            title.text = row.title

            val isOpen = row.key == expandedSubKey
            arrow?.text = if (isOpen) "⌄" else "›"

            itemView.setOnClickListener { onSubCategoryClick(row) }
        }
    }

    // ================= ITEM =================
    inner class ItemVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.itemName)
        private val price: TextView = itemView.findViewById(R.id.itemPrice)

        fun bind(row: MenuRow.ItemRow) {
            name.text = row.item.name
            price.text = formatMenuPrice(row.item)

            itemView.setOnClickListener { onItemClick(row.item) }
        }

        private fun formatMenuPrice(item: MenuItem): String {
            // ✅ Immer "ab"
            if (item.sizes.isNotEmpty()) {
                val min = item.sizes.minOf { it.price }
                return "ab " + String.format(Locale.GERMANY, "%.2f €", min)
            }
            return "ab " + String.format(Locale.GERMANY, "%.2f €", item.price)
        }
    }
}
