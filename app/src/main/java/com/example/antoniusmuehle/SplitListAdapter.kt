package com.example.antoniusmuehle

import android.content.Context
import android.widget.BaseAdapter

/**
 * Kompatibilitäts-Wrapper:
 * Falls irgendwo im Projekt noch SplitListAdapter verwendet wird,
 * liefert er einfach den neuen SplitBillAdapter zurück (keine Vererbung -> kein "final" Problem).
 */
class SplitListAdapter(
    context: Context,
    items: MutableList<SplitBillActivity.SplitUiItem>,
    onChanged: () -> Unit
) : BaseAdapter() {

    private val delegate = SplitBillAdapter(context, items, onChanged)

    override fun getCount(): Int = delegate.count
    override fun getItem(position: Int): Any = delegate.getItem(position)
    override fun getItemId(position: Int): Long = delegate.getItemId(position)
    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        return delegate.getView(position, convertView, parent)
    }
}
