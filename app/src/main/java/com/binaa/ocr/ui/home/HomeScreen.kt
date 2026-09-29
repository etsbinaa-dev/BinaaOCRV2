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
import com.binaa.ocr.R
import com.binaa.ocr.ui.theme.Binaa

/**
 * Phase 1: لا أرشيف بعد، فالشاشة = الهوية + بطاقة الإرشاد (مفتوحة) + حالة فارغة.
 * لا يوجد هنا أي زر لبدء قراءة إيصال أو لإضافة عملية: المدخل الوحيد هو المشاركة من WhatsApp.
 */
@Composable
fun HomeScreen() {
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
        Spacer(Modifier.height(24.dp))
        GuideCard()
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.home_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** «كيف أقرأ إيصالًا؟» — مفتوحة مبدئيًا (لا عمليات بعد)، ويمكن طيّها. */
@Composable
private fun GuideCard() {
    val colors = Binaa.colors
    var open by rememberSaveable { mutableStateOf(true) }
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
