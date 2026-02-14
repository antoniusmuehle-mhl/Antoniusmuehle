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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import kotlin.math.max

data class TablePlan(
    val occupied: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 1,
    val h: Int = 1
)

class TableActivity : AppCompatActivity() {

    private lateinit var canvas: FrameLayout
    private lateinit var roomTitleTxt: TextView
    private lateinit var tablesRef: DatabaseReference
    private lateinit var roomName: String

    // ======= Virtuelles Raster (Tischgrößen) =======
    private val virtualCell = 90f
    private val virtualGap = 12f
    private val virtualPadding = 0f   // ✅ bis an Wand

    // ======= Virtuelle Raumgröße (wird je nach Raum gesetzt) =======
    private var virtualW = 1400f
    private var virtualH = 900f

    // ✅ Transform virtual -> px
    private var scaleX = 1f
    private var scaleY = 1f

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

        roomName = intent.getStringExtra("ROOM_NAME") ?: "Unbekannt"

        // ✅ Raumgröße abhängig vom Raum setzen
        applyRoomSize(roomName)

        roomTitleTxt = findViewById(R.id.roomTitleTxt)
        roomTitleTxt.text = roomName

        canvas = findViewById(R.id.planCanvas)

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

        findViewById<ImageButton>(R.id.addTableBtn).setOnClickListener {
            addNewTable()
        }

        canvas.post { scheduleRender() }
        observeTables()
    }

    /**
     * ✅ Restaurant bekommt mehr Breite (18 Spalten),
     * alle anderen Räume bleiben wie vorher.
     */
    private fun applyRoomSize(room: String) {
        if (room.trim().equals("Restaurant", ignoreCase = true)) {

            // ✅ HIER: 18 Spalten wie gewünscht
            val cols = 18
            val rows = 9

            // Exakt aus Raster berechnen (damit "bis an Wand" sauber klappt)
            virtualW = cols * virtualCell + (cols - 1) * virtualGap
            virtualH = rows * virtualCell + (rows - 1) * virtualGap

        } else {
            virtualW = 1400f
            virtualH = 900f
        }
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

    // ===================== Render =====================
    private fun renderFromCacheInternal() {
        canvas.removeAllViews()

        if (planCache.isEmpty()) {
            canvas.addView(Button(this).apply {
                text = "Keine Tische vorhanden.\nKlicke „Tische anlegen“."
                isEnabled = false
            })
            return
        }

        computeTransform()

        for ((tableId, raw) in planCache) {
            val plan = clampPlanToBounds(raw)
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

            setBackgroundResource(
                if (plan.occupied) R.drawable.table_busy_bg else R.drawable.table_free_bg
            )
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

    // ===================== Edit Gesten =====================
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

                        val canvasW = getCanvasWidthPx()
                        val canvasH = getCanvasHeightPx()

                        val maxLeft = (canvasW - lp.width).coerceAtLeast(0)
                        val maxTop = (canvasH - lp.height).coerceAtLeast(0)

                        lp.leftMargin = (origLeft + dx).coerceIn(0, maxLeft)
                        lp.topMargin = (origTop + dy).coerceIn(0, maxTop)

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
                        val (gx, gy) = screenPxToGrid(lp.leftMargin.toFloat(), lp.topMargin.toFloat())

                        val cur = planCache[tableId] ?: return@setOnTouchListener true
                        val next = clampPlanToBounds(cur.copy(x = gx, y = gy))

                        planCache[tableId] = next
                        scheduleRender()
                    }
                    true
                }

                else -> true
            }
        }
    }

    // ===================== Menü =====================
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
                if (newId.isEmpty()) return@setPositiveButton
                if (newId == oldId) return@setPositiveButton

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
            .addOnFailureListener { e ->
                AlertDialog.Builder(this)
                    .setTitle("Fehler")
                    .setMessage("Umbenennen fehlgeschlagen: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
    }

    private fun confirmDeleteTable(tableId: String) {
        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId löschen?")
            .setMessage("Diese Aktion kann nicht rückgängig gemacht werden.\n\nWillst du Tisch $tableId wirklich löschen?")
            .setPositiveButton("LÖSCHEN") { _, _ ->
                deleteTableInFirebase(tableId)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun deleteTableInFirebase(tableId: String) {
        tablesRef.child(tableId).removeValue()
            .addOnSuccessListener {
                planCache.remove(tableId)
                scheduleRender()
            }
            .addOnFailureListener { e ->
                AlertDialog.Builder(this)
                    .setTitle("Fehler")
                    .setMessage("Löschen fehlgeschlagen: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
    }

    // ===================== Tisch hinzufügen =====================
    private fun addNewTable() {
        val used = planCache.keys.mapNotNull { it.toIntOrNull() }.toSet()
        var next = 1
        while (used.contains(next)) next++
        val newId = next.toString()

        val start = clampPlanToBounds(TablePlan(false, x = 0, y = 0, w = 1, h = 1))

        val updates = hashMapOf<String, Any>(
            "$newId/occupied" to false,
            "$newId/x" to start.x,
            "$newId/y" to start.y,
            "$newId/w" to 1,
            "$newId/h" to 1
        )
        tablesRef.updateChildren(updates)
    }

    // ===================== Größe ändern =====================
    private fun showResizeDialog(tableId: String) {
        val current = planCache[tableId] ?: return

        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId Größe")
            .setMessage("Breite: ${current.w}\nHöhe: ${current.h}\n\nLong-Press = Verschieben")
            .setPositiveButton("Breite +") { _, _ ->
                planCache[tableId] = clampPlanToBounds(current.copy(w = (current.w + 1).coerceAtMost(6)))
                scheduleRender()
            }
            .setNeutralButton("Breite -") { _, _ ->
                planCache[tableId] = clampPlanToBounds(current.copy(w = (current.w - 1).coerceAtLeast(1)))
                scheduleRender()
            }
            .setNegativeButton("Höhe…") { _, _ ->
                showResizeDialogHeight(tableId)
            }
            .show()
    }

    private fun showResizeDialogHeight(tableId: String) {
        val current = planCache[tableId] ?: return

        AlertDialog.Builder(this)
            .setTitle("Tisch $tableId Höhe")
            .setMessage("Breite: ${current.w}\nHöhe: ${current.h}")
            .setPositiveButton("Höhe +") { _, _ ->
                planCache[tableId] = clampPlanToBounds(current.copy(h = (current.h + 1).coerceAtMost(6)))
                scheduleRender()
            }
            .setNeutralButton("Höhe -") { _, _ ->
                planCache[tableId] = clampPlanToBounds(current.copy(h = (current.h - 1).coerceAtLeast(1)))
                scheduleRender()
            }
            .setNegativeButton("Fertig", null)
            .show()
    }

    // ===================== Speichern =====================
    private fun savePlanToFirebase() {
        val updates = hashMapOf<String, Any>()
        for ((tableId, raw) in planCache) {
            val plan = clampPlanToBounds(raw)
            updates["$tableId/x"] = plan.x
            updates["$tableId/y"] = plan.y
            updates["$tableId/w"] = plan.w
            updates["$tableId/h"] = plan.h
        }
        tablesRef.updateChildren(updates)
    }

    // ===================== Seed =====================
    private fun seedLayoutIfEmpty() {
        tablesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && snapshot.childrenCount > 0) return

                val updates = hashMapOf<String, Any>()
                for (i in 1..10) add(updates, "T$i", (i - 1) % 4, (i - 1) / 4, 1, 1)
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

    // ===================== Transform / Bounds =====================
    private fun computeTransform() {
        val cw = getCanvasWidthPx().toFloat()
        val ch = getCanvasHeightPx().toFloat()
        scaleX = cw / virtualW
        scaleY = ch / virtualH
    }

    private fun frameParamsFor(plan: TablePlan): FrameLayout.LayoutParams {
        val leftV = virtualPadding + plan.x * (virtualCell + virtualGap)
        val topV = virtualPadding + plan.y * (virtualCell + virtualGap)
        val widthV = plan.w * virtualCell + (plan.w - 1) * virtualGap
        val heightV = plan.h * virtualCell + (plan.h - 1) * virtualGap

        val leftPx = (leftV * scaleX).toInt()
        val topPx = (topV * scaleY).toInt()
        val wPx = max(1, (widthV * scaleX).toInt())
        val hPx = max(1, (heightV * scaleY).toInt())

        return FrameLayout.LayoutParams(wPx, hPx).apply {
            leftMargin = leftPx
            topMargin = topPx
        }
    }

    private fun screenPxToGrid(leftPx: Float, topPx: Float): Pair<Int, Int> {
        val leftV = leftPx / scaleX
        val topV = topPx / scaleY
        val step = (virtualCell + virtualGap)

        val gx = ((leftV - virtualPadding) / step).toInt().coerceAtLeast(0)
        val gy = ((topV - virtualPadding) / step).toInt().coerceAtLeast(0)
        return gx to gy
    }

    private fun clampPlanToBounds(plan: TablePlan): TablePlan {
        val newW = plan.w.coerceAtLeast(1)
        val newH = plan.h.coerceAtLeast(1)

        val cols = (((virtualW - 2 * virtualPadding) + virtualGap) / (virtualCell + virtualGap))
            .toInt().coerceAtLeast(1)
        val rows = (((virtualH - 2 * virtualPadding) + virtualGap) / (virtualCell + virtualGap))
            .toInt().coerceAtLeast(1)

        val maxX = (cols - newW).coerceAtLeast(0)
        val maxY = (rows - newH).coerceAtLeast(0)

        return plan.copy(
            w = newW,
            h = newH,
            x = plan.x.coerceIn(0, maxX),
            y = plan.y.coerceIn(0, maxY)
        )
    }

    private fun getCanvasWidthPx(): Int {
        val w = canvas.width.takeIf { it > 0 }
            ?: canvas.measuredWidth.takeIf { it > 0 }
            ?: canvas.layoutParams.width
        return w.coerceAtLeast(1)
    }

    private fun getCanvasHeightPx(): Int {
        val h = canvas.height.takeIf { it > 0 }
            ?: canvas.measuredHeight.takeIf { it > 0 }
            ?: canvas.layoutParams.height
        return h.coerceAtLeast(1)
    }
}
