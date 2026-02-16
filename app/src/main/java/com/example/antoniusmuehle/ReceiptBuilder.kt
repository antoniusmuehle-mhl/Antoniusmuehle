package com.example.antoniusmuehle

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReceiptData(
    val titleCenter: String,
    val roomCenter: String,
    val sectionTitle: String,
    val dateTimeLine: String,
    val lines: List<String>,
    val items: List<ReceiptBuilder.LineItem>,
    val ticketHint: String = ""
)

object ReceiptBuilder {

    data class LineItem(
        val name: String,
        val size: String = "",
        val qty: Int,
        val note: String = "" // ✅ NEU: Wunsch/Zusatz direkt unter Artikel
    )

    fun build(
        tableId: String,
        roomName: String,
        sectionTitle: String,
        items: List<LineItem>,
        ticketHint: String = ""
    ): ReceiptData {

        val ts = SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.GERMANY).format(Date())

        val a4Lines = items.map { li ->
            formatQtyNameLineA4(li.qty, li.name, li.size)
        }

        return ReceiptData(
            titleCenter = "Tisch $tableId",
            roomCenter = roomName,
            sectionTitle = sectionTitle,
            dateTimeLine = ts,
            lines = a4Lines,
            items = items,
            ticketHint = ticketHint
        )
    }

    private fun formatQtyNameLineA4(qty: Int, name: String, size: String): String {
        val qtyStr = "${qty}x"
        val qtyWidth = 5
        val left = qtyStr.padEnd(qtyWidth, ' ')
        val fullName = if (size.isNotBlank()) "$name ($size)" else name
        return left + fullName
    }

    fun toEscPosText(data: ReceiptData): String {

        val lineWidth = 42
        val qtyWidth = 5
        val nameWidth = lineWidth - qtyWidth

        val sb = StringBuilder()

        fun esc(cmd: ByteArray) = sb.append(String(cmd, Charsets.ISO_8859_1))

        fun bold(on: Boolean) {
            esc(byteArrayOf(0x1B, 0x45, if (on) 0x01 else 0x00))
        }

        fun doubleSize(on: Boolean) {
            esc(byteArrayOf(0x1D, 0x21, if (on) 0x11 else 0x00))
        }

        fun tripleHeight(on: Boolean) {
            esc(byteArrayOf(0x1D, 0x21, if (on) 0x02 else 0x00))
        }

        fun invert(on: Boolean) {
            esc(byteArrayOf(0x1D, 0x42, if (on) 0x01 else 0x00))
        }

        fun alignCenter() = esc(byteArrayOf(0x1B, 0x61, 0x01))
        fun alignLeft() = esc(byteArrayOf(0x1B, 0x61, 0x00))

        fun cut() {
            esc(byteArrayOf(0x1D, 0x56, 0x41, 0x10))
        }

        fun line(): String = "-".repeat(lineWidth) + "\n"

        fun centerLine(text: String): String {
            val t = text.trim()
            val pad = ((lineWidth - t.length) / 2).coerceAtLeast(0)
            return " ".repeat(pad) + t + "\n"
        }

        fun wrapText(text: String, maxWidth: Int): List<String> {
            val words = text.trim().split(Regex("\\s+"))
            val out = mutableListOf<String>()
            var current = ""
            for (w in words) {
                val trial = if (current.isEmpty()) w else "$current $w"
                if (trial.length <= maxWidth) current = trial
                else {
                    if (current.isNotBlank()) out.add(current)
                    current = w
                }
            }
            if (current.isNotBlank()) out.add(current)
            return out
        }

        fun wrapItemWithNote(li: LineItem): String {
            val fullName = buildString {
                append(li.name)
                if (li.size.isNotBlank()) append(" (${li.size})")
            }

            val qtyStr = "${li.qty}x".padEnd(qtyWidth, ' ')
            val indent = " ".repeat(qtyWidth)

            val nameLines = wrapText(fullName, nameWidth)
            val out = StringBuilder()

            // 1) Artikel (Name) – Menge nur in erster Zeile
            if (nameLines.isNotEmpty()) {
                out.append(qtyStr).append(nameLines[0]).append("\n")
                for (i in 1 until nameLines.size) {
                    out.append(indent).append(nameLines[i]).append("\n")
                }
            } else {
                out.append(qtyStr).append("\n")
            }

            // 2) Note direkt darunter (eingezogen)
            if (li.note.isNotBlank()) {
                val notePrefix = "» "
                val noteLines = wrapText(li.note.trim(), nameWidth - notePrefix.length)
                for (n in noteLines) {
                    out.append(indent).append(notePrefix).append(n).append("\n")
                }
            }

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

        if (data.ticketHint.isNotBlank()) {
            bold(true)
            sb.append(centerLine("*** ${data.ticketHint.uppercase()} ***"))
            bold(false)
        }

        sb.append(line())

        alignLeft()
        sb.append(data.dateTimeLine).append("\n\n")

        val newItems = mutableListOf<LineItem>()
        val stornoItems = mutableListOf<LineItem>()

        for (it in data.items) {
            val isStorno = it.name.trim().startsWith("STORNO:", ignoreCase = true)
            if (isStorno) stornoItems.add(it) else newItems.add(it)
        }

        if (newItems.isNotEmpty()) {
            alignCenter()
            sb.append(centerLine("NEU"))
            alignLeft()
            sb.append(line())

            bold(true)
            tripleHeight(true)
            for (it in newItems) {
                sb.append(wrapItemWithNote(it))
                // Note soll NICHT 3x hoch sein → danach kurz normal
                if (it.note.isNotBlank()) {
                    tripleHeight(false)
                    sb.append("") // nur Reset
                    tripleHeight(true)
                }
            }
            tripleHeight(false)
            bold(false)
            sb.append("\n")
        }

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
            tripleHeight(true)
            for (it in stornoItems) {
                sb.append(wrapItemWithNote(it))
                if (it.note.isNotBlank()) {
                    tripleHeight(false)
                    sb.append("")
                    tripleHeight(true)
                }
            }
            tripleHeight(false)
            bold(false)
            invert(false)
            sb.append("\n")
        }

        sb.append("\n\n")
        cut()

        return sb.toString()
    }
}
// Stand: 16.02.2026 - 21:34