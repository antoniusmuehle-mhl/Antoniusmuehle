package com.example.antoniusmuehle

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*

class RoomSelectionActivity : AppCompatActivity() {

    private lateinit var rootRef: DatabaseReference
    private lateinit var roomsRef: DatabaseReference

    private lateinit var infoContainer: LinearLayout
    private lateinit var roomLayout: LinearLayout

    private val DB_URL =
        "https://antoniusmuehle-39749-default-rtdb.europe-west1.firebasedatabase.app"

    // ✅ gewünschte Reihenfolge
    private val roomOrder = listOf(
        "Restaurant",
        "Gewölbe",
        "Scheune EG",
        "Scheune UG",
        "Terrasse"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room_selection)
        KioskMode.enable(this)

        infoContainer = findViewById(R.id.infoContainer)
        roomLayout = findViewById(R.id.roomLayout)

        rootRef = FirebaseDatabase.getInstance(DB_URL).reference
        roomsRef = rootRef.child("rooms")

        checkAndCreateRoomsIfMissing()
    }
    override fun onResume() {
        super.onResume()
        KioskMode.enable(this)
    }


    /**
     * Prüfen ob /rooms existiert.
     * Falls nein → automatisch anlegen.
     */
    private fun checkAndCreateRoomsIfMissing() {

        infoContainer.removeAllViews()
        roomLayout.removeAllViews()

        // Info oben fest
        infoContainer.addView(makeInfoChip("DB verbunden ✓"))
        infoContainer.addView(makeInfoChip("URL: $DB_URL"))

        roomsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                if (!snapshot.exists()) {
                    infoContainer.addView(makeInfoChip("Node /rooms fehlt → wird angelegt..."))
                    createRoomsNode()
                    return
                }

                if (snapshot.childrenCount == 0L) {
                    infoContainer.addView(makeInfoChip("/rooms existiert, aber ist leer → wird befüllt..."))
                    createRoomsNode()
                    return
                }

                // Wenn alles da ist → Räume anzeigen
                observeRooms()
            }

            override fun onCancelled(error: DatabaseError) {
                infoContainer.addView(makeInfoChip("Fehler: ${error.message}"))
            }
        })
    }

    /**
     * Räume automatisch anlegen
     */
    private fun createRoomsNode() {
        val roomsMap = mapOf(
            "Restaurant" to true,
            "Gewölbe" to true,
            "Scheune EG" to true,
            "Scheune UG" to true,
            "Terrasse" to true
        )

        roomsRef.setValue(roomsMap)
            .addOnSuccessListener {
                infoContainer.addView(makeInfoChip("Räume erfolgreich angelegt ✓"))
                observeRooms()
            }
            .addOnFailureListener { e ->
                infoContainer.addView(makeInfoChip("Fehler: ${e.message}"))
            }
    }

    /**
     * Räume anzeigen (✅ fix sortiert + Buttons gleich groß)
     */
    private fun observeRooms() {
        roomsRef.addValueEventListener(object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                infoContainer.removeAllViews()
                roomLayout.removeAllViews()

                // ✅ Dieses Feld soll so bleiben wie es ist
                infoContainer.addView(makeInfoChip("Räume unter /rooms gefunden ✓"))

                val existingKeys = snapshot.children.mapNotNull { it.key }.toSet()

                // Räume: gewünschte Reihenfolge
                val roomsToShow = mutableListOf<String>()
                roomsToShow.addAll(roomOrder)

                // Optional: andere Räume unten anhängen
                val extras = existingKeys
                    .filter { it !in roomOrder }
                    .sorted()

                roomsToShow.addAll(extras)

                // ✅ Buttons gleichmäßig verteilen
                val count = roomsToShow.size.coerceAtLeast(1)
                val weightPerButton = 1f

                for (name in roomsToShow) {
                    roomLayout.addView(makeRoomButton(name, weightPerButton))
                }
            }

            override fun onCancelled(error: DatabaseError) {
                infoContainer.removeAllViews()
                roomLayout.removeAllViews()
                infoContainer.addView(makeInfoChip("Firebase Fehler: ${error.message}"))
            }
        })
    }

    private fun makeRoomButton(roomName: String, weight: Float): Button {
        return Button(this).apply {
            text = roomName
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.parseColor("#2F2A26"))

            // ✅ moderner Look
            setBackgroundResource(R.drawable.room_button_modern_bg)
            backgroundTintList = null

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                this.weight = weight
                topMargin = dp(10)
            }

            setOnClickListener {
                val intent = Intent(this@RoomSelectionActivity, TableActivity::class.java)
                intent.putExtra("ROOM_NAME", roomName)
                startActivity(intent)
            }
        }
    }

    // ✅ Info-Chip (oben, bleibt fix, nicht gleich groß wie Buttons)
    private fun makeInfoChip(message: String): Button {
        return Button(this).apply {
            text = message
            textSize = 13f
            isAllCaps = false
            isEnabled = false

            setTextColor(Color.parseColor("#3A2A20"))
            setBackgroundResource(R.drawable.room_info_chip_bg)
            backgroundTintList = null

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
