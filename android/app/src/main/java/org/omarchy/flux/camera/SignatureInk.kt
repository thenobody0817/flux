package org.omarchy.flux.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ink of a signature, cut out of a photo of paper. [alpha] holds 1
 * byte for each pixel, row by row, from 0 (paper) to 255 (ink). [original]
 * is the mean color of the ink as 0xRRGGBB.
 */
class SignatureInk(val width: Int, val height: Int, val alpha: ByteArray, val original: Int) {
    /** Returns the ARGB pixels of the signature in [rgb], on a transparent background. */
    fun pixels(rgb: Int): IntArray {
        val color = rgb and 0xFFFFFF
        return IntArray(width * height) { i -> ((alpha[i].toInt() and 0xFF) shl 24) or color }
    }
}

/** The colors that the signature can have. [rgb] is null for the color of the pen. */
enum class InkColor(val label: String, val rgb: Int?) {
    Black("Black", 0x000000),
    Blue("Blue", 0x1A3DB0),
    Original("Original", null);

    fun of(ink: SignatureInk): Int = rgb ?: ink.original
}

/** A rectangle in image pixels. */
data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Cuts the ink of a signature out of a photo of paper. The steps remove
 * uneven light, find the split between paper and ink, remove specks and
 * dark areas at the border, and crop to the ink.
 */
object SignatureCut {
    /** The largest image that [extract] reads. The sums of the paper stay below Int.MAX_VALUE. */
    const val MAX_PIXELS = 8_000_000

    /**
     * Returns the ink in [argb], an image of [width] by [height] pixels, or
     * null when the image has no ink.
     */
    fun extract(argb: IntArray, width: Int, height: Int): SignatureInk? {
        require(width > 0 && height > 0 && argb.size == width * height) { "The pixels do not match the size" }
        require(width * height <= MAX_PIXELS) { "The image is too large" }
        val n = width * height
        val luma = IntArray(n) { i ->
            val c = argb[i]
            (77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8
        }
        val dark = darkness(luma, width, height)
        val split = otsu(dark).coerceIn(0.08f, 0.40f)
        val alpha = ramp(dark, split * 0.6f, min(split * 1.4f, 0.95f))
        keepStrokes(alpha, width, height)
        return crop(argb, alpha, width, height)
    }

    /**
     * Maps a frame in a view to the image that the view shows with
     * FILL_CENTER: the image fills the view and the overflow is cut off
     * equally on both sides. [pad] enlarges the frame by that part of its
     * size on each side. The result stays inside the image.
     */
    fun frameInImage(
        left: Float, top: Float, right: Float, bottom: Float,
        viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int,
        pad: Float = 0f,
    ): Crop {
        val scale = max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val offsetX = (viewWidth - imageWidth * scale) / 2
        val offsetY = (viewHeight - imageHeight * scale) / 2
        val padX = (right - left) * pad
        val padY = (bottom - top) * pad
        val x0 = ((left - padX - offsetX) / scale).roundToInt().coerceIn(0, imageWidth - 1)
        val y0 = ((top - padY - offsetY) / scale).roundToInt().coerceIn(0, imageHeight - 1)
        val x1 = ((right + padX - offsetX) / scale).roundToInt().coerceIn(x0 + 1, imageWidth)
        val y1 = ((bottom + padY - offsetY) / scale).roundToInt().coerceIn(y0 + 1, imageHeight)
        return Crop(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Returns how much darker each pixel is than the paper around it, from
     * 0 to 1. The paper is the mean of a large window. A second pass leaves
     * out the pixels that look like ink, so dense strokes do not darken the
     * paper.
     */
    private fun darkness(luma: IntArray, w: Int, h: Int): FloatArray {
        val radius = max(12, max(w, h) / 20)
        val all = IntArray(luma.size) { 1 }
        val first = boxMean(luma, all, w, h, radius, null)
        val paper = IntArray(luma.size) { i -> if (luma[i] * 100 >= first[i] * 85) 1 else 0 }
        val paperLuma = IntArray(luma.size) { i -> luma[i] * paper[i] }
        val second = boxMean(paperLuma, paper, w, h, radius, first)
        return FloatArray(luma.size) { i ->
            val bg = max(second[i], 1f)
            (1f - luma[i] / bg).coerceIn(0f, 1f)
        }
    }

    /**
     * Returns the sum of [values] divided by the sum of [weights] in a
     * square window around each pixel. Where the window has no weight, the
     * result comes from [fallback].
     */
    private fun boxMean(values: IntArray, weights: IntArray, w: Int, h: Int, r: Int, fallback: FloatArray?): FloatArray {
        val sv = integral(values, w, h)
        val sw = integral(weights, w, h)
        val stride = w + 1
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val v = sv[y1 * stride + x1] - sv[y0 * stride + x1] - sv[y1 * stride + x0] + sv[y0 * stride + x0]
                val c = sw[y1 * stride + x1] - sw[y0 * stride + x1] - sw[y1 * stride + x0] + sw[y0 * stride + x0]
                out[y * w + x] = if (c > 0) v.toFloat() / c else fallback?.get(y * w + x) ?: 255f
            }
        }
        return out
    }

    /** Returns the summed-area table of [a], with 1 extra row and column of zeros. */
    private fun integral(a: IntArray, w: Int, h: Int): IntArray {
        val stride = w + 1
        val s = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += a[y * w + x]
                s[(y + 1) * stride + x + 1] = s[y * stride + x + 1] + row
            }
        }
        return s
    }

    /** Returns the split between paper and ink with Otsu's method, from 0 to 1. */
    private fun otsu(dark: FloatArray): Float {
        val bins = 256
        val hist = IntArray(bins)
        for (v in dark) hist[(v * (bins - 1)).roundToInt()]++
        val total = dark.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until bins) sumAll += i * hist[i].toDouble()
        var sumBack = 0.0
        var countBack = 0.0
        var best = 0.0
        var split = 0
        for (i in 0 until bins) {
            countBack += hist[i]
            if (countBack == 0.0) continue
            val countFore = total - countBack
            if (countFore == 0.0) break
            sumBack += i * hist[i].toDouble()
            val meanBack = sumBack / countBack
            val meanFore = (sumAll - sumBack) / countFore
            val between = countBack * countFore * (meanBack - meanFore) * (meanBack - meanFore)
            if (between > best) {
                best = between
                split = i
            }
        }
        return split / (bins - 1f)
    }

    /** Maps darkness to alpha with a smooth step from [low] to [high], so edges stay soft. */
    private fun ramp(dark: FloatArray, low: Float, high: Float): IntArray = IntArray(dark.size) { i ->
        val s = ((dark[i] - low) / (high - low)).coerceIn(0f, 1f)
        (s * s * (3 - 2 * s) * 255).roundToInt()
    }

    /**
     * Keeps the strokes and removes the rest. A stroke is a connected area
     * of pixels with alpha of 128 or more. The step removes specks, and
     * solid areas that touch the border, such as a table, a shadow, or the
     * edge of the paper. Thin strokes fill only a small part of their box.
     * Soft edge pixels stay only next to a stroke that stays.
     */
    private fun keepStrokes(alpha: IntArray, w: Int, h: Int) {
        val n = w * h
        val label = IntArray(n)
        val stack = IntArray(n)
        val minSize = max(4, n / 60_000)
        // keepLabel[k - 1] tells whether the stroke with label k stays.
        val keepLabel = ArrayList<Boolean>()
        var next = 0
        for (start in 0 until n) {
            if (alpha[start] < 128 || label[start] != 0) continue
            next++
            var top = 0
            stack[top++] = start
            label[start] = next
            var size = 0
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var border = false
            while (top > 0) {
                val p = stack[--top]
                size++
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (alpha[q] >= 128 && label[q] == 0) {
                            label[q] = next
                            stack[top++] = q
                        }
                    }
                }
            }
            val boxArea = (maxX - minX + 1).toLong() * (maxY - minY + 1)
            val solid = border && size * 100L > boxArea * 35
            keepLabel.add(size >= minSize && !solid)
        }
        val keep = BooleanArray(n) { i -> label[i] != 0 && keepLabel[label[i] - 1] }
        // Soft pixels stay within 2 pixels of a stroke that stays.
        val near = grow(grow(keep, w, h), w, h)
        for (i in 0 until n) if (!near[i]) alpha[i] = 0
    }

    /** Returns [mask] grown by 1 pixel in the 8 directions. */
    private fun grow(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (ny in max(0, y - 1)..min(h - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(w - 1, x + 1)) out[ny * w + nx] = true
                }
            }
        }
        return out
    }

    /** Crops to the ink with a margin and finds the mean ink color. Returns null without ink. */
    private fun crop(argb: IntArray, alpha: IntArray, w: Int, h: Int): SignatureInk? {
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var r = 0L
        var g = 0L
        var b = 0L
        var core = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = alpha[y * w + x]
                if (a == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (a >= 230) {
                    val c = argb[y * w + x]
                    r += c shr 16 and 0xFF
                    g += c shr 8 and 0xFF
                    b += c and 0xFF
                    core++
                }
            }
        }
        if (maxX < 0 || core == 0L) return null
        val margin = max(8, max(maxX - minX, maxY - minY) / 40)
        val left = minX - margin
        val top = minY - margin
        val cw = maxX - minX + 1 + 2 * margin
        val ch = maxY - minY + 1 + 2 * margin
        val out = ByteArray(cw * ch)
        for (y in 0 until ch) {
            val sy = top + y
            if (sy < 0 || sy >= h) continue
            for (x in 0 until cw) {
                val sx = left + x
                if (sx < 0 || sx >= w) continue
                out[y * cw + x] = alpha[sy * w + sx].toByte()
            }
        }
        val mean = ((r / core).toInt() shl 16) or ((g / core).toInt() shl 8) or (b / core).toInt()
        return SignatureInk(cw, ch, out, mean)
    }
}
