package com.binaa.ocr.goldencorpus

import android.graphics.Bitmap
import com.binaa.ocr.header.Box
import com.binaa.ocr.header.HeaderDetector
import com.binaa.ocr.header.PixelImage
import org.json.JSONArray
import org.json.JSONObject

/**
 * قياس الكاشف على لقطات الـCorpus (نسخة debug فقط، قبل 5b): يشغّل HeaderDetector كما هو ويسجّل الهندسة والرموز فقط.
 * لا OCR، ولا نص، ولا صورة اسم: أرقام البكسل ورموز ثابتة، فلا يوجد ما يُقنَّع.
 */
object HeaderMeasurement {

    const val KEY = "headerGeometry"

    fun measure(caseId: String, bitmap: Bitmap): JSONObject {
        val out = JSONObject().put("caseId", caseId)
        return try {
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val d = HeaderDetector.detect(PixelImage(bitmap.width, bitmap.height, argb))
            out.put("headerFound", d.headerFound)
            out.put("rejection", d.rejection?.name ?: JSONObject.NULL)
            d.layout?.let { l ->
                out.put("direction", l.direction.name)
                out.put("iconHeight", l.iconHeight)
                out.put("nameToIconsGap", l.nameToIconsGap ?: JSONObject.NULL)
                out.put("headerBar", box(l.headerBar))
                out.put("arrow", box(l.arrow))
                out.put("iconCluster", box(l.iconCluster))
                out.put("name", box(l.name))
                out.put("subtitle", box(l.subtitle))
            }
            out
        } catch (e: Exception) {
            // نوع الاستثناء فقط.
            out.put("error", e::class.java.simpleName)
        }
    }

    private fun box(b: Box?): Any = b?.let { JSONObject().put("l", it.l).put("t", it.t).put("r", it.r).put("b", it.b) } ?: JSONObject.NULL

    /** يضيف قسم الهندسة (مرتّبًا حسب caseId) إلى تقرير JSON كما هو، دون إعادة كتابة باقي التقرير. */
    fun appendTo(reportJson: String, entries: List<JSONObject>): String {
        val sorted = JSONArray(entries.sortedBy { it.getString("caseId") })
        val end = reportJson.lastIndexOf('}')
        require(end > 0) { "report is not a JSON object" }
        return reportJson.substring(0, end).trimEnd() + ",\n    \"$KEY\": " + sorted.toString(4).replace("\n", "\n    ") + "\n}"
    }
}
