package com.binaa.ocr.header

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * التطبيق يملك INTERNET للمزامنة المشفّرة بين هاتفي المستخدم فقط. محرّكا الاسم (Tesseract وML Kit) يعملان كاملين
 * بلا شبكة، ولا تُضاف أي صلاحية شبكة أخرى، ومكوّن telemetry الخاص بـML Kit مُزال من الـManifest.
 */
@RunWith(AndroidJUnit4::class)
class OfflineEnginesInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onlyInternetForSync_noOtherNetworkPermission() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse(requested.toString(), Manifest.permission.ACCESS_NETWORK_STATE in requested)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
    }

    @Test
    fun mlKitTelemetry_isRemovedFromTheManifest() {
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        assertFalse(services.joinToString { it.name }, services.any { it.name.contains("datatransport") })
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
