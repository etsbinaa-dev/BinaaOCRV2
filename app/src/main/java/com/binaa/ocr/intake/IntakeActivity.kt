package com.binaa.ocr.intake

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.binaa.ocr.core.intake.IntakePhase
import com.binaa.ocr.core.intake.ShareAction
import com.binaa.ocr.ui.theme.BinaaTheme

/**
 * المدخل الوحيد لـV2: لقطة شاشة واحدة كاملة تُشارَك من WhatsApp.
 * التحليل يبدأ تلقائيًا فور الاستلام، بلا أي خطوة تأكيد. الإغلاق يعيد المستخدم إلى WhatsApp.
 */
class IntakeActivity : ComponentActivity() {

    val viewModel: IntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // الـViewModel يبقى عبر تدوير الشاشة، فلا تُعاد المعالجة. بعد موت العملية يعود فارغًا فتُعاد من المشاركة نفسها.
        if (viewModel.state.value.phase == IntakePhase.IDLE && !viewModel.state.value.closed) handleShare(intent)
        setContent {
            BinaaTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.closed) { if (state.closed) finish() }
                IntakeScreen(
                    state = state,
                    onClose = viewModel::close,
                    onConfirmReplace = viewModel::confirmReplace,
                    onKeepCurrent = viewModel::keepCurrent,
                    onEdit = viewModel::editDraft,
                    onSave = viewModel::save,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** عام للاختبارات: يعامل [intent] كما لو وصل من المشاركة. */
    fun handleShare(intent: Intent) {
        viewModel.onShare(shareRequestOf(intent))
    }

    private fun shareRequestOf(intent: Intent): ShareRequest {
        val action = when (intent.action) {
            Intent.ACTION_SEND -> ShareAction.SEND
            Intent.ACTION_SEND_MULTIPLE -> ShareAction.SEND_MULTIPLE
            else -> ShareAction.OTHER
        }
        val fromExtras: List<Uri> = when (action) {
            ShareAction.SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            ShareAction.SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
            ShareAction.OTHER -> emptyList()
        }
        // بعض التطبيقات تضع الصور في ClipData أيضًا (أو فيه فقط): نعدّ كل صورة مميزة مرة واحدة.
        val fromClip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        return ShareRequest(action, (fromExtras + fromClip).distinct())
    }
}
