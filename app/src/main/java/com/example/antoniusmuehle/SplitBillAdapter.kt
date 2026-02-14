package com.example.antoniusmuehle

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class SplitBillAdapter(
    private val context: Context,
    private val items: MutableList<SplitBillActivity.SplitUiItem>,
    private val onChanged: () -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.row_split_item, parent, false)

        val check = v.findViewById<CheckBox>(R.id.splitCheck)
        val name = v.findViewById<TextView>(R.id.splitItemName)
        val price = v.findViewById<TextView>(R.id.splitItemPrice)
        val qtyInfo = v.findViewById<TextView>(R.id.splitQtyInfo)
        val minus = v.findViewById<Button>(R.id.splitMinus)
        val plus = v.findViewById<Button>(R.id.splitPlus)
        val payQtyTxt = v.findViewById<TextView>(R.id.splitPayQty)

        val item = items[position]

        val sizePart = if (item.size.isNotBlank()) " (${item.size})" else ""
        name.text = item.name + sizePart
        price.text = String.format(Locale.GERMANY, "%.2f €", item.price)

        check.setOnCheckedChangeListener(null)
        check.isChecked = item.selected

        // Wenn neu selektiert: default payQty = 1
        check.setOnCheckedChangeListener { _, isChecked ->
            item.selected = isChecked
            if (isChecked && item.payQty <= 0) item.payQty = 1
            if (!isChecked) item.payQty = 0
            notifyDataSetChanged()
            onChanged()
        }

        val payQty = if (item.selected) item.payQty else 0
        payQtyTxt.text = payQty.toString()
        qtyInfo.text = "Gesamt: ${item.totalQty}  |  Bezahlen: $payQty"

        // Buttons nur aktiv wenn ausgewählt
        minus.isEnabled = item.selected
        plus.isEnabled = item.selected

        minus.setOnClickListener {
            if (!item.selected) return@setOnClickListener
            item.payQty = max(0, item.payQty - 1)
            if (item.payQty == 0) item.selected = false
            notifyDataSetChanged()
            onChanged()
        }

        plus.setOnClickListener {
            if (!item.selected) return@setOnClickListener
            item.payQty = min(item.totalQty, item.payQty + 1)
            notifyDataSetChanged()
            onChanged()
        }

        // Zeile klick -> toggle
        v.setOnClickListener {
            item.selected = !item.selected
            if (item.selected && item.payQty <= 0) item.payQty = 1
            if (!item.selected) item.payQty = 0
            notifyDataSetChanged()
            onChanged()
        }

        return v
    }
}
