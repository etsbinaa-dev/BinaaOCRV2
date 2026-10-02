package com.binaa.ocr.header

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** لقطات اصطناعية تُرسم أثناء الاختبار (لا صور حقيقية): رأس Image Viewer بهندسة اختبارات الكاشف، واسم مكتوب بخط النظام. */
object SyntheticHeader {
    val DARK = 0xFF141A1E.toInt()
    val WHITE = 0xFFFEFEFE.toInt()

    fun viewer(name: String, textSize: Float = 56f): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2229, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(DARK) }
        val fill = Paint().apply { color = WHITE }
        fun rect(l: Int, t: Int, r: Int, b: Int) = canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fill)
        rect(49, 61, 96, 107)        // سهم الرجوع
        rect(718, 62, 770, 111)      // ☆
        rect(858, 64, 902, 105)      // ↪
        rect(146, 128, 400, 152)     // subtitle
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; this.textSize = textSize; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        canvas.drawText(name, 148f, 98f, text)
        return bitmap
    }

    fun blank(): Bitmap = Bitmap.createBitmap(1080, 2229, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
}
