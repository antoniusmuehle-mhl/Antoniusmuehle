package com.example.antoniusmuehle

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*

data class TablePlan(
    val occupied: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 1,
    val h: Int = 1
)

class TableActivity : AppCompatActivity() {

    private lateinit var planFrame: FrameLayout
    private lateinit var canvas: FrameLayout
    private lateinit var tablesRef: DatabaseReference
    private lateinit var roomName: String

    // Raster-Basis (wird pro Raum dynamisch “in den Screen” skaliert)
    private val baseCellDp = 90
    private val baseGapDp = 12

    // dynamische Werte (werden nach Layout gesetzt)
    private var cellDp = baseCellDp
    private var gapDp = baseGapDp
    private var gridCols = 12
    private var gridRows = 8

    private var editMode = false
    private val planCache = linkedMapOf<String, TablePlan>()

    private var renderScheduled = false
    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        canvas.post {
            renderScheduled = false
            renderFromCacheInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table)
        KioskMode.enable(this)

        roomName = intent.getStringExtra("ROOM_NAME") ?: "Unbekannt"

        planFrame = findViewById(R.id.planFrame)
        canvas = findViewById(R.id.planCanvas)

        findViewById<TextView>(R.id.roomTitleTxt).text = roomName

        tablesRef = FirebaseDatabase.getInstance()
            .reference
            .child("rooms")
            .child(roomName)
            .child("tables")

        findViewById<Button>(R.id.backToRoomsBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.seedTablesBtn).setOnClickListener { seedLayoutIfEmpty() }

        val editBtn = findViewById<Button>(R.id.editToggleBtn)
        val saveBtn = findViewById<Button>(R.id.savePlanBtn)

        editBtn.setOnClickListener {
            editMode = !editMode
            editBtn.text = if (editMode) "Fertig" else "Bearbeiten"
            saveBtn.isEnabled = editMode
            scheduleRender()
        }
        saveBtn.setOnClickListener { savePlanToFirebase() }

        findViewById<ImageButton>(R.id.addTableBtn).setOnClickListener { addNewTable() }

        // ✅ WICHTIG: erst nach dem Layout Grid/Cell skalieren, damit nichts “gezoomt” wirkt
        planFrame.post {
            configureRoomViewport()
            observeTables()
        }
    }
    override fun onResume() {
        super.onResume()
        KioskMode.enable(this)
    }


    /**
     * Macht den Raum IMMER passend in den Screen.
     * - definiert Grid-Größe pro Raum
     * - skaliert cellDp/gapDp so, dass gridCols/gridRows exakt in den Frame passen
     * - setzt Restaurant Background (bg_restaurant.png), ohne Verzerrung
     */
    private fun configureRoomViewport() {
        // 1) feste Grid-Dimensionen pro Raum (damit “Tische im vollen Umfang gesehen werden”)
        //    -> Restaurant größer, andere normal
        when (roomName.trim()) {
            "Restaurant" -> {
                gridCols = 20
                gridRows = 13 // “höher” (ca. +1x1)
            }
            "Scheune EG" -> {
                gridCols = 18
                gridRows = 10
            }
            "Scheune UG" -> {
                gridCols = 18
                gridRows = 8
            }
            "Gewölbe" -> {
                gridCols = 18
                gridRows = 9
            }
            "Terrasse" -> {
                gridCols = 18
                gridRows = 8
            }
            else -> {
                gridCols = 12
                gridRows = 8
            }
        }

        // 2) verfügbare Pixel im Frame
        val availW = (planFrame.width - planFrame.paddingLeft - planFrame.paddingRight).coerceAtLeast(1)
        val availH = (planFrame.height - planFrame.paddingTop - planFrame.paddingBottom).coerceAtLeast(1)

        // 3) cellDp/gapDp so skalieren, dass Raster komplett reinpasst
        //    Rasterbreite = cols*cell + (cols-1)*gap
        //    Rasterhöhe   = rows*cell + (rows-1)*gap
        //    Wir starten bei base und skalieren runter falls nötig.
        val baseCellPx = dp(baseCellDp)
        val baseGapPx = dp(baseGapDp)

        val needWBase = gridCols * baseCellPx + (gridCols - 1) * baseGapPx
        val needHBase = gridRows * baseCellPx + (gridRows - 1) * baseGapPx

        val scaleW = availW.toFloat() / needWBase.toFloat()
        val scaleH = availH.toFloat() / needHBase.toFloat()
        val scale = minOf(scaleW, scaleH, 1f) // nie größer skalieren (sonst “zoomt” es)

        val newCellPx = (baseCellPx * scale).toInt().coerceAtLeast(dp(48)) // Minimum
        val newGapPx = (baseGapPx * scale).toInt().coerceAtLeast(dp(6))

        // zurück in dp-Äquivalent speichern (für unsere dp()-Funktion nutzen wir px, daher speichern wir als px->dp nicht nötig)
        // Wir arbeiten intern weiter in “dp-Werten” und wandeln dann. Einfacher:
        // -> Wir speichern cellDp/gapDp als “px-basierte dp-Werte” via pxToDp.
        cellDp = pxToDp(newCellPx)
        gapDp = pxToDp(newGapPx)

        // 4) Background setzen
        if (roomName.trim() == "Restaurant") {
            // Hintergrundbild nur im Canvas-Bereich (nicht darüber hinaus)
            canvas.setBackgroundResource(R.drawable.bg_restaurant)
        } else {
            canvas.setBackgroundColor(Color.parseColor("#F4ECE6"))
        }

        scheduleRender()
    }

    private fun observeTables() {
        tablesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                planCache.clear()

                for (tableSnap in snapshot.children) {
                    val id = tableSnap.key ?: continue
                    val occupied = tableSnap.child("occupied").getValue(Boolean::class.java) ?: false
                    val x = tableSnap.child("x").getValue(Int::class.java) ?: 0
                    val y = tableSnap.child("y").getValue(Int::class.java) ?: 0
                    val w = tableSnap.child("w").getValue(Int::class.java) ?: 1
                    val h = tableSnap.child("h").getValue(Int::class.java) ?: 1
                    planCache[id] = TablePlan(occupied, x, y, w, h)
                }

                scheduleRender()
            }

            override fun onCancelled(error: DatabaseError) {
                canvas.removeAllViews()
                canvas.addView(Button(this@TableActivity).apply {
                    text = "Firebase Fehler: ${error.message}"
                    isEnabled = false
                })
            }
        })
    }

    private fun renderFromCacheInternal() {
        canvas.removeAllViews()

        if (planCache.isEmpty()) {
            canvas.addView(Button(this).apply {
                text = "Keine Tische vorhanden.\nKlicke „Tische anlegen“."
                isEnabled = false
            })
            return
        }

        for ((tableId, raw) in planCache) {
            val plan = clampPlanToGrid(raw)
            if (plan != raw) planCache[tableId] = plan

            val btn = createTableButton(tableId, plan)
            canvas.addView(btn)
        }
    }

    private fun createTableButton(tableId: String, plan: TablePlan): Button {
        val btn = Button(this).apply {
            text = tableId
            isAllCaps = false
            setTextColor(Color.parseColor("#3A2A20"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setBackgroundResource(if (plan.occupied) R.drawable.table_busy_bg else R.drawable.table_free_bg)
            backgroundTintList = null
        }

        btn.layoutParams = frameParamsFor(plan)

        if (!editMode) {
            btn.setOnTouchListener(null)
            btn.setOnClickListener {
                val intent = Intent(this@TableActivity, OrderActivity::class.java)
                intent.putExtra("ROOM_NAME", roomName)
                intent.putExtra("TABLE_ID", tableId)
                startActivity(intent)
            }
        } else {
            enableEditGestures(btn, tableId)
        }

        return btn
    }

    private fun enableEditGestures(view: Button, tableId: String) {
        var dragging = false
        var startRawX = 0f
        var startRawY = 0f
        var origLeft = 0
        var origTop = 0

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showEditTableMenu(tableId)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                dragging = true
                view.alpha = 0.85f

                val lp = view.layoutParams as FrameLayout.LayoutParams
                origLeft = lp.leftMargin
                origTop = lp.topMargin
                startRawX = e.rawX
                startRawY = e.rawY
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
        })

        view.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)

                        val dx = (event.rawX - startRawX).toInt()
                        val dy = (event.rawY - startRawY).toInt()

                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        val maxLeft = (canvas.width - lp.width).coerceAtLeast(0)
                        val maxTop = (canvas.height - lp.height).coerceAtLeast(0)

                        val newLeft = (origLeft + dx).coerceIn(0, maxLeft)
                        val newTop = (origTop + dy).coerceIn(0, maxTop)

                        val (gx, gy) = pxToGrid(newLeft, newTop)
                        lp.leftMargin = gridToPxX(gx).coerceIn(0, maxLeft)
                        lp.topMargin = gridToPxY(gy).coerceIn(0, maxTop)
                        v.layoutParams = lp
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        view.alpha = 1f
                        v.parent?.requestDisallowInterceptTouchEvent(false)

                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        val (gx, gy) = pxToGrid(lp.leftMargin, lp.topMargin)

                        val cur = planCache[tableId] ?: return@setOnTouchListener true
                        planCache[tableId] = clampPlanToGrid(cur.copy(x = gx, y = gy))

                        scheduleRender()
                    }
                    true
                }

                else -> true
            }
        }
    }

    // ===== MENÜ =====

    private fun showEditTableMenu(tableId: String) {
        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId")
            .setItems(arrayOf("Tischnummer ändern", "Größe ändern", "Tisch löschen")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(tableId)
                    1 -> showResizeDialog(tableId)
                    2 -> confirmDeleteTable(tableId)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showRenameDialog(oldId: String) {
        val input = EditText(this).apply {
            hint = "Neue Tischnummer"
            setText(oldId)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("Tischnummer ändern")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val newId = input.text.toString().trim()
                if (newId.isEmpty() || newId == oldId) return@setPositiveButton

                if (planCache.containsKey(newId)) {
                    AlertDialog.Builder(this)
                        .setTitle("Fehler")
                        .setMessage("Tisch $newId existiert bereits.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }
                renameTableInFirebase(oldId, newId)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun renameTableInFirebase(oldId: String, newId: String) {
        val oldPlan = planCache[oldId] ?: return
        val newData = mapOf(
            "occupied" to oldPlan.occupied,
            "x" to oldPlan.x,
            "y" to oldPlan.y,
            "w" to oldPlan.w,
            "h" to oldPlan.h
        )

        tablesRef.child(newId).setValue(newData)
            .addOnSuccessListener {
                tablesRef.child(oldId).removeValue()
                planCache.remove(oldId)
                planCache[newId] = oldPlan
                scheduleRender()
            }
    }

    private fun confirmDeleteTable(tableId: String) {
        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId löschen?")
            .setMessage("Willst du Tisch $tableId wirklich löschen?")
            .setPositiveButton("LÖSCHEN") { _, _ -> deleteTableInFirebase(tableId) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun deleteTableInFirebase(tableId: String) {
        tablesRef.child(tableId).removeValue()
            .addOnSuccessListener {
                planCache.remove(tableId)
                scheduleRender()
            }
    }

    // ===== PLUS =====

    private fun addNewTable() {
        val used = planCache.keys.mapNotNull { it.toIntOrNull() }.toSet()
        var next = 1
        while (used.contains(next)) next++
        val newId = next.toString()

        val spot = findFirstFreeSpot(w = 1, h = 1)
        tablesRef.child(newId).setValue(
            mapOf(
                "occupied" to false,
                "x" to spot.first,
                "y" to spot.second,
                "w" to 1,
                "h" to 1
            )
        )
    }

    private fun findFirstFreeSpot(w: Int, h: Int): Pair<Int, Int> {
        fun overlaps(x: Int, y: Int, other: TablePlan): Boolean {
            val ax1 = x
            val ay1 = y
            val ax2 = x + w - 1
            val ay2 = y + h - 1
            val bx1 = other.x
            val by1 = other.y
            val bx2 = other.x + other.w - 1
            val by2 = other.y + other.h - 1
            return !(ax2 < bx1 || ax1 > bx2 || ay2 < by1 || ay1 > by2)
        }

        val maxX = (gridCols - w).coerceAtLeast(0)
        val maxY = (gridRows - h).coerceAtLeast(0)

        for (yy in 0..maxY) {
            for (xx in 0..maxX) {
                val ok = planCache.values.none { overlaps(xx, yy, it) }
                if (ok) return xx to yy
            }
        }
        return 0 to 0
    }

    // ===== RESIZE =====

    private fun showResizeDialog(tableId: String) {
        val current = planCache[tableId] ?: return

        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId Größe")
            .setMessage("Breite: ${current.w}\nHöhe: ${current.h}\n\nLong-Press = Verschieben")
            .setPositiveButton("Breite +") { _, _ ->
                planCache[tableId] = clampPlanToGrid(current.copy(w = (current.w + 1).coerceAtMost(6)))
                scheduleRender()
            }
            .setNeutralButton("Breite -") { _, _ ->
                planCache[tableId] = clampPlanToGrid(current.copy(w = (current.w - 1).coerceAtLeast(1)))
                scheduleRender()
            }
            .setNegativeButton("Höhe…") { _, _ -> showResizeDialogHeight(tableId) }
            .show()
    }

    private fun showResizeDialogHeight(tableId: String) {
        val current = planCache[tableId] ?: return

        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId Höhe")
            .setMessage("Breite: ${current.w}\nHöhe: ${current.h}")
            .setPositiveButton("Höhe +") { _, _ ->
                planCache[tableId] = clampPlanToGrid(current.copy(h = (current.h + 1).coerceAtMost(6)))
                scheduleRender()
            }
            .setNeutralButton("Höhe -") { _, _ ->
                planCache[tableId] = clampPlanToGrid(current.copy(h = (current.h - 1).coerceAtLeast(1)))
                scheduleRender()
            }
            .setNegativeButton("Fertig", null)
            .show()
    }

    private fun savePlanToFirebase() {
        val updates = hashMapOf<String, Any>()
        for ((tableId, raw) in planCache) {
            val plan = clampPlanToGrid(raw)
            updates["$tableId/x"] = plan.x
            updates["$tableId/y"] = plan.y
            updates["$tableId/w"] = plan.w
            updates["$tableId/h"] = plan.h
        }
        tablesRef.updateChildren(updates)
    }

    // ===== SEED =====

    private fun seedLayoutIfEmpty() {
        tablesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && snapshot.childrenCount > 0) return

                val updates = hashMapOf<String, Any>()

                when (roomName.trim()) {
                    "Restaurant" -> {
                        // bleibt wie du es zuletzt gesetzt hattest – kann später angepasst werden
                        add(updates, "1", 18, 2, 2, 2)
                        add(updates, "2", 18, 0, 2, 1)
                        add(updates, "3", 18, 5, 2, 2)
                        add(updates, "4", 18, 8, 2, 2)
                        add(updates, "5", 18, 10, 2, 2)
                        add(updates, "6", 13, 0, 2, 1)
                        add(updates, "7", 19, 0, 1, 1)
                        add(updates, "8", 19, 2, 1, 2)
                        add(updates, "9", 14, 1, 2, 2)
                        add(updates, "10", 19, 7, 1, 2)
                        add(updates, "11", 9, 7, 2, 3)
                        add(updates, "14", 3, 0, 2, 1)
                    }
                    "Gewölbe" -> {
                        add(updates, "17", 3, 0, 2, 2)
                        add(updates, "18", 7, 0, 2, 2)
                        add(updates, "14", 0, 4, 2, 3)
                        add(updates, "15", 4, 4, 2, 3)
                        add(updates, "16", 8, 4, 2, 3)
                    }
                    "Scheune EG" -> {
                        add(updates, "30", 0, 0, 1, 1)
                        add(updates, "31", 1, 0, 2, 1)
                        add(updates, "32", 3, 0, 2, 1)
                        add(updates, "33", 5, 0, 2, 1)
                        add(updates, "34", 7, 0, 2, 1)
                        add(updates, "37", 0, 2, 1, 2)
                        add(updates, "38", 0, 4, 1, 2)
                        add(updates, "35", 3, 4, 3, 1)
                        add(updates, "36", 8, 3, 3, 1)
                    }
                    "Scheune UG" -> {
                        add(updates, "40", 0, 3, 1, 1)
                        add(updates, "41", 3, 4, 2, 2)
                        add(updates, "42", 6, 6, 2, 2)
                        add(updates, "43", 7, 4, 2, 2)
                        add(updates, "44", 9, 2, 2, 2)
                    }
                    "Terrasse" -> {
                        add(updates, "60", 0, 0, 2, 2)
                        add(updates, "61", 2, 0, 2, 2)
                        add(updates, "62", 4, 0, 2, 2)
                        add(updates, "65", 6, 1, 1, 1)
                        add(updates, "63", 1, 4, 2, 2)
                        add(updates, "64", 3, 4, 2, 2)
                        add(updates, "66", 7, 4, 2, 2)
                        add(updates, "67", 8, 7, 3, 1)
                    }
                    else -> {
                        for (i in 1..10) add(updates, "T$i", (i - 1) % 4, (i - 1) / 4, 1, 1)
                    }
                }

                tablesRef.updateChildren(updates)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun add(updates: HashMap<String, Any>, id: String, x: Int, y: Int, w: Int, h: Int) {
        updates["$id/occupied"] = false
        updates["$id/x"] = x
        updates["$id/y"] = y
        updates["$id/w"] = w
        updates["$id/h"] = h
    }

    // ===== GRID / LAYOUT =====

    private fun frameParamsFor(plan: TablePlan): FrameLayout.LayoutParams {
        val widthPx = dp(plan.w * cellDp + (plan.w - 1) * gapDp)
        val heightPx = dp(plan.h * cellDp + (plan.h - 1) * gapDp)
        return FrameLayout.LayoutParams(widthPx, heightPx).apply {
            leftMargin = gridToPxX(plan.x)
            topMargin = gridToPxY(plan.y)
        }
    }

    private fun clampPlanToGrid(plan: TablePlan): TablePlan {
        val newW = plan.w.coerceAtLeast(1)
        val newH = plan.h.coerceAtLeast(1)

        val maxX = (gridCols - newW).coerceAtLeast(0)
        val maxY = (gridRows - newH).coerceAtLeast(0)

        return plan.copy(
            w = newW,
            h = newH,
            x = plan.x.coerceIn(0, maxX),
            y = plan.y.coerceIn(0, maxY)
        )
    }

    private fun gridToPxX(x: Int): Int = dp(x * (cellDp + gapDp))
    private fun gridToPxY(y: Int): Int = dp(y * (cellDp + gapDp))

    private fun pxToGrid(leftPx: Int, topPx: Int): Pair<Int, Int> {
        val stepPx = dp(cellDp + gapDp).coerceAtLeast(1)
        val x = (leftPx.toFloat() / stepPx).toInt().coerceAtLeast(0)
        val y = (topPx.toFloat() / stepPx).toInt().coerceAtLeast(0)
        return x to y
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px / density).toInt().coerceAtLeast(1)
    }
}
