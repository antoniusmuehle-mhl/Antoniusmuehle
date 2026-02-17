package com.example.antoniusmuehle

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import java.util.Locale

class SplitBillActivity : AppCompatActivity() {

    private lateinit var db: DatabaseReference
    private lateinit var roomName: String
    private lateinit var tableId: String

    private lateinit var orderItemsRef: DatabaseReference

    private lateinit var splitList: ListView
    private lateinit var totalSelectedTxt: TextView
    private lateinit var payPartBtn: Button
    private lateinit var closeSplitBtn: Button

    private val items = mutableListOf<SplitUiItem>()
    private lateinit var adapter: SplitBillAdapter

    data class SplitUiItem(
        val key: String,
        val name: String,
        val size: String,
        val price: Double,
        val totalQty: Int,
        var selected: Boolean = false,
        var payQty: Int = 0
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.split_bill_activity)
        KioskMode.enable(this)

        roomName = intent.getStringExtra("ROOM_NAME") ?: "Unbekannt"
        tableId = intent.getStringExtra("TABLE_ID") ?: "?"

        db = FirebaseDatabase.getInstance().reference
        orderItemsRef = db.child("orders").child(roomName).child(tableId).child("current").child("items")

        splitList = findViewById(R.id.splitList)
        totalSelectedTxt = findViewById(R.id.totalSelectedTxt)
        payPartBtn = findViewById(R.id.payPartBtn)
        closeSplitBtn = findViewById(R.id.closeSplitBtn)

        adapter = SplitBillAdapter(
            context = this,
            items = items,
            onChanged = { updateTotal() }
        )
        splitList.adapter = adapter

        closeSplitBtn.setOnClickListener { finish() }
        payPartBtn.setOnClickListener { confirmAndPayPartial() }

        observeOrderItems()
    }

    override fun onResume() {
        super.onResume()
        KioskMode.enable(this)
    }

    private fun observeOrderItems() {
        orderItemsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                items.clear()

                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    val qty = child.child("qty").getValue(Int::class.java) ?: 0
                    if (qty <= 0) continue

                    val name = child.child("name").getValue(String::class.java) ?: key
                    val size = child.child("size").getValue(String::class.java) ?: ""
                    val price = child.child("price").getValue(Double::class.java) ?: 0.0

                    items.add(
                        SplitUiItem(
                            key = key,
                            name = name,
                            size = size,
                            price = price,
                            totalQty = qty,
                            selected = false,
                            payQty = 0
                        )
                    )
                }

                adapter.notifyDataSetChanged()
                updateTotal()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@SplitBillActivity, "Fehler: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun updateTotal() {
        var sum = 0.0
        for (it in items) {
            if (it.selected && it.payQty > 0) {
                sum += it.price * it.payQty
            }
        }
        totalSelectedTxt.text = "Teilbetrag: ${String.format(Locale.GERMANY, "%.2f", sum)} €"
    }

    private fun confirmAndPayPartial() {
        val selected = items.filter { it.selected && it.payQty > 0 }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Bitte Positionen markieren und Menge wählen.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Teilzahlung")
            .setMessage("Ausgewählte Positionen bezahlen und aus der Bestellung entfernen?")
            .setPositiveButton("JA") { _, _ -> applyPartialPayment(selected) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun applyPartialPayment(selected: List<SplitUiItem>) {
        val updates = hashMapOf<String, Any>()

        for (it in selected) {
            val remaining = (it.totalQty - it.payQty).coerceAtLeast(0)
            updates["${it.key}/qty"] = remaining
        }

        orderItemsRef.updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Teilzahlung übernommen ✅", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Fehler: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}
