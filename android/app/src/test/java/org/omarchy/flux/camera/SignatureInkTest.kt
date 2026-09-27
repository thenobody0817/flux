package org.omarchy.flux.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class SignatureInkTest {
    private val w = 800
    private val h = 320
    private val inkRgb = 0x283278

    /** Paper that goes from bright on the left to a shadow on the right, with noise. */
    private fun paper(seed: Long = 1): IntArray {
        val random = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w
            val base = 235 - 80 * x / w
            val v = (base + random.nextInt(13) - 6).coerceIn(0, 255)
            rgb(v, v, (v - 8).coerceAtLeast(0))
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Draws a filled disc of ink. */
    private fun dot(img: IntArray, cx: Int, cy: Int, radius: Int, color: Int = inkRgb) {
        for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius) {
            if (x !in 0 until w || y !in 0 until h) continue
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radius * radius) img[y * w + x] = (0xFF shl 24) or color
        }
    }

    /** Draws a wave from x = 200 to 600 around y = 160, 3 pixels thick on each side. Returns its box. */
    private fun stroke(img: IntArray): IntArray {
        var minY = h
        var maxY = 0
        for (x in 200..600) {
            val y = 160 + (40 * sin(x / 30.0)).toInt()
            dot(img, x, y, 3)
            minY = minOf(minY, y - 3)
            maxY = maxOf(maxY, y + 3)
        }
        return intArrayOf(197, minY, 603, maxY)
    }

    private fun margin(box: IntArray) = max(8, max(box[2] - box[0], box[3] - box[1]) / 40)

    @Test
    fun keepsTheStrokeOnUnevenPaper() {
        val img = paper()
        val box = stroke(img)
        val found = SignatureCut.extract(img, w, h)
        assertNotNull(found)
        val ink = found!!
        val m = margin(box)
        assertEquals(box[2] - box[0] + 1 + 2 * m, ink.width)
        assertEquals(box[3] - box[1] + 1 + 2 * m, ink.height)
        // The stroke is opaque and the paper around it is transparent.
        val opaque = ink.alpha.count { (it.toInt() and 0xFF) == 255 }
        val visible = ink.alpha.count { it.toInt() != 0 }
        assertTrue("opaque pixels: $opaque", opaque > 2000)
        assertTrue("visible pixels: $visible of ${ink.alpha.size}", visible < ink.alpha.size / 3)
        assertEquals(0, ink.alpha[0].toInt())
        assertEquals(0, ink.alpha[ink.alpha.size - 1].toInt())
    }

    @Test
    fun findsTheColorOfThePen() {
        val img = paper()
        stroke(img)
        val ink = SignatureCut.extract(img, w, h)!!
        assertTrue(abs((ink.original shr 16 and 0xFF) - 0x28) <= 12)
        assertTrue(abs((ink.original shr 8 and 0xFF) - 0x32) <= 12)
        assertTrue(abs((ink.original and 0xFF) - 0x78) <= 12)
    }

    @Test
    fun removesSpecks() {
        val clean = paper()
        stroke(clean)
        val expected = SignatureCut.extract(clean, w, h)!!

        // Specks away from the stroke. A kept speck would make the crop larger.
        val img = paper()
        val box = stroke(img)
        val random = Random(7)
        var specks = 0
        while (specks < 60) {
            val x = random.nextInt(w)
            val y = random.nextInt(h)
            if (x in box[0] - 10..box[2] + 10 && y in box[1] - 10..box[3] + 10) continue
            img[y * w + x] = rgb(20, 20, 20)
            specks++
        }
        val ink = SignatureCut.extract(img, w, h)!!
        assertEquals(expected.width, ink.width)
        assertEquals(expected.height, ink.height)
    }

    @Test
    fun removesADarkAreaAtTheBorder() {
        val clean = paper()
        stroke(clean)
        val expected = SignatureCut.extract(clean, w, h)!!

        // A table at the bottom of the photo.
        val img = paper()
        stroke(img)
        for (y in h - 40 until h) for (x in 0 until w) img[y * w + x] = rgb(50, 40, 30)
        val ink = SignatureCut.extract(img, w, h)!!
        assertEquals(expected.width, ink.width)
        assertEquals(expected.height, ink.height)
    }

    @Test
    fun removesTheEdgeOfThePaper() {
        val clean = paper()
        stroke(clean)
        val expected = SignatureCut.extract(clean, w, h)!!

        // A thin dark sliver of the table next to the right edge of the paper.
        val img = paper()
        stroke(img)
        for (y in 0 until h) for (x in w - 6 until w) img[y * w + x] = rgb(60, 55, 50)
        val ink = SignatureCut.extract(img, w, h)!!
        assertEquals(expected.width, ink.width)
        assertEquals(expected.height, ink.height)
    }

    @Test
    fun blankPaperHasNoInk() {
        assertNull(SignatureCut.extract(paper(3), w, h))
    }

    @Test
    fun pixelsUseTheColor() {
        val img = paper()
        stroke(img)
        val ink = SignatureCut.extract(img, w, h)!!
        val px = ink.pixels(InkColor.Blue.of(ink))
        assertEquals(ink.width * ink.height, px.size)
        for (i in px.indices) {
            assertEquals(0x1A3DB0, px[i] and 0xFFFFFF)
            assertEquals(ink.alpha[i].toInt() and 0xFF, px[i] ushr 24)
        }
        assertEquals(ink.original, InkColor.Original.of(ink))
        assertEquals(0x000000, InkColor.Black.of(ink))
    }

    @Test
    fun frameInAPortraitImage() {
        // The image is wider than the view, so FILL_CENTER cuts off its sides.
        val crop = SignatureCut.frameInImage(60f, 555f, 940f, 907f, 1000, 1500, 3000, 4000)
        assertEquals(Crop(327, 1480, 2346, 939), crop)
    }

    @Test
    fun frameInALandscapeImage() {
        // The image is taller than the view, so FILL_CENTER cuts off its top and bottom.
        val crop = SignatureCut.frameInImage(100f, 100f, 1500f, 500f, 1600, 900, 1600, 1200)
        assertEquals(Crop(100, 250, 1400, 400), crop)
    }

    @Test
    fun framePadsAndStaysInTheImage() {
        assertEquals(Crop(80, 90, 240, 120), SignatureCut.frameInImage(100f, 100f, 300f, 200f, 800, 600, 800, 600, pad = 0.1f))
        assertEquals(Crop(0, 0, 800, 600), SignatureCut.frameInImage(-50f, -50f, 900f, 700f, 800, 600, 800, 600))
    }
}
