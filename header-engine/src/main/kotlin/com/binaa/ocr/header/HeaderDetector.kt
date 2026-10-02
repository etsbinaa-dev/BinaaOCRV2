package com.binaa.ocr.header

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** مستطيل بكسلات [l, r) × [t, b) في إحداثيات لقطة الشاشة كاملة. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    override fun toString() = "($l,$t)-($r,$b)"
}

/** بكسلات ARGB لصورة كاملة، صفًّا بعد صف. */
class PixelImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "pixel buffer does not match ${width}x$height" }
    }
}

/** اتجاه واجهة الرأس. الكاشف الحالي يعرف LTR فقط (المثبت على S26)؛ RTL ينتظر لقطات حقيقية. */
enum class UiDirection { LTR, RTL }

/**
 * نوع الرأس، ومنه اتجاه الدفعة:
 * عارض الصور (صورة الإيصال بملء الشاشة: ← الاسم ☆ ↪ ⋮) = إيصال وصلك (وارد)،
 * المحادثة (← الصورة الشخصية الاسم 📹 📞 ⋮) = إيصال أرسلته أنت (صادر)، والاسم اسم المستلم.
 */
enum class HeaderKind { IMAGE_VIEWER, CHAT }

/** لماذا لم يُعطِ الكاشف مربع اسم. */
enum class DetectorRejection {
    /** لا مجموعة أيقونات (☆ ↪ …) في صف الرأس: ليست لقطة Image Viewer. */
    NO_ICON_GROUP,

    /** رأس بلا سطر نص بين السهم والأيقونات. */
    NO_TEXT_LINE,

    /** أول سطر نص ليس في صف الأيقونات (مثلًا سطر subtitle وحده): لا يُعدّ اسمًا. */
    NAME_NOT_ON_ICON_ROW,

    /** الاسم يلامس الأيقونات: مقطوع على الأرجح. */
    NAME_TOUCHES_ICONS,

    /** ارتفاع سطر الاسم خارج المدى بالنسبة للأيقونات. */
    NAME_HEIGHT_OUT_OF_RANGE,

    /**
     * أكثر من رأس حقيقي في اللقطة (مثلًا لقطة داخل لقطة): لا يُختار أحدها تلقائيًا ⇒ HEADER_NOT_DETECTED.
     * رأس «حقيقي» = مجموعة أيقونات + سهم رجوع في صفّها + مربع اسم مقبول؛ مجموعة أيقونات وحدها لا تكفي.
     */
    MULTIPLE_HEADER_CANDIDATES,
}

/**
 * هندسة الرأس التي بنى عليها الكاشف قراره (كلها بإحداثيات لقطة الشاشة الأصلية، أيًّا كان الاتجاه).
 * [nameToIconsGap] = المسافة الأفقية بين حافة مربع الاسم وأقرب أيقونة.
 */
data class HeaderLayout(
    val direction: UiDirection,
    /** شريط الرأس المعتمد: من أعلى الصورة حتى أسفل الـsubtitle (أو الاسم) بهامش. */
    val headerBar: Box,
    val arrow: Box?,
    val icons: List<Box>,
    val iconCluster: Box,
    val iconHeight: Int,
    val name: Box?,
    val subtitle: Box?,
    val nameToIconsGap: Int?,
    val kind: HeaderKind = HeaderKind.IMAGE_VIEWER,
    /** الصورة الشخصية الدائرية بعد السهم (رأس المحادثة فقط). */
    val avatar: Box? = null,
)

/** مربع الاسم بعد القناع: نص أسود على أبيض (ARGB) بنفس اتجاه الصورة، جاهز لـ[NamePrep]. */
class NameImage(val box: Box, val argb: IntArray) {
    val width get() = box.w
    val height get() = box.h
}

/**
 * نتيجة الكاشف. [layout] موجود متى وُجدت مجموعة أيقونات في اتجاه واحد (حتى لو رُفض الاسم)، ليظهر لماذا رُفض.
 * [nameImage] موجود فقط إذا قُبل مربع الاسم.
 */
class Detection(val layout: HeaderLayout?, val rejection: DetectorRejection?, val nameImage: NameImage?) {
    /** رأس Image Viewer موجود (حتى لو لم يُقرأ الاسم): يفرّق بين HEADER_NOT_DETECTED و NAME_UNREADABLE. */
    val headerFound: Boolean get() = layout != null
}

/**
 * كاشف رأس WhatsApp Image Viewer الهندسي، منقول من Binaa Payment OCR (المُختبَر على S26) بنفس القواعد.
 * الفروق المقصودة فقط:
 * 1) الأحجام بالبكسل مضروبة في عرض الصورة / 1080 (نفس الأرقام تمامًا عند 1080).
 * 2) LTR فقط، كما في الكاشف المثبت (الأيقونات يمينًا). رأس RTL ⇒ لا مجموعة أيقونات ⇒ HEADER_NOT_DETECTED (آمن).
 *    البحث المعكوس جُرّب ورُفض: حروف اسم لاتيني متباعدة تبدو «أيقونات» في الصورة المعكوسة فيخترع رأسًا.
 * 3) قاعدة إضافية (رفض فقط): أول سطر نص يجب أن يتقاطع عموديًا مع صف الأيقونات، فلا يصير الـsubtitle اسمًا.
 * 4) قاعدة إضافية (رفض فقط): إذا قُبل الرأس المختار ووُجد في صف آخر رأس حقيقي ثانٍ ⇒ MULTIPLE_HEADER_CANDIDATES.
 *    اختيار الرأس الأول وفحصه لم يتغيرا؛ الرأس الثاني يُفحص بالقواعد نفسها ابتداءً من صفّه هو.
 */
object HeaderDetector {

    private const val REFERENCE_WIDTH = 1080.0

    fun detect(image: PixelImage): Detection {
        val zoneH = (image.height * 0.11).toInt()
        val zone = Array(zoneH) { y -> IntArray(image.width) { x -> image.argb[y * image.width + x] } }
        return detectLtr(zone, image.width, image.height)
    }

    private fun rgb(p: Int) = intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
    private fun median(v: List<Int>) = v.sorted()[v.size / 2]

    /** مكوّنات متصلة (8 جيران) في قناع منطقي. */
    fun components(mask: Array<BooleanArray>, x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Box, Int>> {
        val seen = Array(mask.size) { BooleanArray(mask[0].size) }
        val out = mutableListOf<Pair<Box, Int>>()
        for (y in y0 until y1) for (x in x0 until x1) {
            if (!mask[y][x] || seen[y][x]) continue
            var l = x; var r = x; var t = y; var b = y; var n = 0
            val st = ArrayDeque<Int>(); st.add(y * 100000 + x); seen[y][x] = true
            while (st.isNotEmpty()) {
                val v = st.removeLast(); val cy = v / 100000; val cx = v % 100000; n++
                l = min(l, cx); r = max(r, cx); t = min(t, cy); b = max(b, cy)
                for (dy in -1..1) for (dx in -1..1) {
                    val ny = cy + dy; val nx = cx + dx
                    if (ny in y0 until y1 && nx in x0 until x1 && mask[ny][nx] && !seen[ny][nx]) { seen[ny][nx] = true; st.add(ny * 100000 + nx) }
                }
            }
            out += Box(l, t, r + 1, b + 1) to n
        }
        return out
    }

    /** الكاشف الأصلي (رأس LTR: الأيقونات يمينًا والسهم يسارًا) على شريط الرأس. */
    private fun detectLtr(px: Array<IntArray>, W: Int, height: Int): Detection {
        val zoneH = px.size
        val s = W / REFERENCE_WIDTH
        fun scaled(v: Int) = v * s
        fun scaledPx(v: Int) = max(1, (v * s).roundToInt())

        // 1) خلفية الرأس B: الوسيط في شريط الأيقونات/الاسم (أغلب البكسلات خلفية).
        val sample = (0 until zoneH step 3).flatMap { y -> (0 until W step 3).map { x -> px[y][x] } }
        val B = IntArray(3) { c -> median(sample.map { rgb(it)[c] }) }
        fun dist(p: Int): Double { val q = rgb(p); return sqrt(((q[0]-B[0])*(q[0]-B[0]) + (q[1]-B[1])*(q[1]-B[1]) + (q[2]-B[2])*(q[2]-B[2])).toDouble()) }

        // 2) أيقونات ☆ ↪ (⋮): في صف الرأس فقط (أعلى 8%)، شبه مربعة، متباعدة (فراغ ≥ عرض أيقونة)، الأقرب إلى الطرف.
        val strong = Array(zoneH) { y -> BooleanArray(W) { x -> dist(px[y][x]) > 110 } }
        val iconZone = (height * 0.08).toInt()
        val iconSize = scaled(25)..scaled(90)
        val right = components(strong, W / 2, 0, W, iconZone)
            .filter { (r, n) -> r.w.toDouble() in iconSize && r.h.toDouble() in iconSize && n > 60 * s * s && r.w.toDouble() / r.h in 0.6..1.7 }
            .map { it.first }
        var icons = emptyList<Box>()
        val groups = mutableListOf<List<Box>>()
        for (a in right) {
            val row = right.filter { abs(it.cy - a.cy) <= scaled(12) && abs(it.h - a.h) <= scaled(20) }.sortedByDescending { it.r }
            val picked = mutableListOf<Box>()
            for (c in row) if (picked.isEmpty() || picked.last().l - c.r >= max(c.w, picked.last().w)) picked += c
            val grp = picked.sortedBy { it.l }
            if (grp.size >= 2) groups += grp
            if (grp.size >= 2 && (icons.isEmpty() || grp.last().r > icons.last().r || (grp.last().r == icons.last().r && grp.size > icons.size))) icons = grp
        }
        // رأس المحادثة: صورة شخصية دائرية بعد السهم. لا توجد في رأس عارض الصور أبدًا (الاسم يبدأ بعد السهم مباشرة).
        avatar(px, W, B, iconZone)?.let { avatar ->
            // أيقونات المحادثة: أقرب اثنتين إلى الطرف في صف الصورة الشخصية (📹 📞 أو 🏪 📞). حروف الاسم لا تُحسب أيقونات.
            val row = right.filter { abs(it.cy - avatar.cy) <= scaled(25) }.sortedByDescending { it.r }
            val picked = mutableListOf<Box>()
            for (c in row) if (picked.size < 2 && (picked.isEmpty() || picked.last().l - c.r >= max(c.w, picked.last().w))) picked += c
            if (picked.size < 2) return Detection(null, DetectorRejection.NO_ICON_GROUP, null)
            return evaluate(px, W, B, picked.sortedBy { it.l }, 0, avatar)
        }
        if (icons.size < 2) return Detection(null, DetectorRejection.NO_ICON_GROUP, null)
        val primary = evaluate(px, W, B, icons, 0)
        if (primary.rejection != null) return primary
        // رأس ثانٍ حقيقي في صف لا يتقاطع مع صف الرأس المختار ⇒ التباس، لا اختيار.
        val cluster = primary.layout!!.iconCluster
        val second = groups.filter { g -> g.maxOf { it.b } <= cluster.t || g.minOf { it.t } >= cluster.b }
            .distinctBy { g -> g.map { it.l to it.t } }
            .any { g ->
                val iconH = median(g.map { it.h })
                val d = evaluate(px, W, B, g, max(0, g.minOf { it.t } - iconH))
                d.rejection == null && d.layout?.arrow != null
            }
        return if (second) Detection(null, DetectorRejection.MULTIPLE_HEADER_CANDIDATES, null) else primary
    }

    /**
     * الصورة الشخصية في رأس المحادثة: كتلة واحدة مختلفة عن الخلفية، شبه مربعة (دائرة)، 80–135 بكسل (عند 1080)،
     * تبدأ بعد السهم مباشرة (120–220). صورة حقيقية أو حرف ملوّن أو الصورة الرمادية الافتراضية: كلها كتلة واحدة.
     */
    private fun avatar(px: Array<IntArray>, W: Int, B: IntArray, iconZone: Int): Box? {
        val s = W / REFERENCE_WIDTH
        val x1 = min(W, (W * 0.35).toInt())
        val soft = Array(iconZone) { y -> BooleanArray(W) { x -> x < x1 && rgb(px[y][x]).let { q -> (0..2).sumOf { (q[it] - B[it]) * (q[it] - B[it]) } } > 40 * 40 } }
        val size = (80 * s)..(135 * s)
        return components(soft, 0, 0, x1, iconZone)
            .filter { (b, n) -> b.w.toDouble() in size && b.h.toDouble() in size && b.w.toDouble() / b.h in 0.8..1.25 && n >= 0.5 * b.w * b.h && b.l.toDouble() in (120 * s)..(220 * s) }
            .maxByOrNull { it.second }?.first
    }

    /**
     * الخطوات 3–7 للرأس الذي أيقوناته [icons]. [yStart] = أول صف يُبحث فيه عن سطر الاسم
     * (0 للرأس المختار كما في الكاشف المثبت؛ صف الرأس الثاني عند فحص الالتباس).
     */
    private fun evaluate(px: Array<IntArray>, W: Int, B: IntArray, icons: List<Box>, yStart: Int, avatar: Box? = null): Detection {
        val zoneH = px.size
        val s = W / REFERENCE_WIDTH
        fun scaled(v: Int) = v * s
        fun scaledPx(v: Int) = max(1, (v * s).roundToInt())
        fun dist(p: Int): Double { val q = rgb(p); return sqrt(((q[0]-B[0])*(q[0]-B[0]) + (q[1]-B[1])*(q[1]-B[1]) + (q[2]-B[2])*(q[2]-B[2])).toDouble()) }
        val iconH = median(icons.map { it.h })
        val cluster = Box(icons.minOf { it.l }, icons.minOf { it.t }, icons.maxOf { it.r }, icons.maxOf { it.b })

        // 3) لون الواجهة F من لب الأيقونات.
        val iconPx = icons.flatMap { r -> (r.t until r.b).flatMap { y -> (r.l until r.r).map { x -> px[y][x] } } }.filter { dist(it) > 110 }
        val top = iconPx.sortedByDescending { dist(it) }.take(max(10, iconPx.size / 3))
        val F = IntArray(3) { c -> median(top.map { rgb(it)[c] }) }
        val fb = DoubleArray(3) { (F[it] - B[it]).toDouble() }; val fb2 = fb.sumOf { it * it }
        fun tOf(p: Int): Double {
            val q = rgb(p); val d = DoubleArray(3) { (q[it] - B[it]).toDouble() }
            val t = (d[0] * fb[0] + d[1] * fb[1] + d[2] * fb[2]) / fb2
            val e = sqrt((0..2).sumOf { (d[it] - t * fb[it]).let { v -> v * v } })
            return if (e <= 0.3 * sqrt(fb2)) t else 0.0
        }
        val T = Array(zoneH) { y -> DoubleArray(W) { x -> tOf(px[y][x]) } }
        // 4) القناع: لب ≥ 0.75، وحواف ≥ 0.35 على بعد ≤ 2 بكسل من اللب فقط.
        val reach = scaledPx(2)
        val core = Array(zoneH) { y -> BooleanArray(W) { x -> T[y][x] >= 0.75 } }
        val mask = Array(zoneH) { y -> BooleanArray(W) { x -> core[y][x] || (T[y][x] >= 0.35 &&
            (-reach..reach).any { dy -> (-reach..reach).any { dx -> val yy = y + dy; val xx = x + dx; yy in 0 until zoneH && xx in 0 until W && core[yy][xx] } }) } }

        // 5) السهم = كتلة لب بحجم أيقونة في أقصى الطرف الآخر.
        val arrowSize = scaled(20)..scaled(90)
        val arrow = components(core, 0, 0, (W * 0.11).toInt(), zoneH).map { it.first }
            .filter { it.w.toDouble() in arrowSize && it.h.toDouble() in arrowSize && abs(it.cy - icons.first().cy) <= scaled(25) }
            .maxByOrNull { it.w * it.h }
        val xStart = max((arrow?.r ?: (W * 0.08).toInt()) + iconH / 3, avatar?.let { it.r + scaledPx(20) } ?: 0)
        val firstIcon = icons.first()
        val xEnd = firstIcon.l - iconH / 4
        fun layout(name: Box?, sub: Box?, headerHeight: Int) = HeaderLayout(
            direction = UiDirection.LTR,
            headerBar = Box(0, 0, W, headerHeight),
            arrow = arrow,
            icons = icons,
            iconCluster = cluster,
            iconHeight = iconH,
            name = name,
            subtitle = sub,
            nameToIconsGap = name?.let { firstIcon.l - it.r },
            kind = if (avatar != null) HeaderKind.CHAT else HeaderKind.IMAGE_VIEWER,
            avatar = avatar,
        )

        // 6) أسطر النص بين السهم والأيقونات (إسقاط أفقي على القناع).
        val yEnd = min(zoneH, firstIcon.b + iconH * 2)
        val rowInk = IntArray(yEnd) { y -> (xStart until xEnd).count { mask[y][it] } }
        val bands = mutableListOf<IntArray>()
        var y = yStart
        while (y < yEnd) {
            if (rowInk[y] == 0) { y++; continue }
            var e = y; var gap = 0; var k = y
            while (k < yEnd && gap <= scaled(6)) { if (rowInk[k] > 0) { e = k; gap = 0 } else gap++; k++ }
            if ((y..e).sumOf { rowInk[it] } > 40 * s * s) bands += intArrayOf(y, e + 1)
            y = k
        }
        if (bands.isEmpty()) return Detection(layout(null, null, zoneH), DetectorRejection.NO_TEXT_LINE, null)
        fun inkBox(b: IntArray): Box? {
            val cols = (xStart until xEnd).filter { x -> (b[0] until b[1]).any { mask[it][x] } }
            return if (cols.isEmpty()) null else Box(cols.first(), b[0], cols.last() + 1, b[1])
        }
        val name = inkBox(bands[0])!!
        val sub = bands.getOrNull(1)?.let { inkBox(it) }
        val headerHeight = min(zoneH, (sub?.b ?: name.b) + scaledPx(40))
        val found = layout(name, sub, headerHeight)
        if (!(name.t < firstIcon.b && name.b > firstIcon.t)) return Detection(found, DetectorRejection.NAME_NOT_ON_ICON_ROW, null)
        if (name.r >= xEnd - scaled(2)) return Detection(found, DetectorRejection.NAME_TOUCHES_ICONS, null)
        if (name.h.toDouble() / iconH !in 0.6..1.8) return Detection(found, DetectorRejection.NAME_HEIGHT_OUT_OF_RANGE, null)

        // 7) الصورة النظيفة: خارج القناع أبيض، داخله أسود بقدر t.
        val pad = name.h / 3
        val box = Box(max(0, name.l - pad), max(0, name.t - pad), min(W, name.r + pad), min(zoneH, name.b + pad))
        val clean = IntArray(box.w * box.h) { i ->
            val xx = box.l + i % box.w; val yy = box.t + i / box.w
            val v = if (mask[yy][xx]) (255 - (T[yy][xx].coerceIn(0.0, 1.0) * 255)).toInt() else 255
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Detection(found, null, NameImage(box, clean))
    }
}
