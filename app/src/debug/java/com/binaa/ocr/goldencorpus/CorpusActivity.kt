package com.binaa.ocr.goldencorpus

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * نسخة debug فقط. يُشغَّل من adb:
 *   adb shell am start -W -n com.binaa.ocr.debug/com.binaa.ocr.goldencorpus.CorpusActivity
 * يشغّل Golden Corpus في الخلفية (انظر [CorpusRun]) ويعرض سطر الأعداد فقط.
 */
class CorpusActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 18f; text = "Golden Corpus: running…" }
        setContentView(status)

        if (!running.compareAndSet(false, true)) {
            status.text = "Golden Corpus: already running"
            return
        }
        val context = applicationContext
        thread(name = "golden-corpus") {
            val message = try {
                val t = CorpusRun.run(CorpusRun.casesDir(context), CorpusRun.reportsDir(context)).totals
                "Golden Corpus done\ncases ${t.cases} · passed ${t.passed} · failed ${t.failed} · not run ${t.notRun}\n" +
                    "errors ${t.errors} · problems ${t.problems} · wrongFound ${t.wrongFound}"
            } catch (e: Exception) {
                // نوع الاستثناء فقط: رسالته قد تحمل مسارًا أو بيانات.
                Log.e(CorpusRun.TAG, "GOLDEN_CORPUS_FAILED ${e::class.java.simpleName}")
                "Golden Corpus failed: ${e::class.java.simpleName}"
            } finally {
                running.set(false)
            }
            runOnUiThread { if (!isDestroyed) status.text = message }
        }
    }

    private companion object {
        val running = AtomicBoolean(false)
    }
}
