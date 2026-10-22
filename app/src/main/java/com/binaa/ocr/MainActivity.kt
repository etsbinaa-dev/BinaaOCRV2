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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.binaa.ocr.ui.home.HomeScreen
import com.binaa.ocr.ui.home.HomeViewModel
import com.binaa.ocr.ui.theme.BinaaTheme

/** الشاشة الرئيسية: «للإدخال» و«الأرشيف» وإرشاد. لا يوجد فيها أي زر لبدء قراءة أو لإضافة عملية. */
class MainActivity : ComponentActivity() {

    val viewModel: HomeViewModel by viewModels()

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(viewModel::exportTo) }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFrom) }

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
                    onExport = { exportLauncher.launch("binaa-ocr-backup-${java.time.LocalDate.now()}.zip") },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
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
