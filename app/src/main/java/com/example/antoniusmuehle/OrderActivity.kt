package com.example.antoniusmuehle

import android.content.Intent
import android.os.Bundle
import android.text.InputType
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
import java.util.Locale
import kotlin.math.abs

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
    private lateinit var splitBtn: Button

    private lateinit var currentOrderRef: DatabaseReference
    private lateinit var orderItemsRef: DatabaseReference
    private lateinit var orderMetaRef: DatabaseReference
    private lateinit var historyRef: DatabaseReference
    private lateinit var tableOccupiedRef: DatabaseReference

    private val orderCache = linkedMapOf<String, OrderItem>()
    private lateinit var orderAdapter: OrderAdapter

    private val menuRows = mutableListOf<MenuRow>()

    private data class DrinkTreeNode(
        val title: String,
        val children: LinkedHashMap<String, DrinkTreeNode> = linkedMapOf(),
        val items: MutableList<MenuItem> = mutableListOf()
    )

    private val drinksTree = linkedMapOf<String, DrinkTreeNode>()

    private val foodsData = linkedMapOf<String, Pair<String, List<MenuItem>>>()
    private val foodSubData = linkedMapOf<String, LinkedHashMap<String, Pair<String, List<MenuItem>>>>()

    private var expandedCategoryKey: String? = null
    private val expandedGroupPaths = mutableSetOf<String>()
    private var expandedMainSubKey: String? = null

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

    private val PRINT_MODE = PrintMode.A4_ANDROID_PRINT       // später ESC_POS_TCP
    private val BAR_PRINTER_IP = "192.168.178.126"
    private val KITCHEN_PRINTER_IP = "192.168.178.126"
    private val PRINTER_PORT = 9100

    private val collator: Collator = Collator.getInstance(Locale.GERMANY).apply {
        strength = Collator.PRIMARY
    }

    private companion object {
        private const val COURSE_STARTER = "STARTER"
        private const val COURSE_MAIN = "MAIN"
        private const val COURSE_DESSERT = "DESSERT"
    }

    private fun courseFromExpandedCategory(): String {
        return when (expandedCategoryKey) {
            "vorspeisen" -> COURSE_STARTER
            "suppen" -> COURSE_STARTER
            "nachspeisen" -> COURSE_DESSERT
            else -> COURSE_MAIN
        }
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
        splitBtn = findViewById(R.id.splitBtn)

        splitBtn.setOnClickListener {
            val i = Intent(this, SplitBillActivity::class.java)
            i.putExtra("ROOM_NAME", roomName)
            i.putExtra("TABLE_ID", tableId)
            startActivity(i)
        }

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

        // ====================== ORDER LIST (rechts) ======================
        orderAdapter = OrderAdapter(
            context = this,
            items = emptyList(),
            onPlus = { itOrder ->
                // ✅ qty + lastAddedAt aktualisieren => wandert nach unten
                val updates = hashMapOf<String, Any>(
                    "qty" to (itOrder.qty + 1),
                    "lastAddedAt" to ServerValue.TIMESTAMP
                )
                orderItemsRef.child(itOrder.itemId).updateChildren(updates)
            },
            onMinus = { itOrder ->
                if (itOrder.qty > 1) {
                    orderItemsRef.child(itOrder.itemId).child("qty").setValue(itOrder.qty - 1)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Artikel löschen?")
                        .setMessage("Willst du „${itOrder.name}“ wirklich entfernen?")
                        .setPositiveButton("LÖSCHEN") { _, _ ->
                            orderItemsRef.child(itOrder.itemId).child("qty").setValue(0)
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            },
            onLongPress = { itOrder ->
                showNoteDialog(itOrder)
            }
        )
        orderList.adapter = orderAdapter

        // ====================== MENU (links) ======================
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

        observeMenuFromFirebase()
        observeOrder()
    }

    // ✅ Zusatz/Wunsch Dialog (Text; Handschrift per Keyboard möglich)
    private fun showNoteDialog(orderItem: OrderItem) {
        val input = EditText(this).apply {
            setText(orderItem.note)
            hint = "z.B. mit Pommes, ohne Zwiebeln..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Wunsch zu: ${orderItem.name}")
            .setView(input)
            .setPositiveButton("SPEICHERN") { _, _ ->
                val text = input.text.toString().trim()
                orderItemsRef.child(orderItem.itemId).child("note").setValue(text)
            }
            .setNeutralButton("LÖSCHEN") { _, _ ->
                orderItemsRef.child(orderItem.itemId).child("note").setValue("")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

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
            "alkoholfrei" -> "Alkoholfrei"
            "bier" -> "Bier"
            "alkoholfreie_biere" -> "Alkoholfreie Biere"
            "wein_sekt" -> "Wein & Sekt"
            "longdrinks_cocktail" -> "Longdrinks & Cocktails"
            "schnaps" -> "Spirituosen"
            "heissgetraenke" -> "Heißgetränke"

            "wasser" -> "Wasser"
            "softdrinks" -> "Softdrinks"
            "saefte" -> "Säfte"
            "saftschorlen" -> "Saftschorlen"
            "erfrischungsgetraenk" -> "Erfrischungsgetränke"
            "erfrischungsgetraenke" -> "Erfrischungsgetränke"

            "sekt" -> "Sekt"
            "wein_rot" -> "Rotwein"
            "wein_weiss" -> "Weißwein"
            "x_wein_rose" -> "Roséwein"
            "weinschorle_rot" -> "Rotweinschorle"
            "weinschorle_weiss" -> "Weißweinschorle"
            "x_weinschorle_rose" -> "Roséweinschorle"

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

    private fun observeOrder() {
        orderItemsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                orderCache.clear()

                for (itemSnap in snapshot.children) {
                    val itemId = itemSnap.key ?: continue
                    val qty = itemSnap.child("qty").getValue(Int::class.java) ?: 0
                    if (qty <= 0) continue

                    val name = itemSnap.child("name").getValue(String::class.java) ?: itemId
                    val price = itemSnap.child("price").getValue(Double::class.java) ?: 0.0
                    val size = itemSnap.child("size").getValue(String::class.java) ?: ""
                    val note = itemSnap.child("note").getValue(String::class.java) ?: ""
                    val lastAddedAt = itemSnap.child("lastAddedAt").getValue(Long::class.java) ?: 0L

                    orderCache[itemId] = OrderItem(itemId, name, size, price, qty, note, lastAddedAt)
                }

                // ✅ Sortierung: zuletzt hinzugefügt steht ganz unten
                val sorted = orderCache.values.sortedWith(
                    compareBy<OrderItem> { it.lastAddedAt }.thenBy(collator) { it.name }
                )

                orderAdapter.update(sorted)
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
        val course = if (dept == "KITCHEN") courseFromExpandedCategory() else ""

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
                "course" to course,
                "note" to "",
                "orderedQty" to 0,
                "printedQty" to 0,
                "lastAddedAt" to now // ✅ NEU
            )
            orderItemsRef.child(key).setValue(newData)
        } else {
            val updates = hashMapOf<String, Any>(
                "qty" to (existing.qty + 1),
                "lastAddedAt" to now // ✅ NEU: bei erneutem Hinzufügen nach unten
            )
            if (dept == "KITCHEN" && course.isNotBlank()) {
                updates["course"] = course
            }
            orderItemsRef.child(key).updateChildren(updates)
        }
    }

    private fun confirmPrint() {
        AlertDialog.Builder(this)
            .setTitle("Bestellen")
            .setMessage("Änderungen jetzt an Theke/Küche senden?")
            .setPositiveButton("JA") { _, _ -> printOrderDeltasAndMarkOrdered() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun printOrderDeltasAndMarkOrdered() {
        orderItemsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                val barDelta = mutableListOf<ReceiptBuilder.LineItem>()

                val kitchenStarterDelta = mutableListOf<ReceiptBuilder.LineItem>()
                val kitchenMainDelta = mutableListOf<ReceiptBuilder.LineItem>()
                val kitchenDessertDelta = mutableListOf<ReceiptBuilder.LineItem>()

                val updates = hashMapOf<String, Any>()

                for (itemSnap in snapshot.children) {
                    val key = itemSnap.key ?: continue
                    val name = itemSnap.child("name").getValue(String::class.java) ?: key
                    val size = itemSnap.child("size").getValue(String::class.java) ?: ""
                    val note = itemSnap.child("note").getValue(String::class.java) ?: ""
                    val dept = itemSnap.child("dept").getValue(String::class.java) ?: "BAR"
                    val course = itemSnap.child("course").getValue(String::class.java) ?: COURSE_MAIN

                    val qty = itemSnap.child("qty").getValue(Int::class.java) ?: 0

                    val orderedQty = when {
                        itemSnap.child("orderedQty").exists() -> itemSnap.child("orderedQty").getValue(Int::class.java) ?: 0
                        itemSnap.child("printedQty").exists() -> itemSnap.child("printedQty").getValue(Int::class.java) ?: 0
                        else -> 0
                    }

                    val diff = qty - orderedQty
                    if (diff == 0) continue

                    val li = ReceiptBuilder.LineItem(
                        name = if (diff < 0) "STORNO: $name" else name,
                        size = size,
                        qty = abs(diff),
                        note = note
                    )

                    if (dept == "BAR") {
                        barDelta.add(li)
                    } else {
                        when (course) {
                            COURSE_STARTER -> kitchenStarterDelta.add(li)
                            COURSE_DESSERT -> kitchenDessertDelta.add(li)
                            else -> kitchenMainDelta.add(li)
                        }
                    }

                    updates["$key/orderedQty"] = qty
                    updates["$key/printedQty"] = qty
                }

                val hasAny =
                    barDelta.isNotEmpty() ||
                            kitchenStarterDelta.isNotEmpty() ||
                            kitchenMainDelta.isNotEmpty() ||
                            kitchenDessertDelta.isNotEmpty()

                if (!hasAny) {
                    Toast.makeText(this@OrderActivity, "Keine Änderungen seit der letzten Bestellung.", Toast.LENGTH_SHORT).show()
                    return
                }

                fun build(section: String, items: List<ReceiptBuilder.LineItem>, hint: String = ""): ReceiptData {
                    return ReceiptBuilder.build(
                        tableId = tableId,
                        roomName = roomName,
                        sectionTitle = section,
                        items = items,
                        ticketHint = hint
                    )
                }

                when (PRINT_MODE) {

                    PrintMode.A4_ANDROID_PRINT -> {

                        fun emptyReceipt(section: String) = build(section, emptyList())

                        if (barDelta.isNotEmpty()) {
                            val page1 = build("THEKE / GETRÄNKE", barDelta)
                            val page2 = emptyReceipt("KÜCHE / SPEISEN")
                            A4PrintHelper.printTwoPageReceipt(
                                context = this@OrderActivity,
                                jobName = "Bestellen THEKE Tisch $tableId",
                                page1 = page1,
                                page2 = page2
                            )
                        }

                        if (kitchenStarterDelta.isNotEmpty()) {
                            val page1 = emptyReceipt("THEKE / GETRÄNKE")
                            val page2 = build("KÜCHE / SPEISEN", kitchenStarterDelta, "VORSPEISENBON")
                            A4PrintHelper.printTwoPageReceipt(
                                context = this@OrderActivity,
                                jobName = "Bestellen VORSPEISEN Tisch $tableId",
                                page1 = page1,
                                page2 = page2
                            )
                        }

                        if (kitchenMainDelta.isNotEmpty()) {
                            val page1 = emptyReceipt("THEKE / GETRÄNKE")
                            val page2 = build("KÜCHE / SPEISEN", kitchenMainDelta, "SPEISENBON")
                            A4PrintHelper.printTwoPageReceipt(
                                context = this@OrderActivity,
                                jobName = "Bestellen SPEISEN Tisch $tableId",
                                page1 = page1,
                                page2 = page2
                            )
                        }

                        if (kitchenDessertDelta.isNotEmpty()) {
                            val page1 = emptyReceipt("THEKE / GETRÄNKE")
                            val page2 = build("KÜCHE / SPEISEN", kitchenDessertDelta, "NACHSPEISENBON")
                            A4PrintHelper.printTwoPageReceipt(
                                context = this@OrderActivity,
                                jobName = "Bestellen NACHSPEISEN Tisch $tableId",
                                page1 = page1,
                                page2 = page2
                            )
                        }

                        if (updates.isNotEmpty()) orderItemsRef.updateChildren(updates)

                        Toast.makeText(this@OrderActivity, "Bestellung gesendet (A4) ✅", Toast.LENGTH_SHORT).show()
                    }

                    PrintMode.ESC_POS_TCP -> {
                        if (BAR_PRINTER_IP.isBlank() && KITCHEN_PRINTER_IP.isBlank()) {
                            Toast.makeText(this@OrderActivity, "Drucker-IP fehlt (BAR/KITCHEN).", Toast.LENGTH_LONG).show()
                            return
                        }

                        Thread {
                            var ok = true

                            if (barDelta.isNotEmpty()) {
                                val text = ReceiptBuilder.toEscPosText(build("THEKE / GETRÄNKE", barDelta))
                                ok = ok && sendToPrinterTcp(BAR_PRINTER_IP, PRINTER_PORT, text)
                            }

                            if (kitchenStarterDelta.isNotEmpty()) {
                                val text = ReceiptBuilder.toEscPosText(
                                    build("KÜCHE / SPEISEN", kitchenStarterDelta, "VORSPEISENBON")
                                )
                                ok = ok && sendToPrinterTcp(KITCHEN_PRINTER_IP, PRINTER_PORT, text)
                            }

                            if (kitchenMainDelta.isNotEmpty()) {
                                val text = ReceiptBuilder.toEscPosText(
                                    build("KÜCHE / SPEISEN", kitchenMainDelta, "SPEISENBON")
                                )
                                ok = ok && sendToPrinterTcp(KITCHEN_PRINTER_IP, PRINTER_PORT, text)
                            }

                            if (kitchenDessertDelta.isNotEmpty()) {
                                val text = ReceiptBuilder.toEscPosText(
                                    build("KÜCHE / SPEISEN", kitchenDessertDelta, "NACHSPEISENBON")
                                )
                                ok = ok && sendToPrinterTcp(KITCHEN_PRINTER_IP, PRINTER_PORT, text)
                            }

                            runOnUiThread {
                                if (!ok) {
                                    Toast.makeText(this@OrderActivity, "Druck fehlgeschlagen (IP/Netz/Port prüfen).", Toast.LENGTH_LONG).show()
                                } else {
                                    if (updates.isNotEmpty()) orderItemsRef.updateChildren(updates)
                                    Toast.makeText(this@OrderActivity, "Bestellung gesendet ✅", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OrderActivity, "Bestellen Fehler: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun sendToPrinterTcp(ip: String, port: Int, text: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 1500)
                val out: OutputStream = socket.getOutputStream()
                out.write(text.toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

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
                "size" to it.size,
                "note" to it.note,
                "lastAddedAt" to it.lastAddedAt
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

// Stand: 16.02.2026 - 21:34