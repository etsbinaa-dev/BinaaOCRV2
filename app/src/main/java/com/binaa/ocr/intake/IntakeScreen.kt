package com.binaa.ocr.intake

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.binaa.ocr.R
import com.binaa.ocr.core.intake.IntakePhase
import com.binaa.ocr.core.intake.IntakeRejection
import com.binaa.ocr.ui.theme.Binaa

@Composable
fun IntakeScreen(
    state: IntakeUiState,
    onClose: () -> Unit,
    onConfirmReplace: () -> Unit,
    onKeepCurrent: () -> Unit,
) {
    val colors = Binaa.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        // ✕ في بداية الشريط (يمين الشاشة في RTL): إغلاق والعودة إلى WhatsApp.
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.testTag("intake-close")) {
                Text("✕", style = MaterialTheme.typography.titleLarge, color = colors.text)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (state.phase) {
                IntakePhase.RECEIVING -> Receiving()
                IntakePhase.RECEIVED -> Received(state)
                IntakePhase.REJECTED -> Rejected(state.rejection ?: IntakeRejection.UNREADABLE)
                IntakePhase.IDLE -> Unit
            }
        }

        // الإجراء الوحيد في أسفل الشاشة (منطقة الإبهام).
        val label = if (state.phase == IntakePhase.RECEIVING) R.string.intake_cancel else R.string.intake_close
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("intake-bottom-action"),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.text)
        }
        Spacer(Modifier.height(12.dp))
    }

    state.pendingReplace?.let {
        AlertDialog(
            onDismissRequest = onKeepCurrent,
            title = { Text(stringResource(R.string.replace_title)) },
            text = { Text(stringResource(R.string.replace_body)) },
            confirmButton = {
                Button(onClick = onConfirmReplace, colors = ButtonDefaults.buttonColors(containerColor = colors.brand)) {
                    Text(stringResource(R.string.replace_confirm), color = colors.onBrand)
                }
            },
            dismissButton = { TextButton(onClick = onKeepCurrent) { Text(stringResource(R.string.replace_keep), color = colors.text) } },
        )
    }
}

@Composable
private fun Receiving() {
    val colors = Binaa.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.intake_receiving), style = MaterialTheme.typography.titleMedium, color = colors.text)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(0.5f).clip(CircleShape),
            color = colors.brand,
            trackColor = colors.divider,
        )
    }
}

@Composable
private fun Received(state: IntakeUiState) {
    val colors = Binaa.colors
    val image = state.image ?: return
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // اللقطة كاملة كما وصلت (Fit: لا قص في العرض أيضًا).
        state.preview?.let { preview ->
            Image(
                bitmap = preview,
                contentDescription = stringResource(R.string.intake_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .border(1.dp, colors.divider, MaterialTheme.shapes.large)
                    .testTag("intake-preview"),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = colors.found, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.intake_received_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
        }
        Text(
            stringResource(R.string.intake_image_size, image.width, image.height),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.intake_phase1_note),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Rejected(reason: IntakeRejection) {
    val colors = Binaa.colors
    val message = when (reason) {
        IntakeRejection.MULTIPLE_IMAGES -> R.string.reject_multiple
        IntakeRejection.NO_IMAGE -> R.string.reject_no_image
        IntakeRejection.NOT_A_SHARE -> R.string.reject_not_share
        IntakeRejection.NOT_AN_IMAGE -> R.string.reject_not_image
        IntakeRejection.TOO_LARGE -> R.string.reject_too_large
        IntakeRejection.UNREADABLE -> R.string.reject_unreadable
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("⚠", style = MaterialTheme.typography.headlineMedium, color = colors.uncertainBorder)
        Text(
            stringResource(message),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("intake-rejection"),
        )
    }
}
