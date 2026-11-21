package com.binaa.ocr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.binaa.ocr.core.sync.SyncKey
import com.binaa.ocr.sync.SyncEngine
import com.binaa.ocr.sync.SyncKeyStore
import com.binaa.ocr.ui.home.SyncCommand
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.binaa.ocr.ui.home.HomeScreen
import com.binaa.ocr.ui.home.HomeViewModel
import com.binaa.ocr.ui.theme.BinaaTheme

/** الشاشة الرئيسية: «للإدخال» و«الأرشيف» وإرشاد. لا يوجد فيها أي زر لبدء قراءة أو لإضافة عملية. */
class MainActivity : ComponentActivity() {

    val viewModel: HomeViewModel by viewModels()

    /** مفتاح يُعرض رمز QR له الآن. */
    private var syncQr by mutableStateOf<SyncKey?>(null)

    /** موافقة Google Drive (أول مرة فقط). */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { viewModel.refresh() }

    private fun onSync(command: SyncCommand) {
        when (command) {
            SyncCommand.PAIR_FIRST -> {
                syncQr = SyncEngine.pairAsFirst(this)
                viewModel.refresh()
            }
            SyncCommand.SHOW_CODE -> syncQr = SyncKeyStore.load(this)
            SyncCommand.SCAN -> scan()
            SyncCommand.SYNC_NOW -> viewModel.refresh()
            SyncCommand.GRANT_ACCESS -> SyncEngine.pendingConsent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                ?: viewModel.refresh()
            SyncCommand.STOP -> SyncEngine.stop(this)
        }
    }

    /** الهاتف الثاني: مسح رمز QR بماسح Google (لا يحتاج التطبيق صلاحية الكاميرا). */
    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                if (SyncEngine.pairFromQr(this, barcode.rawValue.orEmpty()) != null) viewModel.refresh()
                else Toast.makeText(this, R.string.sync_bad_qr, Toast.LENGTH_LONG).show()
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // إذن الإشعارات (Android 13+) مرة واحدة: بدونه لا رقم على الأيقونة، والتطبيق يعمل كما هو.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            BinaaTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val syncStatus by SyncEngine.status.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onTab = viewModel::selectTab,
                    onEntered = viewModel::markEntered,
                    onRestore = viewModel::markPending,
                    onOpen = viewModel::open,
                    onCloseImage = viewModel::closeImage,
                    onDelete = viewModel::requestDelete,
                    onConfirmDelete = viewModel::confirmDelete,
                    onCancelDelete = viewModel::cancelDelete,
                    sync = syncStatus,
                    syncQr = syncQr,
                    onSync = ::onSync,
                    onCloseSyncQr = { syncQr = null },
                )
            }
        }
    }

    /** دفعة حُفظت من المشاركة أثناء غياب الشاشة: تظهر عند العودة. */
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
