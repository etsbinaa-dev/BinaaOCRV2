package com.binaa.ocr

import android.app.Application
import com.binaa.ocr.intake.IntakeStore
import kotlin.concurrent.thread

class BinaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // صور الاستلام المتروكة (مثلًا أُوقف التطبيق قبل الإغلاق) لا تبقى على الهاتف أكثر من يوم.
        thread(name = "intake-cleanup", isDaemon = true) {
            IntakeStore(filesDir, contentResolver).deleteExpired(System.currentTimeMillis())
        }
    }
}
