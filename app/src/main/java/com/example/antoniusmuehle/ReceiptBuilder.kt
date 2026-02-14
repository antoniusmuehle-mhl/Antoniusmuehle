package com.example.antoniusmuehle

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ✅ Wird von A4PrintHelper (receipt.lines) UND Bondruck (receipt.items) genutzt
data class ReceiptData(
    val titleCenter: String,          // z.B. "Tisch 7"
    val roomCenter: String,           // z.B. "Saal"
    val sectionTitle: String,         // z.B. "THEKE / GETRÄNKE"
    val dateTimeLine: String,         // z.B. "13.02.2026  01:23"
    val lines: List<String>,          // ✅ für A4PrintHelper
    val items: List<ReceiptBuilder.LineItem> // ✅ für ESC/POS Bon
)

object ReceiptBuilder {

    data class LineItem(
        val name: String,
        val size: String = "",
        val qty: Int
    )

    // ========================= BUILD (für A4 + ESC/POS) =========================

    fun build(
        tableId: String,
        roomName: String,
        sectionTitle: String,
        items: List<LineItem>
    ): ReceiptData {

        val ts = SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.GERMANY).format(Date())

        // ✅ A4: Menge links, dann "Tab", dann Text
        val a4Lines = items.map { li ->
            formatQtyNameLineA4(li.qty, li.name, li.size)
        }

        return ReceiptData(
            titleCenter = "Tisch $tableId",
            roomCenter = roomName,
            sectionTitle = sectionTitle,
            dateTimeLine = ts,
            lines = a4Lines,
            items = items
        )
    }

    // ✅ A4-Format: "3x    Wasser still (0,5l)"
    private fun formatQtyNameLineA4(qty: Int, name: String, size: String): String {
        val qtyStr = "${qty}x"
        val qtyWidth = 5 // "12x  " -> wirkt wie ein Tab
        val left = qtyStr.padEnd(qtyWidth, ' ')

        val fullName = if (size.isNotBlank()) "$name ($size)" else name
        return left + fullName
    }

    // ========================= ESC/POS (BON) =========================
    // Option 2:
    // - NEU: fett
    // - STORNO: invertiert + fett
    // - Überschrift + Linie für NEU/STORNO (wenn beides im selben Bon)

    fun toEscPosText(data: ReceiptData): String {

        // ✅ 80mm Bon: meist 42 Zeichen je Zeile (58mm = 32)
        val lineWidth = 42

        // ✅ Spaltenlayout: "12x  " = 5 Zeichen
        val qtyWidth = 5
        val nameWidth = lineWidth - qtyWidth

        val sb = StringBuilder()

        // ===== ESC/POS Helfer =====
        fun esc(cmd: ByteArray) = sb.append(String(cmd, Charsets.ISO_8859_1))

        fun bold(on: Boolean) {
            esc(byteArrayOf(0x1B, 0x45, if (on) 0x01 else 0x00)) // ESC E n
        }

        fun doubleSize(on: Boolean) {
            esc(byteArrayOf(0x1D, 0x21, if (on) 0x11 else 0x00)) // GS ! n
        }

        fun invert(on: Boolean) {
            esc(byteArrayOf(0x1D, 0x42, if (on) 0x01 else 0x00)) // GS B n
        }

        fun alignCenter() = esc(byteArrayOf(0x1B, 0x61, 0x01)) // ESC a 1
        fun alignLeft() = esc(byteArrayOf(0x1B, 0x61, 0x00))   // ESC a 0

        fun cut() {
            esc(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // GS V A n
        }

        fun line(): String = "-".repeat(lineWidth) + "\n"

        fun centerLine(text: String): String {
            val t = text.trim()
            val pad = ((lineWidth - t.length) / 2).coerceAtLeast(0)
            return " ".repeat(pad) + t + "\n"
        }

        // ✅ Wrap: Menge links nur erste Zeile, Folgezeilen eingerückt
        fun wrapItemLine(qty: Int, name: String, size: String): String {
            val fullName = buildString {
                append(name)
                if (size.isNotBlank()) append(" ($size)")
            }

            val qtyStr = "${qty}x".padEnd(qtyWidth, ' ')
            val indent = " ".repeat(qtyWidth)

            val words = fullName.split(Regex("\\s+"))
            val out = StringBuilder()

            var current = ""
            var first = true

            fun flush(lineText: String) {
                if (first) {
                    out.append(qtyStr).append(lineText).append("\n")
                    first = false
                } else {
                    out.append(indent).append(lineText).append("\n")
                }
            }

            for (w in words) {
                val trial = if (current.isEmpty()) w else "$current $w"
                if (trial.length <= nameWidth) {
                    current = trial
                } else {
                    if (current.isNotBlank()) flush(current)
                    current = w
                }
            }
            if (current.isNotBlank()) flush(current)

            return out.toString()
        }

        // ===== HEADER =====
        alignCenter()
        bold(true)
        doubleSize(true)
        sb.append(data.titleCenter.uppercase()).append("\n")
        doubleSize(false)
        bold(false)

        sb.append(data.roomCenter).append("\n")
        sb.append(data.sectionTitle).append("\n")
        sb.append(line())

        alignLeft()
        sb.append(data.dateTimeLine).append("\n\n")

        // ===== ITEMS: NEU vs STORNO trennen =====
        val newItems = mutableListOf<LineItem>()
        val stornoItems = mutableListOf<LineItem>()

        for (it in data.items) {
            val isStorno = it.name.trim().startsWith("STORNO:", ignoreCase = true)
            if (isStorno) stornoItems.add(it) else newItems.add(it)
        }

        // ✅ NEU: fett
        if (newItems.isNotEmpty()) {
            alignCenter()
            sb.append(centerLine("NEU"))
            alignLeft()
            sb.append(line())

            bold(true)
            for (it in newItems) {
                sb.append(wrapItemLine(it.qty, it.name, it.size))
            }
            bold(false)
            sb.append("\n")
        }

        // ✅ STORNO: invertiert + fett
        if (stornoItems.isNotEmpty()) {
            alignCenter()
            invert(true)
            bold(true)
            sb.append(centerLine("STORNO"))
            bold(false)
            invert(false)

            alignLeft()
            sb.append(line())

            invert(true)
            bold(true)
            for (it in stornoItems) {
                sb.append(wrapItemLine(it.qty, it.name, it.size))
            }
            bold(false)
            invert(false)
            sb.append("\n")
        }

        sb.append("\n\n")
        cut()

        return sb.toString()
    }
}
