package com.binaa.ocr.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.binaa.ocr.intake.displayName
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.binaa.ocr.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.binaa.ocr.core.archive.PaymentRecord
import com.binaa.ocr.core.archive.PaymentStatus
import com.binaa.ocr.intake.providerLabel
import com.binaa.ocr.ui.theme.Binaa

/**
 * الهوية، ثم «للإدخال» (محفوظة ولم تُدخل في المحاسبة) و«الأرشيف» (ضغطت «تم»)، وبطاقة الإرشاد.
 * لا يوجد هنا أي زر لبدء قراءة إيصال أو لإضافة عملية: المدخل الوحيد هو المشاركة من WhatsApp.
 */
@Composable
fun HomeScreen(
    state: HomeState = HomeState(),
    onTab: (HomeTab) -> Unit = {},
    onEntered: (String) -> Unit = {},
    onRestore: (String) -> Unit = {},
    onOpen: (PaymentRecord) -> Unit = {},
    onCloseImage: () -> Unit = {},
    onDelete: (PaymentRecord) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
) {
    val colors = Binaa.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.home_version_badge),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onBrand,
                modifier = Modifier.background(colors.brand, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            "\u2066" + com.binaa.ocr.BuildConfig.VERSION_NAME + "\u2069",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.testTag("version"),
        )
        Spacer(Modifier.height(20.dp))
        val empty = state.pending.isEmpty() && state.archived.isEmpty()
        // الإرشاد فقط قبل أول دفعة؛ بعدها الشاشة كلها للدفعات.
        if (empty) {
            GuideCard(startOpen = true)
            Spacer(Modifier.height(16.dp))
        }
        if (empty) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.home_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.tab == HomeTab.PENDING,
                    onClick = { onTab(HomeTab.PENDING) },
                    label = { Text(stringResource(R.string.home_tab_pending, state.pending.size)) },
                    modifier = Modifier.testTag("tab-pending"),
                )
                FilterChip(
                    selected = state.tab == HomeTab.ARCHIVE,
                    onClick = { onTab(HomeTab.ARCHIVE) },
                    label = { Text(stringResource(R.string.home_tab_archive, state.archived.size)) },
                    modifier = Modifier.testTag("tab-archive"),
                )
            }
            Text(stringResource(R.string.home_delete_hint), style = MaterialTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            val list = if (state.tab == HomeTab.PENDING) state.pending else state.archived
            if (list.isEmpty()) {
                Text(
                    stringResource(if (state.tab == HomeTab.PENDING) R.string.home_pending_empty else R.string.home_archive_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in list) PaymentCard(r, state.icons[r.provider], onEntered, onRestore, onOpen, onDelete)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    state.deleting?.let { DeleteDialog(it, onConfirmDelete, onCancelDelete) }
    state.viewing?.let { (record, image) ->
        AlertDialog(
            onDismissRequest = onCloseImage,
            confirmButton = { TextButton(onClick = onCloseImage) { Text(stringResource(R.string.intake_close), color = colors.text) } },
            title = { Text(displayName(record.name)) },
            text = {
                if (image != null) {
                    Image(bitmap = image, contentDescription = stringResource(R.string.intake_preview_description), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp))
                } else {
                    Text(stringResource(R.string.home_image_loading), color = colors.textMuted)
                }
            },
        )
    }
}

@Composable
private fun DeleteDialog(record: PaymentRecord, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = Binaa.colors
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.home_delete_title)) },
        text = { Text(stringResource(R.string.home_delete_body, displayName(record.name), record.amountText, record.date)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("delete-confirm")) { Text(stringResource(R.string.home_delete), color = colors.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.intake_cancel), color = colors.text) } },
    )
}

/**
 * دفعة واحدة في بطاقة مضغوطة: الاسم والاتجاه، ثم المبلغ والتاريخ والتطبيق في سطر، و«تم» (أو «إرجاع») صغيرًا على الجانب.
 * لمسة البطاقة تفتح صورة الإيصال، وضغطة مطوّلة تسأل عن الحذف.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaymentCard(r: PaymentRecord, icon: androidx.compose.ui.graphics.ImageBitmap?, onEntered: (String) -> Unit, onRestore: (String) -> Unit, onOpen: (PaymentRecord) -> Unit, onDelete: (PaymentRecord) -> Unit) {
    val colors = Binaa.colors
    val ltr = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr, fontWeight = FontWeight.SemiBold)
    val incoming = r.direction == "INCOMING"
    val outgoing = r.direction == "OUTGOING"
    // الاتجاه بنظرة واحدة: خلفية باهتة وشريط جانبي بلون شارته (أخضر وارد، ذهبي صادر). بلا اتجاه ⇒ بطاقة عادية.
    val accent = when { incoming -> colors.found; outgoing -> colors.uncertainBorder; else -> colors.divider }
    val tint = when { incoming -> colors.incomingTint; outgoing -> colors.outgoingTint; else -> colors.card }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .background(tint)
            .border(1.dp, colors.divider, MaterialTheme.shapes.medium)
            .combinedClickable(onClick = { onOpen(r) }, onLongClick = { onDelete(r) })
            .testTag("payment-${r.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // السطر الأول: من؟ وكم؟ — الاسم في البداية والمبلغ في النهاية، في المكان نفسه في كل البطاقات.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName(r.name),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.text,
                        // اسم طويل ينزل إلى سطر ثانٍ بدل أن يقصه المبلغ.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("\u2066${r.amountText} MRU\u2069", style = ltr, color = colors.text, maxLines = 1, softWrap = false)
                }
                // السطر الثاني: الاتجاه (إطار بسهم) ثم التطبيق (شارة ملوّنة)، والتاريخ في النهاية. لا شيء منها يُقص.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (incoming || outgoing) {
                        Text(
                            (if (incoming) "↓ " else "↑ ") + stringResource(if (incoming) R.string.direction_in else R.string.direction_out),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = accent,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .border(1.5.dp, accent, CircleShape)
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                                .testTag("direction-${r.id}"),
                        )
                    }
                    r.provider?.let { p ->
                        if (icon != null) {
                            // أيقونة التطبيق الحقيقية من الهاتف، واسمه بجانبها.
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("provider-${r.id}")) {
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.small))
                                Spacer(Modifier.size(4.dp))
                                Text(providerLabel(p), style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1, softWrap = false)
                            }
                        } else {
                            // التطبيق غير مثبّت: شارة بلونه.
                            Text(
                                providerLabel(p),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = androidx.compose.ui.graphics.Color.White,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .background(providerColor(p), MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                                    .testTag("provider-${r.id}"),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("\u2066" + r.date + "\u2069", style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.size(8.dp))
            if (r.status == PaymentStatus.PENDING) {
                Button(
                    onClick = { onEntered(r.id) },
                    modifier = Modifier.height(40.dp).testTag("entered-${r.id}"),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand),
                ) { Text(stringResource(R.string.home_entered), color = colors.onBrand) }
            } else {
                // «إرجاع» نادر: زر صغير بسهم فقط، لا يأخذ مكان الاسم.
                val restore = stringResource(R.string.home_restore)
                TextButton(
                    onClick = { onRestore(r.id) },
                    modifier = Modifier.size(40.dp).testTag("restore-${r.id}").semantics { contentDescription = restore },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("↩", style = MaterialTheme.typography.titleMedium, color = colors.textMuted) }
            }
        }
    }
}

/** «كيف أقرأ إيصالًا؟» — مفتوحة حين لا توجد دفعات، ويمكن طيّها. */
@Composable
private fun GuideCard(startOpen: Boolean) {
    val colors = Binaa.colors
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, MaterialTheme.shapes.large)
            .border(1.dp, colors.divider, MaterialTheme.shapes.large)
            .padding(16.dp)
            .testTag("guide-card"),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (open) R.string.guide_toggle_close else R.string.guide_toggle_open),
                style = MaterialTheme.typography.labelMedium,
                color = colors.brand,
            )
        }
        if (open) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Step(1, R.string.guide_step_1)
                Step(2, R.string.guide_step_2)
                Step(3, R.string.guide_step_3)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.guide_note), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        }
    }
}

@Composable
private fun Step(number: Int, @StringRes text: Int) {
    val colors = Binaa.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(colors.brand, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = colors.onBrand)
        }
        Spacer(Modifier.size(12.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = colors.text)
    }
}

/** لون ثابت لكل تطبيق دفع، ليُعرف من النظرة الأولى. */
private fun providerColor(p: String) = androidx.compose.ui.graphics.Color(
    when (p) {
        "BANKILY" -> 0xFFE0A800
        "MASRVI" -> 0xFF1BB89A
        "SEDAD" -> 0xFF9C8A2E
        "BIMBANK" -> 0xFFE65100
        "BCIPAY" -> 0xFF3B2A8C
        "CLICK" -> 0xFF1A7F99
        else -> 0xFF607D8B
    },
)
