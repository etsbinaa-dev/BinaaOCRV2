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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
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
import com.binaa.ocr.core.receipt.Amount
import com.binaa.ocr.core.receipt.FieldReason
import com.binaa.ocr.core.receipt.FieldStatus
import com.binaa.ocr.core.receipt.PaymentDate
import com.binaa.ocr.core.receipt.Provider
import com.binaa.ocr.core.receipt.ReceiptField
import com.binaa.ocr.header.NameReason
import com.binaa.ocr.header.NameStatus
import com.binaa.ocr.header.PaymentDirection
import com.binaa.ocr.ui.theme.Binaa

@Composable
fun IntakeScreen(
    state: IntakeUiState,
    onClose: () -> Unit,
    onConfirmReplace: () -> Unit,
    onKeepCurrent: () -> Unit,
    onEdit: ((PaymentDraft) -> PaymentDraft) -> Unit = {},
    onSave: () -> Unit = {},
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
                IntakePhase.RECEIVED -> Received(state, onEdit)
                IntakePhase.REJECTED -> Rejected(state.rejection ?: IntakeRejection.UNREADABLE)
                IntakePhase.IDLE -> Unit
            }
        }

        // الإجراء الوحيد في أسفل الشاشة (منطقة الإبهام).
        val label = when {
            state.phase == IntakePhase.RECEIVING || state.header == HeaderUi.Reading || state.receipt == ReceiptUi.Reading -> R.string.intake_cancel
            state.header is HeaderUi.Done -> R.string.intake_done
            else -> R.string.intake_close
        }
        val draft = state.draft
        if (draft != null) {
            // «حفظ» إلى «للإدخال»، ويعمل فقط حين يكتمل الاسم والمبلغ والتاريخ والاتجاه. ✕ في الأعلى = إلغاء بلا حفظ.
            Button(
                onClick = onSave,
                enabled = draft.canSave && !state.saved,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("intake-save"),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = colors.brand),
            ) {
                Text(stringResource(R.string.intake_save), style = MaterialTheme.typography.labelLarge, color = colors.onBrand)
            }
        } else {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("intake-bottom-action"),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colors.divider),
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
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
private fun Received(state: IntakeUiState, onEdit: ((PaymentDraft) -> PaymentDraft) -> Unit) {
    val colors = Binaa.colors
    val image = state.image ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        // اللقطة كاملة كما وصلت (Fit: لا قص في العرض أيضًا).
        state.preview?.let { preview ->
            Image(
                bitmap = preview,
                contentDescription = stringResource(R.string.intake_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(if (state.draft != null) 220.dp else 360.dp)
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
        val draft = state.draft
        val header = state.header
        if (draft != null && header is HeaderUi.Done) {
            ReviewForm(draft, header, onEdit)
        } else {
            state.header?.let { HeaderSection(it) }
            state.receipt?.let { Spacer(Modifier.height(8.dp)); ReceiptSection(it) }
        }
        Spacer(Modifier.height(8.dp))
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

/** اسم الطرف الآخر: جارٍ، أو الاسم (FOUND)، أو السبب (NOT_FOUND). الاسم اختياري ولا يُخمَّن. */
@Composable
private fun HeaderSection(header: HeaderUi) {
    val colors = Binaa.colors
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (header is HeaderUi.Done && header.result.status == NameStatus.FOUND) colors.found else colors.notFoundBorder, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("intake-header"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.header_label), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
        when (header) {
            HeaderUi.Reading -> {
                Text(stringResource(R.string.header_reading), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = colors.brand, trackColor = colors.divider)
            }
            is HeaderUi.Done -> {
                val result = header.result
                val name = result.name
                if (result.status == NameStatus.FOUND && name != null) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.testTag("intake-header-name"))
                } else {
                    Text(stringResource(R.string.header_not_found), style = MaterialTheme.typography.titleMedium, color = colors.text)
                    val hint = when (result.reason) {
                        NameReason.NAME_UNREADABLE -> R.string.header_reason_unreadable
                        NameReason.SENT_BY_YOU -> R.string.header_reason_sent_by_you
                        NameReason.HEADER_NOT_DETECTED, null -> R.string.header_reason_not_detected
                    }
                    Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.testTag("intake-header-reason"))
                }
                result.direction?.let { direction ->
                    Text(
                        stringResource(if (direction == PaymentDirection.INCOMING) R.string.direction_incoming else R.string.direction_outgoing),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textMuted,
                        modifier = Modifier.testTag("intake-direction"),
                    )
                }
            }
        }
    }
}

/**
 * المراجعة قبل الحفظ: كل حقل قابل للتعديل. ما قُرئ بيقين يملأ الحقل؛ «غير مؤكد» يعرض مرشّحيه للاختيار بلمسة؛
 * «غير موجود» يبقى فارغًا ليكتبه المستخدم. لا شيء يُختار تلقائيًا.
 */
@Composable
private fun ReviewForm(draft: PaymentDraft, header: HeaderUi.Done, onEdit: ((PaymentDraft) -> PaymentDraft) -> Unit) {
    val colors = Binaa.colors
    val ltr = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr)
    val nameHint: Int? = when (header.result.reason) {
        NameReason.NAME_UNREADABLE -> R.string.header_reason_unreadable
        NameReason.SENT_BY_YOU -> R.string.header_reason_sent_by_you
        NameReason.HEADER_NOT_DETECTED -> R.string.header_reason_not_detected
        null -> null
    }
    Column(Modifier.fillMaxWidth().testTag("intake-review"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { v -> onEdit { it.copy(name = v) } },
            label = { Text(stringResource(R.string.receipt_name)) },
            supportingText = if (nameHint != null) { { Text(stringResource(nameHint)) } } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("review-name"),
        )
        OutlinedTextField(
            value = draft.amount,
            onValueChange = { v -> onEdit { it.copy(amount = v) } },
            label = { Text(stringResource(R.string.receipt_amount_mru)) },
            textStyle = ltr,
            isError = draft.amount.isNotBlank() && draft.amountCents == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("review-amount"),
        )
        Candidates(draft.amountCandidates, { "\u2066$it MRU\u2069" }) { c -> onEdit { it.copy(amount = c) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.date,
                onValueChange = { v -> onEdit { it.copy(date = v) } },
                label = { Text(stringResource(R.string.receipt_date_format)) },
                textStyle = ltr,
                isError = draft.date.isNotBlank() && draft.localDate == null,
                singleLine = true,
                modifier = Modifier.weight(0.62f).testTag("review-date"),
            )
            OutlinedTextField(
                value = draft.time,
                onValueChange = { v -> onEdit { it.copy(time = v) } },
                label = { Text(stringResource(R.string.receipt_time_format)) },
                textStyle = ltr,
                isError = draft.time.isNotBlank() && draft.localTime == null,
                singleLine = true,
                modifier = Modifier.weight(0.38f).testTag("review-time"),
            )
        }
        Candidates(draft.dateCandidates, { "\u2066${it.first} ${it.second}\u2069" }) { c -> onEdit { it.copy(date = c.first, time = c.second) } }

        Text(stringResource(R.string.receipt_provider), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
        if (draft.providerCandidates.isNotEmpty() && draft.provider == null) {
            Text(stringResource(R.string.field_uncertain, draft.providerCandidates.map { providerLabel(it) }.joinToString(" / ")), color = colors.uncertainBorder)
        }
        Chips(PROVIDERS, draft.provider, { providerLabel(it) }) { p -> onEdit { it.copy(provider = if (it.provider == p) null else p) } }

        Text(stringResource(R.string.receipt_direction), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
        Chips(listOf("INCOMING", "OUTGOING"), draft.direction, { stringResource(if (it == "INCOMING") R.string.direction_in else R.string.direction_out) }) { d ->
            onEdit { it.copy(direction = d) }
        }
    }
}

private val PROVIDERS = listOf("BANKILY", "MASRVI", "SEDAD", "BIMBANK", "BCIPAY", "OTHER")

@Composable
private fun <T> Candidates(candidates: List<T>, show: (T) -> String, onPick: (T) -> Unit) {
    if (candidates.isEmpty()) return
    val colors = Binaa.colors
    Text(stringResource(R.string.field_pick_one), style = MaterialTheme.typography.bodyMedium, color = colors.uncertainBorder)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in candidates) FilterChip(selected = false, onClick = { onPick(c) }, label = { Text(show(c)) })
    }
}

@Composable
private fun Chips(options: List<String>, selected: String?, label: @Composable (String) -> String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }, modifier = Modifier.testTag("chip-$o"))
    }
}

@Composable
fun providerLabel(p: String): String = stringResource(
    when (p) {
        "BANKILY" -> R.string.provider_bankily
        "MASRVI" -> R.string.provider_masrvi
        "SEDAD" -> R.string.provider_sedad
        "BIMBANK" -> R.string.provider_bimbank
        "BCIPAY" -> R.string.provider_bcipay
        else -> R.string.provider_other
    },
)

/** المبلغ والتاريخ والتطبيق: القيمة (FOUND)، أو «غير مؤكد» مع المرشّحين، أو «غير موجود» مع السبب. لا اختيار تلقائي. */
@Composable
private fun ReceiptSection(receipt: ReceiptUi) {
    val colors = Binaa.colors
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colors.notFoundBorder, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("intake-receipt"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (receipt) {
            ReceiptUi.Reading -> {
                Text(stringResource(R.string.receipt_reading), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = colors.brand, trackColor = colors.divider)
            }
            is ReceiptUi.Done -> {
                val r = receipt.reading
                FieldRow(R.string.receipt_amount, r.amount, "intake-amount") { formatAmount(it) }
                FieldRow(R.string.receipt_date, r.date, "intake-date") { formatDate(it) }
                FieldRow(R.string.receipt_provider, r.provider, "intake-provider") { providerName(it) }
            }
        }
    }
}

@Composable
private fun <T> FieldRow(label: Int, field: ReceiptField<T>, tag: String, show: @Composable (T) -> String) {
    val colors = Binaa.colors
    val value = field.value
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.textMuted, modifier = Modifier.weight(0.35f))
        val text = when (field.status) {
            FieldStatus.FOUND -> if (value != null) show(value) else ""
            FieldStatus.UNCERTAIN -> stringResource(R.string.field_uncertain, field.candidates.map { show(it) }.joinToString(" / "))
            FieldStatus.NOT_FOUND -> stringResource(if (field.reason == FieldReason.UNKNOWN_PROVIDER) R.string.field_unsupported else R.string.field_not_found)
        }
        val color = when (field.status) {
            FieldStatus.FOUND -> colors.text
            FieldStatus.UNCERTAIN -> colors.uncertainBorder
            FieldStatus.NOT_FOUND -> colors.textMuted
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(0.65f).testTag(tag))
    }
}

/** 6200 MRU، أو 6200.50 MRU إن كان فيه كسر. اتجاه LTR داخل النص العربي. */
private fun formatAmount(a: Amount): String {
    val units = a.cents / 100
    val cents = a.cents % 100
    return "\u2066" + (if (cents == 0L) "$units" else a.canonical) + " MRU\u2069"
}

private fun formatDate(d: PaymentDate): String =
    "\u2066" + d.date.toString() + (d.time?.let { " " + it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0') } ?: "") + "\u2069"

@Composable
private fun providerName(p: Provider): String = stringResource(
    when (p) {
        Provider.BANKILY -> R.string.provider_bankily
        Provider.BIMBANK -> R.string.provider_bimbank
        Provider.SEDAD -> R.string.provider_sedad
        Provider.BCIPAY -> R.string.provider_bcipay
    },
)

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
