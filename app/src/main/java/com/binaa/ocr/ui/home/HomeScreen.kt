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
import com.binaa.ocr.R
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
        GuideCard(startOpen = empty)
        Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(12.dp))
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in list) PaymentCard(r, onEntered, onRestore, onOpen)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    state.viewing?.let { (record, image) ->
        AlertDialog(
            onDismissRequest = onCloseImage,
            confirmButton = { TextButton(onClick = onCloseImage) { Text(stringResource(R.string.intake_close), color = colors.text) } },
            title = { Text(record.name) },
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

/** دفعة واحدة: الاسم والاتجاه، المبلغ، التاريخ والتطبيق، ثم «تم» (أو «إرجاع» في الأرشيف). لمسة البطاقة تفتح صورة الإيصال. */
@Composable
private fun PaymentCard(r: PaymentRecord, onEntered: (String) -> Unit, onRestore: (String) -> Unit, onOpen: (PaymentRecord) -> Unit) {
    val colors = Binaa.colors
    val ltr = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, MaterialTheme.shapes.large)
            .border(1.dp, colors.divider, MaterialTheme.shapes.large)
            .clickable { onOpen(r) }
            .padding(14.dp)
            .testTag("payment-${r.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
            r.direction?.let { d ->
                val incoming = d == "INCOMING"
                Text(
                    stringResource(if (incoming) R.string.direction_in else R.string.direction_out),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onBrand,
                    modifier = Modifier
                        .background(if (incoming) colors.found else colors.uncertainBorder, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
        Text("\u2066${r.amountText} MRU\u2069", style = ltr, color = colors.text)
        Text(
            "\u2066" + r.date + "\u2069",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
        r.provider?.let { p ->
            // شارة كبيرة بلون كل تطبيق (بلا شعارات تجارية: لا صور داخل التطبيق).
            Text(
                providerLabel(p),
                style = MaterialTheme.typography.titleMedium,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .background(providerColor(p), MaterialTheme.shapes.medium)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("provider-${r.id}"),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (r.status == PaymentStatus.PENDING) {
            Button(
                onClick = { onEntered(r.id) },
                modifier = Modifier.fillMaxWidth().testTag("entered-${r.id}"),
                colors = ButtonDefaults.buttonColors(containerColor = colors.brand),
            ) { Text(stringResource(R.string.home_entered), color = colors.onBrand) }
        } else {
            OutlinedButton(onClick = { onRestore(r.id) }, modifier = Modifier.fillMaxWidth().testTag("restore-${r.id}")) {
                Text(stringResource(R.string.home_restore), color = colors.text)
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
        "BANKILY" -> 0xFF0097B2
        "MASRVI" -> 0xFF2E7D32
        "SEDAD" -> 0xFF43A047
        "BIMBANK" -> 0xFFE65100
        "BCIPAY" -> 0xFF5B3CC4
        else -> 0xFF607D8B
    },
)
