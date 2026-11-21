package com.binaa.ocr.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.binaa.ocr.R
import com.binaa.ocr.core.sync.SyncKey
import com.binaa.ocr.sync.SyncProblem
import com.binaa.ocr.sync.SyncStatus
import com.binaa.ocr.ui.theme.Binaa
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import java.time.ZoneId

/** أوامر المزامنة من الشاشة إلى MainActivity. */
enum class SyncCommand { PAIR_FIRST, SCAN, SHOW_CODE, SYNC_NOW, GRANT_ACCESS, STOP }

/**
 * سطر صغير تحت العنوان: حالة المزامنة بين الهاتفين. لمسة واحدة تفتح التفاصيل (الربط، المزامنة الآن، الإيقاف).
 * [qr] = مفتاح يُعرض رمزه الآن (بعد «هذا الهاتف الأول» أو «عرض رمز الربط»).
 */
@Composable
fun SyncSection(status: SyncStatus, qr: SyncKey?, onCommand: (SyncCommand) -> Unit, onCloseQr: () -> Unit) {
    val colors = Binaa.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val on = status as? SyncStatus.On
    val line = when {
        on == null -> stringResource(R.string.sync_off_line)
        on.running -> stringResource(R.string.sync_running)
        on.problem == SyncProblem.NEEDS_ACCESS -> stringResource(R.string.sync_needs_access)
        on.problem == SyncProblem.OFFLINE -> stringResource(R.string.sync_offline)
        on.problem == SyncProblem.FAILED -> stringResource(R.string.sync_failed)
        on.lastOkMillis != null -> stringResource(R.string.sync_ok_at, hhmm(on.lastOkMillis))
        else -> stringResource(R.string.sync_on)
    }
    val warn = on?.problem != null
    Text(
        "⇅ $line",
        style = MaterialTheme.typography.labelMedium,
        color = if (warn) colors.error else colors.textMuted,
        modifier = Modifier.clickable { open = true }.padding(vertical = 6.dp).testTag("sync-line"),
    )

    if (qr != null) {
        AlertDialog(
            onDismissRequest = onCloseQr,
            confirmButton = { TextButton(onClick = onCloseQr) { Text(stringResource(R.string.sync_done), color = colors.text) } },
            title = { Text(stringResource(R.string.sync_qr_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val bitmap = remember(qr) { qrBitmap(qr.toQr(), 720) }
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(240.dp).background(androidx.compose.ui.graphics.Color.White))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.sync_check_code, spaced(qr.checkCode)), style = MaterialTheme.typography.titleMedium, color = colors.text)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sync_qr_hint), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, textAlign = TextAlign.Center)
                }
            },
        )
    }

    if (open && qr == null) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.intake_close), color = colors.text) } },
            title = { Text(stringResource(R.string.sync_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (on == null) {
                        Text(stringResource(R.string.sync_explain), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                        Button(onClick = { onCommand(SyncCommand.PAIR_FIRST) }, colors = ButtonDefaults.buttonColors(containerColor = colors.brand), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sync_pair_first), color = colors.onBrand)
                        }
                        OutlinedButton(onClick = { open = false; onCommand(SyncCommand.SCAN) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sync_pair_scan), color = colors.text)
                        }
                    } else {
                        Text(stringResource(R.string.sync_check_code, spaced(on.checkCode)), style = MaterialTheme.typography.titleMedium, color = colors.text)
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (warn) colors.error else colors.textMuted)
                        if (on.problem == SyncProblem.NEEDS_ACCESS) {
                            Button(onClick = { open = false; onCommand(SyncCommand.GRANT_ACCESS) }, colors = ButtonDefaults.buttonColors(containerColor = colors.brand), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.sync_grant), color = colors.onBrand)
                            }
                        }
                        OutlinedButton(onClick = { onCommand(SyncCommand.SYNC_NOW) }, enabled = !on.running, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sync_now), color = colors.text)
                        }
                        OutlinedButton(onClick = { onCommand(SyncCommand.SHOW_CODE) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sync_show_code), color = colors.text)
                        }
                        var confirmStop by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { if (confirmStop) { open = false; onCommand(SyncCommand.STOP) } else confirmStop = true }) {
                                Text(stringResource(if (confirmStop) R.string.sync_stop_confirm else R.string.sync_stop), color = colors.error)
                            }
                        }
                    }
                }
            },
        )
    }
}

private fun spaced(code: String) = "⁦" + code.chunked(3).joinToString(" ") + "⁩"

private fun hhmm(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let {
    "⁦" + it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0') + "⁩"
}

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
