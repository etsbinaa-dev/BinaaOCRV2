package com.binaa.ocr

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
