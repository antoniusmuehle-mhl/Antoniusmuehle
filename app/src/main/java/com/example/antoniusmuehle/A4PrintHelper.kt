package com.example.antoniusmuehle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

object A4PrintHelper {

    fun printTwoPageReceipt(context: Context, jobName: String, page1: ReceiptData, page2: ReceiptData) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager

        val adapter = object : PrintDocumentAdapter() {

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(2)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onWriteCancelled()
                    return
                }

                val pdf = android.graphics.pdf.PdfDocument()

                // A4 @ 72dpi approx: 595 x 842
                val pageInfo1 = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val p1 = pdf.startPage(pageInfo1)
                drawReceiptA4(p1.canvas, page1)
                pdf.finishPage(p1)

                val pageInfo2 = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 2).create()
                val p2 = pdf.startPage(pageInfo2)
                drawReceiptA4(p2.canvas, page2)
                pdf.finishPage(p2)

                try {
                    val out = FileOutputStream(destination!!.fileDescriptor)
                    pdf.writeTo(out)
                    pdf.close()
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }

        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(jobName, adapter, attributes)
    }

    private fun drawReceiptA4(canvas: Canvas, receipt: ReceiptData) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var y = 80f
        val centerX = 595f / 2f

        // Tisch groß & fett zentriert
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = 44f
        canvas.drawText(receipt.titleCenter, centerX, y, paint)

        // Raum zentriert klein
        y += 42f
        paint.isFakeBoldText = false
        paint.textSize = 20f
        canvas.drawText(receipt.roomCenter, centerX, y, paint)

        // Abschnitt zentriert
        y += 28f
        paint.textSize = 18f
        canvas.drawText(receipt.sectionTitle, centerX, y, paint)

        // Linie
        y += 22f
        paint.strokeWidth = 2f
        canvas.drawLine(40f, y, 555f, y, paint)

        // Datum/Uhrzeit links
        y += 28f
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 16f
        canvas.drawText(receipt.dateTimeLine, 40f, y, paint)

        // Abstand
        y += 38f

        // ===== Positionen größer (2 Spalten: qty | name) =====
        paint.textSize = 24f
        paint.isFakeBoldText = true

        val xLeft = 40f
        val qtyColWidth = 70f  // <- "Tab"-Abstand (bei Bedarf 60..90 testen)
        val xQty = xLeft
        val xName = xLeft + qtyColWidth

        val maxNameWidth = 555f - xName // bis rechter Rand

        // ✅ WICHTIG: wir nutzen receipt.items, nicht receipt.lines
        for (it in receipt.items) {
            val qtyStr = "${it.qty}x"
            val nameStr = buildItemName(it)

            // Name kann umbrechen -> aber immer ab xName
            val nameParts = wrapLine(nameStr, paint, maxNameWidth)

            // 1. Zeile: qty + erster Teil
            canvas.drawText(qtyStr, xQty, y, paint)
            canvas.drawText(nameParts.firstOrNull() ?: "", xName, y, paint)
            y += 30f
            if (y > 800f) return

            // Folgezeilen: nur Name (eingezogen), qty leer
            if (nameParts.size > 1) {
                for (i in 1 until nameParts.size) {
                    canvas.drawText(nameParts[i], xName, y, paint)
                    y += 30f
                    if (y > 800f) return
                }
            }
        }
    }

    private fun buildItemName(it: ReceiptBuilder.LineItem): String {
        val sizePart = if (it.size.isNotBlank()) " (${it.size})" else ""
        return "${it.name}$sizePart"
    }

    private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""

        for (w in words) {
            val trial = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(trial) <= maxWidth) {
                current = trial
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = w
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }
}
