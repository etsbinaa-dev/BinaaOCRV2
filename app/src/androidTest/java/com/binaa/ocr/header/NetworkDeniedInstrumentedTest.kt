package com.binaa.ocr.header

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * لا يستطيع Tesseract (ولا أي كود في عملية التطبيق، بما فيه الكود الأصلي) إرسال الصورة إلى الشبكة:
 * التطبيق لا يطلب INTERNET، فالنظام يرفض إنشاء أي socket للعملية كلها (EACCES). ومحرّكا الاسم يعملان مع ذلك.
 */
@RunWith(AndroidJUnit4::class)
class NetworkDeniedInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun appHasNoNetworkPermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse(requested.toString(), Manifest.permission.INTERNET in requested)
        assertFalse(requested.toString(), Manifest.permission.ACCESS_NETWORK_STATE in requested)
    }

    @Test
    fun theProcessCannotOpenASocket_evenToLocalhost() {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9), 1000) }
            fail("a socket was opened: the app process has network access")
        } catch (e: IOException) {
            // إنشاء الـsocket نفسه مرفوض: EACCES (Android أقدم) أو EPERM (API 35/36). وليس «رفض الاتصال» (ECONNREFUSED)
            // الذي يعني أن الشبكة متاحة للعملية.
            val m = e.message.orEmpty()
            assertTrue(m, m.startsWith("socket failed") && (m.contains("EACCES") || m.contains("EPERM")))
        } catch (e: SecurityException) {
            // رفض صريح من النظام: نفس النتيجة.
        }
    }

    @Test
    fun headerEngine_worksFullyOffline() {
        HeaderEngine(context).use { engine ->
            val bitmap = SyntheticHeader.viewer("Karim Saleh")
            val r = try { engine.read(bitmap) } finally { bitmap.recycle() }
            assertEquals(null, r.failure)
            assertEquals(2, r.evidence.size)
        }
    }
}
