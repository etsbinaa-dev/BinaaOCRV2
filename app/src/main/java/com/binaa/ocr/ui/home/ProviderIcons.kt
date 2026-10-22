package com.binaa.ocr.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * أيقونة كل تطبيق دفع كما هي على الهاتف (إن كان مثبّتًا): تُعرف من اسمه في الشاشة الرئيسية، فلا شعارات داخل التطبيق
 * ولا حاجة لاسم الحزمة. تطبيق غير مثبّت ⇒ لا أيقونة، والبطاقة تعرض شارته الملوّنة.
 */
object ProviderIcons {

    private val LABELS = mapOf(
        "BANKILY" to Regex("""(?i)bankily"""),
        "SEDAD" to Regex("""(?i)sedad"""),
        "BIMBANK" to Regex("""(?i)bimbank"""),
        "MASRVI" to Regex("""(?i)masrvi"""),
        "BCIPAY" to Regex("""(?i)bci\s?pay"""),
        "CLICK" to Regex("""(?i)^\s*click\s*$"""),
    )

    /** ثقيل نسبيًا (يقرأ قائمة التطبيقات): خارج الخيط الرئيسي، مرة واحدة. */
    fun load(context: Context, sizePx: Int): Map<String, ImageBitmap> = runCatching {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        buildMap {
            for ((provider, label) in LABELS) {
                val app = apps.firstOrNull { label.containsMatchIn(it.loadLabel(pm).toString()) } ?: continue
                runCatching { app.loadIcon(pm).toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()?.let { put(provider, it) }
            }
        }
    }.getOrDefault(emptyMap())
}
