package com.binaa.ocr.accessprobe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * يعمل داخل تطبيق access-probe نفسه (حزمة وuid مستقلان، بلا أي صلاحية): هذا هو «التطبيق العادي».
 * يتطلب أن تكون نسخة debug (com.binaa.ocr.debug) مثبتة على الجهاز (scripts/ci-instrumented.sh).
 */
@RunWith(AndroidJUnit4::class)
class AccessProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun debugAppIsInstalled_andIsAnotherApp() {
        val target = context.packageManager.getApplicationInfo(TARGET, 0)
        assertNotEquals("the probe must run under its own uid", target.uid, context.applicationInfo.uid)
        assertEquals("the probe must not hold DUMP", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.DUMP))
    }

    @Test
    fun ordinaryApp_cannotStartCorpusActivity() {
        val refused = assertThrows(SecurityException::class.java) { start(CORPUS_ACTIVITY) }
        Log.i(TAG, "ACCESS_PROBE CorpusActivity REFUSED uid=${context.applicationInfo.uid}: ${refused.message}")
        assertTrue("refused for a reason other than DUMP: ${refused.message}", refused.message.orEmpty().contains(Manifest.permission.DUMP))
    }

    @Test
    fun ordinaryApp_canStartTheExportedMainActivity_control() {
        // نفس الطريقة على نشاط مُصدَّر غير محمي لا تُرفض: الرفض أعلاه سببه DUMP وحده.
        start(MAIN_ACTIVITY)
        Log.i(TAG, "ACCESS_PROBE MainActivity ALLOWED uid=${context.applicationInfo.uid}")
    }

    private fun start(activity: String) =
        context.startActivity(Intent().setClassName(TARGET, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private companion object {
        const val TAG = "AccessProbe"
        const val TARGET = "com.binaa.ocr.debug"
        const val CORPUS_ACTIVITY = "com.binaa.ocr.goldencorpus.CorpusActivity"
        const val MAIN_ACTIVITY = "com.binaa.ocr.MainActivity"
    }
}
