package com.example.antoniusmuehle

// ===================== MENU MODELLE =====================

data class MenuItem(
    val id: String,
    val name: String,
    val price: Double = 0.0,
    val sizes: List<MenuSize> = emptyList()
)

data class MenuSize(
    val label: String,
    val price: Double
)

// ===================== ORDER MODEL =====================

data class OrderItem(
    val itemId: String,
    val name: String,
    val size: String,
    val price: Double,
    val qty: Int
)

// ===================== RECYCLER ROWS (ACCORDION) =====================

sealed class MenuRow {

    data class CategoryRow(
        val key: String,
        val title: String
    ) : MenuRow()

    data class SubCategoryRow(
        val key: String,
        val title: String
    ) : MenuRow()

    data class ItemRow(
        val item: MenuItem
    ) : MenuRow()
}
