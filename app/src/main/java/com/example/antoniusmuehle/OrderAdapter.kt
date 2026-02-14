package com.example.antoniusmuehle

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class OrderAdapter(
    private val context: Context,
    private var items: List<OrderItem>,
    private val onPlus: (OrderItem) -> Unit,
    private val onMinus: (OrderItem) -> Unit
) : BaseAdapter() {

    fun update(newItems: List<OrderItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.row_order_item, parent, false)

        val item = items[position]

        val minusBtn = view.findViewById<Button>(R.id.minusBtn)
        val plusBtn = view.findViewById<Button>(R.id.plusBtn)
        val qtyTxt = view.findViewById<TextView>(R.id.qtyTxt)
        val nameTxt = view.findViewById<TextView>(R.id.nameTxt)
        val priceTxt = view.findViewById<TextView>(R.id.priceTxt)

        qtyTxt.text = "${item.qty}x"

        val shownName = if (item.size.isNotBlank()) "${item.name} (${item.size})" else item.name
        nameTxt.text = shownName

        priceTxt.text = String.format(Locale.GERMANY, "%.2f €", item.price)

        minusBtn.setOnClickListener { onMinus(item) }
        plusBtn.setOnClickListener { onPlus(item) }

        return view
    }
}
