package com.binaa.ocr.header

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * الكاشف على رأس WhatsApp Image Viewer اصطناعي (كتل بكسلات، لا صور حقيقية).
 * الهندسة الأساسية نفسها التي اختُبر بها الكاشف القديم (1080×2229، أبعاد لقطات S26)،
 * والاختبارات تتحقق من **لماذا** اختير مربع الاسم: حدود الشريط والاسم والـsubtitle والأيقونات والاتجاه والمسافة.
 */
class HeaderDetectorTest {

    private val dark = 0xFF141A1E.toInt()
    private val white = 0xFFFEFEFE.toInt()
    private val gray = 0xFF7A8084.toInt()

    /** لوحة رسم بعرض/ارتفاع، مع تحجيم (k) وانعكاس اختياريين لإحداثيات الرسم. */
    private class Canvas(val w: Int, val h: Int, bg: Int, val k: Double = 1.0, val mirror: Boolean = false) {
        val px = IntArray(w * h) { bg }
        fun rect(l: Int, t: Int, r: Int, b: Int, c: Int) {
            var x0 = (l * k).roundToInt(); var x1 = (r * k).roundToInt()
            if (mirror) { val m0 = w - x1; val m1 = w - x0; x0 = m0; x1 = m1 }
            for (y in (t * k).roundToInt() until (b * k).roundToInt()) for (x in x0 until x1) px[y * w + x] = c
        }
        fun image() = PixelImage(w, h, px)
    }

    private fun canvas(bg: Int = dark, k: Double = 1.0, mirror: Boolean = false) =
        Canvas((1080 * k).roundToInt(), (2229 * k).roundToInt(), bg, k, mirror)

    /** سهم + ☆ + ↪ + اسم (كتل) + subtitle أصغر تحته — نفس رسم اختبار الكاشف القديم. */
    private fun Canvas.viewer(fg: Int = white, nameRight: Int = 450, ghost: Boolean = false, subtitle: Boolean = true, nameLine: Boolean = true) {
        rect(49, 61, 96, 107, fg)          // سهم الرجوع
        rect(718, 62, 770, 111, fg)        // ☆
        rect(858, 64, 902, 105, fg)        // ↪
        if (nameLine) { var x = 148; while (x < nameRight) { rect(x, 31, minOf(x + 30, nameRight), 80, fg); x += 40 } }
        if (subtitle) rect(146, 112, 400, 140, fg)
        if (ghost) rect(470, 40, 690, 75, gray) // نص خلفي رمادي في صف الاسم (مثل «Confirmer le transfert»)
    }

    private fun near(expected: Int, actual: Int, tol: Int = 2) = abs(expected - actual) <= tol

    // ------------------------------------------------------------------ لماذا اختير مربع الاسم

    @Test
    fun ltrViewer_geometryExplainsTheNameBox() {
        val d = HeaderDetector.detect(canvas().apply { viewer() }.image())
        val l = d.layout!!
        assertNull(d.rejection)
        assertEquals(UiDirection.LTR, l.direction)
        assertEquals(Box(49, 61, 96, 107), l.arrow)
        assertEquals(listOf(Box(718, 62, 770, 111), Box(858, 64, 902, 105)), l.icons)
        assertEquals(Box(718, 62, 902, 111), l.iconCluster)
        assertEquals(49, l.iconHeight)
        assertEquals(Box(148, 31, 450, 80), l.name)
        assertEquals(Box(146, 112, 400, 140), l.subtitle)
        assertEquals(718 - 450, l.nameToIconsGap)
        // شريط الرأس: من أعلى الصورة إلى أسفل الـsubtitle + 40.
        assertEquals(Box(0, 0, 1080, 180), l.headerBar)
        // الاسم بين السهم والأيقونات، وفي صف الأيقونات، والـsubtitle تحته.
        assertTrue(l.name!!.l > l.arrow!!.r && l.name!!.r < l.iconCluster.l)
        assertTrue(l.name!!.t < l.icons.first().b && l.name!!.b > l.icons.first().t)
        assertTrue(l.subtitle!!.t >= l.name!!.b)
    }

    @Test
    fun nameImage_isTheNameLineOnly_neverTheSubtitle() {
        val d = HeaderDetector.detect(canvas().apply { viewer() }.image())
        val box = d.nameImage!!.box
        val name = d.layout!!.name!!
        val subtitle = d.layout!!.subtitle!!
        // مربع الاسم بهامش ثلث ارتفاعه، ولا يصل إلى الـsubtitle.
        assertEquals(Box(name.l - 16, name.t - 16, name.r + 16, name.b + 16), box)
        assertTrue("name image $box reaches the subtitle $subtitle", box.b <= subtitle.t)
        assertNotEquals(subtitle, name)
        // داخله: نص أسود (لب القناع) على أبيض.
        val img = d.nameImage!!
        val ink = img.argb[(name.t - box.t + 10) * img.width + (name.l - box.l + 5)] and 0xFF
        val paper = img.argb[2 * img.width + 2] and 0xFF
        assertTrue(ink < 64 && paper == 255)
    }

    @Test
    fun subtitleAlone_isNeverTakenAsTheName() {
        // لا سطر اسم: أول سطر نص هو الـsubtitle، تحت صف الأيقونات ⇒ رفض، لا مربع اسم.
        val d = HeaderDetector.detect(canvas().apply { viewer(nameLine = false) }.image())
        assertEquals(DetectorRejection.NAME_NOT_ON_ICON_ROW, d.rejection)
        assertNull(d.nameImage)
        assertTrue(d.headerFound)
        val candidate = d.layout!!.name!!
        assertTrue("candidate $candidate is below the icon row", candidate.t >= d.layout!!.icons.first().b)
        assertEquals(NameReason.NAME_UNREADABLE, HeaderResult.of(d, null).reason)
    }

    @Test
    fun grayGhostText_isNotPartOfTheName() {
        val d = HeaderDetector.detect(canvas().apply { viewer(ghost = true) }.image())
        assertEquals(Box(148, 31, 450, 80), d.layout!!.name)
        assertNotNull(d.nameImage)
    }

    // ------------------------------------------------------------------ الرفض

    @Test
    fun nameTouchingTheIcons_isRejected_withTheMeasuredGap() {
        // آخر حرف يمتد حتى حافة الأيقونات (xEnd = ☆.l − iconH/4 = 706): اسم مقطوع ⇒ لا مربع اسم.
        val d = HeaderDetector.detect(canvas().apply { viewer(); rect(450, 31, 715, 80, white) }.image())
        assertEquals(DetectorRejection.NAME_TOUCHES_ICONS, d.rejection)
        assertNull(d.nameImage)
        // الحبر يُقاس حتى xEnd (706) فقط: الاسم يصل إليها ⇒ يلامس. المسافة المتبقية حتى ☆ = 12.
        assertEquals(Box(148, 31, 706, 80), d.layout!!.name)
        assertEquals(12, d.layout!!.nameToIconsGap)
    }

    @Test
    fun nameOverlappingTheIcons_isRejected() {
        val d = HeaderDetector.detect(canvas().apply { viewer(); rect(450, 31, 760, 80, white) }.image())
        assertNull(d.nameImage)
        assertTrue(d.rejection == DetectorRejection.NAME_TOUCHES_ICONS || d.rejection == DetectorRejection.NO_ICON_GROUP)
    }

    @Test
    fun nameLineTooSmallForTheIcons_isRejected() {
        val d = HeaderDetector.detect(canvas().apply {
            viewer(nameLine = false)
            var x = 148; while (x < 450) { rect(x, 70, x + 30, 92, white); x += 40 }
        }.image())
        assertEquals(DetectorRejection.NAME_HEIGHT_OUT_OF_RANGE, d.rejection)
        assertNull(d.nameImage)
    }

    @Test
    fun noWhatsAppHeader_isNotDetected() {
        // صورة فارغة، وإيصال بلا رأس (أسطر من كلمات)، وشريط تطبيق دفع بعنوان وأيقونة واحدة.
        val blank = canvas(bg = white).image()
        val receipt = canvas(bg = white).apply {
            for (row in 0 until 12) { var x = 100; while (x < 900) { rect(x, 40 + row * 60, x + 140, 80 + row * 60, dark); x += 170 } }
        }.image()
        val paymentApp = canvas(bg = 0xFF0B5FA5.toInt()).apply {
            rect(40, 60, 100, 110, white)          // ☰
            rect(300, 60, 700, 100, white)          // عنوان
            rect(960, 60, 1010, 110, white)         // أيقونة واحدة
        }.image()
        for (image in listOf(blank, receipt, paymentApp)) {
            val d = HeaderDetector.detect(image)
            assertEquals(DetectorRejection.NO_ICON_GROUP, d.rejection)
            assertNull(d.layout)
            assertNull(d.nameImage)
            assertEquals(NameReason.HEADER_NOT_DETECTED, HeaderResult.of(d, null).reason)
        }
    }

    // ------------------------------------------------------------------ الاتجاه، الثيم، الدقة

    @Test
    fun rtlViewer_isNotDetected_soTheNameIsSafelyNotFound() {
        // واجهة RTL (السهم يمينًا والأيقونات يسارًا، اسم بخط متصل): الكاشف المثبت LTR فقط ⇒ لا رأس، لا اسم.
        val c = canvas(mirror = true).apply {
            viewer(nameLine = false)
            rect(148, 31, 260, 80, white); rect(272, 31, 360, 80, white); rect(372, 31, 450, 80, white)
        }
        val d = HeaderDetector.detect(c.image())
        assertEquals(DetectorRejection.NO_ICON_GROUP, d.rejection)
        assertNull(d.nameImage)
        assertEquals(NameReason.HEADER_NOT_DETECTED, HeaderResult.of(d, null).reason)
    }

    @Test
    fun lightTheme_isDetectedTheSameWay() {
        val d = HeaderDetector.detect(canvas(bg = 0xFFFFFFFF.toInt()).apply { viewer(fg = 0xFF111B21.toInt()) }.image())
        assertNull(d.rejection)
        assertEquals(UiDirection.LTR, d.layout!!.direction)
        assertEquals(Box(148, 31, 450, 80), d.layout!!.name)
        assertEquals(Box(146, 112, 400, 140), d.layout!!.subtitle)
    }

    @Test
    fun widerScreen_scalesTheSameGeometry() {
        // 1440 بكسل: نفس الرسم مكبّرًا 4/3. الثوابت بالبكسل مضروبة في العرض/1080.
        val k = 1440 / 1080.0
        val d = HeaderDetector.detect(canvas(k = k).apply { viewer() }.image())
        val l = d.layout!!
        assertNull(d.rejection)
        assertEquals(1440, l.headerBar.w)
        val name = l.name!!
        assertTrue("name=$name", near((148 * k).roundToInt(), name.l) && near((450 * k).roundToInt(), name.r) && near((31 * k).roundToInt(), name.t) && near((80 * k).roundToInt(), name.b))
        assertTrue("gap=${l.nameToIconsGap}", near((268 * k).roundToInt(), l.nameToIconsGap!!, 3))
        assertEquals(2, l.icons.size)
        assertNotNull(d.nameImage)
    }
}
