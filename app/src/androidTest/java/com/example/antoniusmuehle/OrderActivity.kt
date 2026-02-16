package com.example.antoniusmuehle

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderActivity : AppCompatActivity() {

    private lateinit var db: DatabaseReference
    private lateinit var roomName: String
    private lateinit var tableId: String

    private lateinit var menuRecycler: RecyclerView
    private lateinit var menuRecyclerAdapter: MenuRecyclerAdapter

    private lateinit var tabDrinksBtn: Button
    private lateinit var tabFoodsBtn: Button
    private enum class Tab { DRINKS, FOODS }
    private var activeTab: Tab = Tab.DRINKS

    private lateinit var orderList: ListView
    private lateinit var totalTxt: TextView
    private lateinit var printBtn: Button
    private lateinit var payBtn: Button

    private lateinit var currentOrderRef: DatabaseReference
    private lateinit var orderItemsRef: DatabaseReference
    private lateinit var orderMetaRef: DatabaseReference
    private lateinit var historyRef: DatabaseReference
    private lateinit var tableOccupiedRef: DatabaseReference

    private val orderCache = linkedMapOf<String, OrderItem>()
    private lateinit var orderAdapter: OrderAdapter

    // ---------- MENU (Accordion) ----------
    private val menuRows = mutableListOf<MenuRow>()

    // ========= DRINKS Baum =========
    private data class DrinkTreeNode(
        val title: String,
        val children: LinkedHashMap<String, DrinkTreeNode> = linkedMapOf(),
        val items: MutableList<MenuItem> = mutableListOf()
    )

    private val drinksTree = linkedMapOf<String, DrinkTreeNode>()

    // ========= FOODS =========
    private val foodsData = linkedMapOf<String, Pair<String, List<MenuItem>>>()
    private val foodSubData = linkedMapOf<String, LinkedHashMap<String, Pair<String, List<MenuItem>>>>()

    // Accordion-State
    private var expandedCategoryKey: String? = null

    // ✅ beliebig tiefe Unterordner – aber nur 1 pro Ebene offen
    private val expandedGroupPaths = mutableSetOf<String>()
    private var expandedMainSubKey: String? = null

    // Sortierung Top-Level Drinks (exakt nach Wunsch)
    private val drinksOrder = listOf(
        "alkoholfrei",
        "bier",
        "alkoholfreie_biere",
        "wein_sekt",
        "longdrinks_cocktail",
        "schnaps",
        "heissgetraenke"
    )

    private val foodsOrder = listOf(
        "vorspeisen",
        "suppen",
        "salate",
        "hauptspeisen",
        "nachspeisen",
        "kindergerichte",
        "menues",
        "Kleinigkeiten"
    )

    private val mainsOrder = listOf("Spezial", "huhn", "rind", "fisch", "schwein", "veg_vegan")

    // ====================== DRUCKER SETTINGS ======================
    private val BAR_PRINTER_IP = "192.168.178.135"
    private val KITCHEN_PRINTER_IP = "192.168.178.135"
    private val PRINTER_PORT = 9100
    // =============================================================

    private val collator: Collator = Collator.getInstance(Locale.GERMANY).apply {
        strength = Collator.PRIMARY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        roomName = intent.getStringExtra("ROOM_NAME") ?: "Unbekannt"
        tableId = intent.getStringExtra("TABLE_ID") ?: "?"

        db = FirebaseDatabase.getInstance().reference

        menuRecycler = findViewById(R.id.menuRecycler)
        orderList = findViewById(R.id.orderList)
        totalTxt = findViewById(R.id.totalTxt)
        printBtn = findViewById(R.id.printBtn)
        payBtn = findViewById(R.id.payBtn)
        tabDrinksBtn = findViewById(R.id.tabDrinksBtn)
        tabFoodsBtn = findViewById(R.id.tabFoodsBtn)

        findViewById<TextView>(R.id.titleTxt).text = "Tisch $tableId"

        findViewById<Button>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.toRoomsBtn).setOnClickListener {
            val i = Intent(this, RoomSelectionActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(i)
            finish()
        }

        currentOrderRef = db.child("orders").child(roomName).child(tableId).child("current")
        orderItemsRef = currentOrderRef.child("items")
        orderMetaRef = currentOrderRef
        historyRef = db.child("orders").child(roomName).child(tableId).child("history")
        tableOccupiedRef = db.child("rooms").child(roomName).child("tables").child(tableId).child("occupied")

        orderAdapter = OrderAdapter(
            context = this,
            items = emptyList(),
            onPlus = { itOrder ->
                orderItemsRef.child(itOrder.itemId).child("qty").setValue(itOrder.qty + 1)
            },
            onMinus = { itOrder ->
                if (itOrder.qty > 1) {
                    orderItemsRef.child(itOrder.itemId).child("qty").setValue(itOrder.qty - 1)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Artikel löschen?")
                        .setMessage("Willst du „${itOrder.name}“ wirklich entfernen?")
                        .setPositiveButton("LÖSCHEN") { _, _ ->
                            orderItemsRef.child(itOrder.itemId).removeValue()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
        )
        orderList.adapter = orderAdapter

        menuRecycler.layoutManager = LinearLayoutManager(this)
        menuRecyclerAdapter = MenuRecyclerAdapter(
            rows = menuRows,
            onCategoryClick = { cat ->
                expandedCategoryKey = if (expandedCategoryKey == cat.key) null else cat.key
                expandedGroupPaths.clear()
                expandedMainSubKey = null
                rebuildMenuRows()
            },
            onSubCategoryClick = { sub ->
                toggleSingleOpenFolder(sub.key)
                expandedMainSubKey = sub.key
                rebuildMenuRows()
            },
            onItemClick = { item ->
                if (activeTab == Tab.DRINKS && item.sizes.isNotEmpty()) {
                    showDrinkSizeDialog(item)
                } else {
                    addItemToOrder(item)
                }
            }
        )
        menuRecycler.adapter = menuRecyclerAdapter

        tabDrinksBtn.setOnClickListener {
            activeTab = Tab.DRINKS
            expandedCategoryKey = null
            expandedGroupPaths.clear()
            expandedMainSubKey = null
            refreshTabUi()
            rebuildMenuRows()
        }
        tabFoodsBtn.setOnClickListener {
            activeTab = Tab.FOODS
            expandedCategoryKey = null
            expandedGroupPaths.clear()
            expandedMainSubKey = null
            refreshTabUi()
            rebuildMenuRows()
        }
        refreshTabUi()

        printBtn.setOnClickListener { confirmPrint() }
        payBtn.setOnClickListener { confirmPayAndClose() }

        splitBtn.setOnClickListener {
            val i = Intent(this, SplitBillActivity::class.java)
            i.putExtra("ROOM_NAME", roomName)
            i.putExtra("TABLE_ID", tableId)
            startActivity(i)
        }


        observeMenuFromFirebase()
        observeOrder()
    }

    // ✅ Nur 1 Ordner pro Ebene offen: Öffnen schließt Geschwister + deren Unterordner
    private fun toggleSingleOpenFolder(path: String) {
        val isOpen = expandedGroupPaths.contains(path)
        val parent = path.substringBeforeLast("/", missingDelimiterValue = "")

        if (isOpen) {
            expandedGroupPaths.removeAll { it == path || it.startsWith("$path/") }
            return
        }

        val siblings = expandedGroupPaths.filter { p ->
            val pParent = p.substringBeforeLast("/", missingDelimiterValue = "")
            pParent == parent && p != path
        }
        for (sib in siblings) {
            expandedGroupPaths.removeAll { it == sib || it.startsWith("$sib/") }
        }

        expandedGroupPaths.add(path)
    }

    private fun refreshTabUi() {
        tabDrinksBtn.alpha = if (activeTab == Tab.DRINKS) 1f else 0.6f
        tabFoodsBtn.alpha = if (activeTab == Tab.FOODS) 1f else 0.6f
    }

    // ========================= DRINKS TREE =========================

    private fun isDrinkLeaf(node: DataSnapshot): Boolean {
        val hasName = node.child("name").exists()
        val hasPrice = node.child("price").exists()
        val hasSizes = node.child("sizes").exists()
        return hasName && (hasPrice || hasSizes)
    }

    private fun buildDrinkItemFromLeaf(leaf: DataSnapshot): MenuItem? {
        val id = leaf.key ?: return null
        val name = leaf.child("name").getValue(String::class.java) ?: return null

        // 1) wenn "sizes" existiert -> parse sizes
        val sizesSnap = leaf.child("sizes")
        if (sizesSnap.exists()) {
            val sizes = mutableListOf<MenuSize>()
            for (s in sizesSnap.children) {
                val label = s.child("label").getValue(String::class.java) ?: continue
                val price = s.child("price").getValue(Double::class.java) ?: 0.0
                sizes.add(MenuSize(label = label, price = price))
            }
            if (sizes.isNotEmpty()) {
                sizes.sortBy { it.price }
                val minPrice = sizes.minOf { it.price }
                return MenuItem(id = id, name = name, price = minPrice, sizes = sizes)
            }
        }

        // 2) sonst normaler Preis
        val price = leaf.child("price").getValue(Double::class.java) ?: 0.0
        return MenuItem(id = id, name = name, price = price, sizes = emptyList())
    }

    private fun sortedChildrenForPath(path: String, children: List<DataSnapshot>): List<DataSnapshot> {
        fun byOrder(list: List<String>, k: String): Int {
            val idx = list.indexOf(k)
            return if (idx >= 0) idx else 999
        }

        return when (path) {
            "drinks" -> children.sortedBy { byOrder(drinksOrder, it.key ?: "") }

            "drinks/alkoholfrei" -> {
                val order = listOf("wasser", "softdrinks", "saefte", "saftschorlen", "erfrischungsgetraenk", "erfrischungsgetraenke")
                children.sortedBy { byOrder(order, it.key ?: "") }
            }

            "drinks/wein_sekt" -> {
                val order = listOf("sekt", "wein_rot", "wein_weiss", "weinschorle_rot", "weinschorle_weiss")
                children.sortedBy { byOrder(order, it.key ?: "") }
            }

            "drinks/longdrinks_cocktail" -> {
                val order = listOf("longdrinks", "cocktails")
                children.sortedBy { byOrder(order, it.key ?: "") }
            }

            "drinks/heissgetraenke" -> {
                val order = listOf("kaffee", "tee", "kaffeespezialitaeten")
                children.sortedBy { byOrder(order, it.key ?: "") }
            }

            // Bier: oft drinks/bier/bier -> items feste Reihenfolge
            "drinks/bier/bier" -> {
                val order = listOf(
                    "krombacher_pils",
                    "krombacher_radler",
                    "krombacher_diesel",
                    "koestritzer_dunkel",
                    "hefeweizen"
                )
                children.sortedBy { byOrder(order, it.key ?: "") }
            }

            // Alkoholfreie Biere: oft drinks/alkoholfreie_biere/alkoholfrei -> feste Reihenfolge
            "drinks/alkoholfreie_biere/alkoholfrei" -> {
                val order = listOf("krombacher_0_0", "hefeweizen_0_0")
                children.sortedBy { byOrder(order, it.key ?: "") }
            }

            else -> children.sortedWith(compareBy { it.key ?: "" })
        }
    }

    private fun fillDrinkTree(nodeSnap: DataSnapshot, target: DrinkTreeNode, path: String) {
        val children = nodeSnap.children.toList()
        val sorted = sortedChildrenForPath(path, children)

        for (child in sorted) {
            if (isDrinkLeaf(child)) {
                val item = buildDrinkItemFromLeaf(child)
                if (item != null) target.items.add(item)
            } else {
                val key = child.key ?: continue
                val title = pretty(key)
                val childNode = target.children.getOrPut(key) { DrinkTreeNode(title = title) }
                fillDrinkTree(child, childNode, "$path/$key")
            }
        }

        if (path != "drinks/bier/bier" && path != "drinks/alkoholfreie_biere/alkoholfrei") {
            target.items.sortWith(compareBy(collator) { it.name })
        }
    }

    private fun addDrinkNodeRows(node: DrinkTreeNode, depth: Int, parentPath: String) {
        for ((childKey, childNode) in node.children) {
            val path = "$parentPath/$childKey"
            val indent = "   ".repeat(depth)
            menuRows.add(MenuRow.SubCategoryRow(key = path, title = indent + childNode.title))

            if (expandedGroupPaths.contains(path)) {
                addDrinkNodeRows(childNode, depth + 1, path)
                for (it in childNode.items) menuRows.add(MenuRow.ItemRow(it))
            }
        }
    }

    // ========================= FOODS =========================

    private fun isFoodItemNode(node: DataSnapshot): Boolean {
        return node.child("name").exists() && node.child("price").exists()
    }

    private fun collectFoodItemsRecursive(node: DataSnapshot): List<MenuItem> {
        val out = mutableListOf<MenuItem>()
        for (child in node.children) {
            if (isFoodItemNode(child)) {
                val id = child.key ?: continue
                val name = child.child("name").getValue(String::class.java) ?: continue
                val price = child.child("price").getValue(Double::class.java) ?: 0.0
                out.add(MenuItem(id = id, name = name, price = price, sizes = emptyList()))
            } else {
                out.addAll(collectFoodItemsRecursive(child))
            }
        }
        return out.sortedWith(compareBy(collator) { it.name })
    }

    // ========================= MENU laden =========================

    private fun observeMenuFromFirebase() {
        val menuRef = db.child("menu")

        menuRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                drinksTree.clear()
                foodsData.clear()
                foodSubData.clear()

                // DRINKS
                val drinksSnap = snapshot.child("drinks")
                val drinkCats = drinksSnap.children.toList().sortedBy { s ->
                    val k = s.key ?: ""
                    val idx = drinksOrder.indexOf(k)
                    if (idx >= 0) idx else 999
                }

                for (cat in drinkCats) {
                    val key = cat.key ?: continue
                    val title = pretty(key)
                    val root = DrinkTreeNode(title = title)
                    fillDrinkTree(cat, root, "drinks/$key")
                    drinksTree[key] = root
                }

                // FOODS
                val foodsSnap = snapshot.child("foods")
                val foodCats = foodsSnap.children.toList().sortedBy { s ->
                    val k = s.key ?: ""
                    val idx = foodsOrder.indexOf(k)
                    if (idx >= 0) idx else 999
                }

                for (cat in foodCats) {
                    val key = cat.key ?: continue
                    val title = pretty(key)

                    val directItems = cat.children.any { isFoodItemNode(it) }
                    val groups = cat.children.filter { !isFoodItemNode(it) }.toList()

                    if (groups.isNotEmpty() && !directItems) {
                        val subMap = linkedMapOf<String, Pair<String, List<MenuItem>>>()

                        val sortedGroups = if (key == "hauptspeisen") {
                            groups.sortedBy { g ->
                                val gk = g.key ?: ""
                                val idx = mainsOrder.indexOf(gk)
                                if (idx >= 0) idx else 999
                            }
                        } else {
                            groups.sortedWith(compareBy(collator) { it.key ?: "" })
                        }

                        for (g in sortedGroups) {
                            val subKey = g.key ?: continue
                            val subTitle = pretty(subKey)
                            val items = collectFoodItemsRecursive(g)
                            subMap[subKey] = subTitle to items
                        }

                        foodSubData[key] = LinkedHashMap(subMap)
                        foodsData[key] = title to emptyList()
                    } else {
                        val items = collectFoodItemsRecursive(cat)
                        foodsData[key] = title to items
                    }
                }

                rebuildMenuRows()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OrderActivity, "Menü Fehler: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    // ========================= Accordion Aufbau =========================

    private fun rebuildMenuRows() {
        menuRows.clear()

        if (activeTab == Tab.DRINKS) {
            for ((key, root) in drinksTree) {
                menuRows.add(MenuRow.CategoryRow(key = key, title = root.title))

                if (expandedCategoryKey == key) {
                    addDrinkNodeRows(node = root, depth = 1, parentPath = key)
                    for (it in root.items) menuRows.add(MenuRow.ItemRow(it))
                }
            }
        } else {
            for ((key, pair) in foodsData) {
                val (title, items) = pair
                menuRows.add(MenuRow.CategoryRow(key = key, title = title))

                if (expandedCategoryKey == key) {
                    val subCats = foodSubData[key]
                    if (subCats != null && subCats.isNotEmpty()) {
                        for ((subKey, subPair) in subCats) {
                            val (subTitle, subItems) = subPair
                            menuRows.add(MenuRow.SubCategoryRow(key = subKey, title = subTitle))

                            if (expandedMainSubKey == subKey) {
                                for (it in subItems) menuRows.add(MenuRow.ItemRow(it))
                            }
                        }
                    } else {
                        for (it in items) menuRows.add(MenuRow.ItemRow(it))
                    }
                }
            }
        }

        menuRecyclerAdapter.update(menuRows, expandedCategoryKey, expandedMainSubKey)
    }

    // ========================= Umlaute + schöne Titel =========================

    private fun applyUmlauts(s: String): String {
        return s
            .replace("Ae", "Ä").replace("Oe", "Ö").replace("Ue", "Ü")
            .replace("ae", "ä").replace("oe", "ö").replace("ue", "ü")
    }

    private fun titleCaseGerman(s: String): String {
        val words = s.trim().split(Regex("\\s+"))
        return words.joinToString(" ") { w ->
            if (w.isBlank()) "" else w.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.GERMANY) else ch.toString()
            }
        }
    }

    private fun pretty(key: String): String {
        return when (key) {
            // Drinks top
            "alkoholfrei" -> "Alkoholfrei"
            "bier" -> "Bier"
            "alkoholfreie_biere" -> "Alkoholfreie Biere"
            "wein_sekt" -> "Wein & Sekt"
            "longdrinks_cocktail" -> "Longdrinks & Cocktails"
            "schnaps" -> "Spirituosen"
            "heissgetraenke" -> "Heißgetränke"

            // Unterordner
            "wasser" -> "Wasser"
            "softdrinks" -> "Softdrinks"
            "saefte" -> "Säfte"
            "saftschorlen" -> "Saftschorlen"
            "erfrischungsgetraenk" -> "Erfrischungsgetränke"
            "erfrischungsgetraenke" -> "Erfrischungsgetränke"

            "sekt" -> "Sekt"
            "wein_rot" -> "Rotwein"
            "wein_weiss" -> "Weißwein"
            "weinschorle_rot" -> "Rotweinschorle"
            "weinschorle_weiss" -> "Weißweinschorle"

            "cocktails" -> "Cocktails"
            "longdrinks" -> "Longdrinks"

            "kaffee" -> "Kaffeespezialitäten"
            "kaffeespezialitaeten" -> "Kaffeespezialitäten"
            "tee" -> "Tee"

            else -> {
                val base = key.replace("_", " ")
                val withUmlauts = applyUmlauts(base)
                titleCaseGerman(withUmlauts)
            }
        }
    }

    // ========================= Größen-Dialog =========================

    private fun showDrinkSizeDialog(item: MenuItem) {
        val sizes = item.sizes.sortedBy { it.price }
        if (sizes.isEmpty()) return

        val display = sizes.map { sz ->
            val p = String.format(Locale.GERMANY, "%.2f", sz.price)
            "${sz.label}  –  $p €"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(display) { _, which ->
                val chosen = sizes[which]
                addItemToOrder(item, sizeLabel = chosen.label, sizePrice = chosen.price)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ========================= ORDER =========================

    private fun observeOrder() {
        orderItemsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                orderCache.clear()

                for (itemSnap in snapshot.children) {
                    val itemId = itemSnap.key ?: continue
                    val name = itemSnap.child("name").getValue(String::class.java) ?: itemId
                    val price = itemSnap.child("price").getValue(Double::class.java) ?: 0.0
                    val qty = itemSnap.child("qty").getValue(Int::class.java) ?: 1
                    val size = itemSnap.child("size").getValue(String::class.java) ?: ""
                    orderCache[itemId] = OrderItem(itemId, name, size, price, qty)
                }

                orderAdapter.update(orderCache.values.toList())
                updateTotal()
                tableOccupiedRef.setValue(orderCache.isNotEmpty())
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OrderActivity, "Order Fehler: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun updateTotal() {
        var sum = 0.0
        for (it in orderCache.values) sum += it.price * it.qty
        totalTxt.text = "Summe: ${String.format(Locale.GERMANY, "%.2f", sum)} €"
    }

    private fun addItemToOrder(item: MenuItem, sizeLabel: String = "", sizePrice: Double? = null) {
        val now = ServerValue.TIMESTAMP

        orderMetaRef.child("createdAt").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) orderMetaRef.child("createdAt").setValue(now)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        orderMetaRef.child("updatedAt").setValue(now)

        val dept = if (activeTab == Tab.DRINKS) "BAR" else "KITCHEN"
        val priceToUse = sizePrice ?: item.price

        val key = if (sizeLabel.isNotBlank()) "${item.id}__${sizeLabel}" else item.id

        val existing = orderCache[key]
        if (existing == null) {
            val newData = mapOf(
                "name" to item.name,
                "price" to priceToUse,
                "qty" to 1,
                "dept" to dept,
                "size" to sizeLabel,
                "printed" to false
            )
            orderItemsRef.child(key).setValue(newData)
        } else {
            orderItemsRef.child(key).child("qty").setValue(existing.qty + 1)
        }
    }

    // ===================== BONIEREN (Variante 3 – Design) =====================

    private fun confirmPrint() {
        AlertDialog.Builder(this)
            .setTitle("Bonieren")
            .setMessage("Ungedruckte Positionen jetzt an Theke/Küche senden?")
            .setPositiveButton("JA") { _, _ -> printUnprintedItems() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun printUnprintedItems() {
        if (BAR_PRINTER_IP.isBlank() && KITCHEN_PRINTER_IP.isBlank()) {
            Toast.makeText(this, "Drucker-IP fehlt (BAR/KITCHEN).", Toast.LENGTH_LONG).show()
            return
        }

        orderItemsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                val bar = mutableListOf<PrintLine>()
                val kitchen = mutableListOf<PrintLine>()
                val toMarkPrinted = mutableListOf<String>()

                for (itemSnap in snapshot.children) {
                    val printed = itemSnap.child("printed").getValue(Boolean::class.java) ?: false
                    if (printed) continue

                    val key = itemSnap.key ?: continue
                    val name = itemSnap.child("name").getValue(String::class.java) ?: key
                    val qty = itemSnap.child("qty").getValue(Int::class.java) ?: 1
                    val price = itemSnap.child("price").getValue(Double::class.java) ?: 0.0
                    val dept = itemSnap.child("dept").getValue(String::class.java) ?: "BAR"
                    val size = itemSnap.child("size").getValue(String::class.java) ?: ""

                    val line = PrintLine(name = name, size = size, qty = qty, price = price, key = key)

                    if (dept == "KITCHEN") kitchen.add(line) else bar.add(line)
                    toMarkPrinted.add(key)
                }

                if (bar.isEmpty() && kitchen.isEmpty()) {
                    Toast.makeText(this@OrderActivity, "Keine neuen Positionen zum Drucken.", Toast.LENGTH_SHORT).show()
                    return
                }

                val ts = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())

                // ✅ Header: Tisch groß/fett, Raum zentriert, Strich, Datum/Uhrzeit
                val header =
                    "\u001B!\u0018" +
                            centerLine("TISCH $tableId") + "\n" +
                            "\u001B!\u0000" +
                            centerLine(roomName) + "\n" +
                            "------------------------------\n" +
                            "$ts\n\n"

                Thread {
                    var ok = true

                    if (bar.isNotEmpty()) {
                        val text = header +
                                ">>> THEKE / GETRÄNKE <<<\n\n" +
                                buildReceiptText(bar)
                        ok = ok && sendToPrinterTcp(BAR_PRINTER_IP, PRINTER_PORT, text)
                    }

                    if (kitchen.isNotEmpty()) {
                        val text = header +
                                ">>> KÜCHE / SPEISEN <<<\n\n" +
                                buildReceiptText(kitchen)
                        ok = ok && sendToPrinterTcp(KITCHEN_PRINTER_IP, PRINTER_PORT, text)
                    }

                    runOnUiThread {
                        if (!ok) {
                            Toast.makeText(this@OrderActivity, "Druck fehlgeschlagen (IP/Netz/Port prüfen).", Toast.LENGTH_LONG).show()
                        } else {
                            val updates = hashMapOf<String, Any>()
                            for (k in toMarkPrinted) updates["$k/printed"] = true
                            orderItemsRef.updateChildren(updates)
                            Toast.makeText(this@OrderActivity, "Bon gesendet ✅", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OrderActivity, "Print Fehler: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private data class PrintLine(
        val name: String,
        val size: String,
        val qty: Int,
        val price: Double,
        val key: String
    )

    // ✅ Bestellung größer + Format: "Wasser still (0,2l)  3x"
    private fun buildReceiptText(lines: List<PrintLine>): String {
        val sb = StringBuilder()

        // große Schrift für Bestellung (ESC ! 0x18)
        sb.append("\u001B!\u0018")

        for (l in lines) {
            val sizePart = if (l.size.isNotBlank()) " (${l.size})" else ""
            sb.append("${l.name}$sizePart  ${l.qty}x\n")
        }

        // zurück zu normal
        sb.append("\u001B!\u0000\n")
        sb.append("\n\n\n")
        return sb.toString()
    }

    // Zentriert Text auf 30 Zeichen (Standard 58mm)
    private fun centerLine(text: String, width: Int = 30): String {
        val t = text.trim()
        if (t.length >= width) return t
        val pad = (width - t.length) / 2
        return " ".repeat(pad) + t
    }

    private fun sendToPrinterTcp(ip: String, port: Int, text: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 1500)
                val out: OutputStream = socket.getOutputStream()
                out.write(text.toByteArray(Charsets.UTF_8))
                // Cut (ESC/POS) – kann je nach Brother Modell ignoriert werden
                out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x10))
                out.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ===================== PAY =====================

    private fun confirmPayAndClose() {
        if (orderCache.isEmpty()) {
            Toast.makeText(this, "Keine Bestellung vorhanden.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Bezahlen")
            .setMessage("Bestellung für Tisch $tableId abschließen und Tisch leeren?")
            .setPositiveButton("JA") { _, _ -> archiveAndClearOrder() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun archiveAndClearOrder() {
        val now = ServerValue.TIMESTAMP
        val historyId = historyRef.push().key ?: return

        val itemsMap = hashMapOf<String, Any>()
        for ((id, it) in orderCache) {
            itemsMap[id] = mapOf(
                "name" to it.name,
                "price" to it.price,
                "qty" to it.qty,
                "size" to it.size
            )
        }

        val historyData = mapOf(
            "paidAt" to now,
            "items" to itemsMap
        )

        historyRef.child(historyId).setValue(historyData)
            .addOnSuccessListener {
                currentOrderRef.removeValue()
                    .addOnSuccessListener {
                        tableOccupiedRef.setValue(false)
                        Toast.makeText(this, "Bestellung abgeschlossen ✅", Toast.LENGTH_SHORT).show()
                        finish()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Archivieren fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}
