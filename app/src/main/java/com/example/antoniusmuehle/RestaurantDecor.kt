package com.example.antoniusmuehle

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Feste Objekte nur für Raum "Restaurant".
 * Koordinaten sind im selben Grid wie die Tische: x/y/w/h (Zellen).
 */
object RestaurantDecor {

    data class DecorRect(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val color: Int,
        val alpha: Float = 1f
    )

    data class DecorLabel(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val text: String
    )

    // Farben ähnlich deinem Bild
    private val BLUE = Color.parseColor("#2416B8") // kräftiges Blau/Violett

    // ✅ Hier definierst du die festen "Balken"
    fun getRects(): List<DecorRect> = listOf(
        // obere lange Wand
        DecorRect(x = 4, y = 4, w = 14, h = 1, color = BLUE),

        // kleiner "Stummel" oben (wie im Bild)
        DecorRect(x = 4, y = 0, w = 1, h = 1, color = BLUE),

        // mittlere lange Wand
        DecorRect(x = 0, y = 6, w = 16, h = 1, color = BLUE),

        // THEKE (L-Form): erst langer Balken, dann rechter Schenkel
        DecorRect(x = 3, y = 11, w = 9, h = 1, color = BLUE),
        DecorRect(x = 11, y = 12, w = 1, h = 2, color = BLUE)
    )

    fun getLabels(): List<DecorLabel> = listOf(
        DecorLabel(x = 3, y = 11, w = 9, h = 1, text = "THEKE")
    )

    /**
     * Fügt Dekor-Views unter die Tische in den Canvas ein.
     * cellPx/gapPx/originLeft/originTop kommen aus deiner TableActivity (Auto-Fit).
     */
    fun addToCanvas(
        context: Context,
        canvas: FrameLayout,
        cellPx: Int,
        gapPx: Int,
        originLeft: Int,
        originTop: Int
    ) {
        // Rectangles
        for (r in getRects()) {
            val v = View(context).apply {
                setBackgroundColor(r.color)
                alpha = r.alpha
                isClickable = false
                isFocusable = false
            }
            val lp = FrameLayout.LayoutParams(
                r.w * cellPx + (r.w - 1) * gapPx,
                r.h * cellPx + (r.h - 1) * gapPx
            ).apply {
                leftMargin = originLeft + r.x * (cellPx + gapPx)
                topMargin = originTop + r.y * (cellPx + gapPx)
            }
            canvas.addView(v, lp)
        }

        // Labels (Text)
        for (l in getLabels()) {
            val tv = TextView(context).apply {
                text = l.text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                letterSpacing = 0.2f
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
            }
            val lp = FrameLayout.LayoutParams(
                l.w * cellPx + (l.w - 1) * gapPx,
                l.h * cellPx + (l.h - 1) * gapPx
            ).apply {
                leftMargin = originLeft + l.x * (cellPx + gapPx)
                topMargin = originTop + l.y * (cellPx + gapPx)
            }
            canvas.addView(tv, lp)
        }
    }
}

