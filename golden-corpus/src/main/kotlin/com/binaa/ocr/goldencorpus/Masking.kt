package com.binaa.ocr.goldencorpus

import com.binaa.ocr.core.ocr.OcrDocument

/**
 * التقرير المقنّع: يحتفظ بالبنية (المربعات، الثقة، الترتيب، أطوال النصوص، الفواصل والرموز) وبكلمات التسميات المعروفة،
 * ويخفي القيم: كل رقم → 9، كل حرف لاتيني → a، كل حرف عربي → ب.
 * يُراجَع يدويًا قبل مشاركته: الإخفاء آلي، والمراجعة البشرية هي الضمان الأخير.
 */
object Masking {

    /** تسميات الإيصالات وأسماء المزوّدين: ليست بيانات شخصية، ويحتاجها تحليل البنية. */
    val KEPT_WORDS: Set<String> = setOf(
        "montant", "frais", "taxe", "ttc", "total", "solde", "mru", "mro", "um",
        "vous", "avez", "envoyé", "envoye", "reçu", "recu", "date", "heure",
        "référence", "reference", "réf", "ref", "transaction", "id", "n°",
        "masrvi", "bankily", "sedad", "bcipay", "bimbank",
        "المبلغ", "الرسوم", "الضريبة", "التاريخ", "الوقت", "رقم", "العملية", "المرجع", "أوقية",
        // مفردات التقرير نفسه.
        "field", "ocr", "found", "uncertain", "not", "counterparty", "amount", "time", "provider",
    )

    private val TOKEN = Regex("""[\p{L}\p{N}°]+""")

    fun mask(text: String): String = TOKEN.replace(text) { m ->
        val token = m.value
        if (token.lowercase() in KEPT_WORDS) token else token.map(::maskChar).joinToString("")
    }

    private fun maskChar(c: Char): Char = when {
        c.isDigit() -> '9'
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿' -> 'ب'
        c.isLetter() -> 'a'
        else -> c
    }

    /** نفس المستند بنفس المعرّفات والمربعات والثقة، بنصوص مقنّعة. */
    fun mask(document: OcrDocument): OcrDocument = document.copy(
        blocks = document.blocks.map { block ->
            block.copy(
                text = mask(block.text),
                lines = block.lines.map { line ->
                    line.copy(text = mask(line.text), words = line.words.map { it.copy(text = mask(it.text)) })
                },
            )
        },
    )

    /** التقرير نفسه، مقنّعًا: القيم والنصوص والمستندات، وتفاصيل المشاكل تُحذف (قد تحمل جزءًا من الملف). */
    fun mask(report: CorpusReport): CorpusReport = report.copy(
        masked = true,
        cases = report.cases.map { case ->
            case.copy(
                checks = case.checks.map { it.copy(check = mask(it.check), expected = mask(it.expected), observed = it.observed?.let(::mask)) },
                document = case.document?.let(::mask),
            )
        },
        problems = report.problems.map { it.copy(detail = null) },
    )
}
