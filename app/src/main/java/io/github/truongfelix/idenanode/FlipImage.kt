package io.github.truongfelix.idenanode

import kotlin.random.Random

/*
 * The anti-AI work on a flip's frames, as idena-desktop does it (renderer/screens/flips/utils.js,
 * protectFlipImage and getAdversarialImage), on ARGB pixels so that it runs and is tested without
 * Android:
 * - every frame gets a noise that people do not notice but that changes it for a model: the frame,
 *   grey and blurred, is cut into regions (watershed); about a fifth of the pixels on the region borders
 *   take a color of the frame's own palette; the hue turns by 20 to 49 degrees; the frame may be mirrored;
 * - the nonsense ("adversarial") frame is a collage of four pictures along the regions of one of them.
 * The desktop's watershed comes from an obfuscated library; this one is the textbook algorithm (Vincent and
 * Soille 1991, as simplified by Roerdink and Meijster), 4-connected, on 256 grey levels.
 */

/** The grey level (0 to 255) of each pixel: the mean of red, green and blue. */
fun grayLevels(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
    val c = pixels[i]
    (((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)) / 3
}

/** [values] blurred by three box blurs of [radius] (close to a Gaussian blur), each way. */
fun blurLevels(values: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (radius <= 0) return values.copyOf()
    var current = values.copyOf()
    val tmp = IntArray(values.size)
    repeat(3) {
        for (y in 0 until height) boxLine(current, tmp, y * width, 1, width, radius)
        for (x in 0 until width) boxLine(tmp, current, x, width, height, radius)
    }
    return current
}

/** One line of a box blur: [count] values from [start], [step] apart; the edges repeat. */
private fun boxLine(src: IntArray, dst: IntArray, start: Int, step: Int, count: Int, radius: Int) {
    val window = 2 * radius + 1
    fun at(i: Int) = src[start + i.coerceIn(0, count - 1) * step]
    var sum = 0
    for (i in -radius..radius) sum += at(i)
    for (i in 0 until count) {
        dst[start + i * step] = sum / window
        sum += at(i + radius + 1) - at(i - radius)
    }
}

/** A queue of pixel indexes for the watershed. */
private class IntQueue(capacity: Int) {
    private val items = IntArray(capacity)
    private var head = 0
    private var size = 0

    val isEmpty: Boolean
        get() = size == 0

    fun add(value: Int) {
        check(size < items.size) { "queue full" }
        items[(head + size) % items.size] = value
        size++
    }

    fun remove(): Int {
        val value = items[head]
        head = (head + 1) % items.size
        size--
        return value
    }
}

/** The label of a watershed line pixel in [watershed]'s result; regions are numbered from 1. */
const val WATERSHED_LINE = 0

/**
 * The regions of [levels] (grey levels 0 to 255, [width] x [height]) by immersion: each local minimum
 * floods a region, numbered from 1; where two floods meet, a line ([WATERSHED_LINE]).
 */
fun watershed(levels: IntArray, width: Int, height: Int): IntArray {
    val n = width * height
    val init = -1
    val mask = -2
    val fictitious = -1
    val labels = IntArray(n) { init }
    val dist = IntArray(n)
    // The pixels sorted by level (counting sort).
    val start = IntArray(257)
    for (v in levels) start[v.coerceIn(0, 255) + 1]++
    for (i in 1..256) start[i] += start[i - 1]
    val fill = start.copyOf()
    val sorted = IntArray(n)
    for (p in 0 until n) sorted[fill[levels[p].coerceIn(0, 255)]++] = p
    val queue = IntQueue(n + 2)
    val neighbors = IntArray(4)
    fun neighborsOf(p: Int): Int {
        val x = p % width
        var count = 0
        if (x > 0) neighbors[count++] = p - 1
        if (x < width - 1) neighbors[count++] = p + 1
        if (p >= width) neighbors[count++] = p - width
        if (p < n - width) neighbors[count++] = p + width
        return count
    }
    var current = 0
    for (level in 0 until 256) {
        val from = start[level]
        val to = start[level + 1]
        if (from == to) continue
        // The pixels of this level next to a region or a line start the flooding.
        for (i in from until to) {
            val p = sorted[i]
            labels[p] = mask
            val count = neighborsOf(p)
            for (k in 0 until count) {
                val q = neighbors[k]
                if (labels[q] > 0 || labels[q] == WATERSHED_LINE) {
                    dist[p] = 1
                    queue.add(p)
                    break
                }
            }
        }
        var currentDist = 1
        queue.add(fictitious)
        while (true) {
            var p = queue.remove()
            if (p == fictitious) {
                if (queue.isEmpty) break
                queue.add(fictitious)
                currentDist++
                p = queue.remove()
            }
            val count = neighborsOf(p)
            for (k in 0 until count) {
                val q = neighbors[k]
                if (dist[q] < currentDist && (labels[q] > 0 || labels[q] == WATERSHED_LINE)) {
                    if (labels[q] > 0) {
                        if (labels[p] == mask || labels[p] == WATERSHED_LINE) labels[p] = labels[q]
                        else if (labels[p] != labels[q]) labels[p] = WATERSHED_LINE
                    } else if (labels[p] == mask) {
                        labels[p] = WATERSHED_LINE
                    }
                } else if (labels[q] == mask && dist[q] == 0) {
                    dist[q] = currentDist + 1
                    queue.add(q)
                }
            }
        }
        // What is left of this level is new minima: a new region each.
        for (i in from until to) {
            val p = sorted[i]
            dist[p] = 0
            if (labels[p] != mask) continue
            current++
            labels[p] = current
            queue.add(p)
            while (!queue.isEmpty) {
                val r = queue.remove()
                val count = neighborsOf(r)
                for (k in 0 until count) {
                    val q = neighbors[k]
                    if (labels[q] == mask) {
                        labels[q] = current
                        queue.add(q)
                    }
                }
            }
        }
    }
    return labels
}

/** Whether pixel [p] is on a region border of [labels]: a line, or next to another region (right or below). */
fun isBorder(labels: IntArray, width: Int, p: Int): Boolean {
    val label = labels[p]
    if (label == WATERSHED_LINE) return true
    if (p % width < width - 1 && labels[p + 1] != label) return true
    return p + width < labels.size && labels[p + width] != label
}

/**
 * The main colors of [pixels], most frequent first, at most [count]: the pixels are grouped by 4 bits
 * of each color, and each group gives its mean color (the desktop uses the extract-colors library).
 */
fun palette(pixels: IntArray, count: Int = 8): List<Int> {
    val sums = Array(4096) { LongArray(4) }
    for (c in pixels) {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        val sum = sums[(r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)]
        sum[0] += r.toLong(); sum[1] += g.toLong(); sum[2] += b.toLong(); sum[3]++
    }
    return sums.filter { it[3] > 0 }.sortedByDescending { it[3] }.take(count).map { s ->
        rgb((s[0] / s[3]).toInt(), (s[1] / s[3]).toInt(), (s[2] / s[3]).toInt())
    }
}

private fun rgb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b

/** [color] with its hue turned by [degrees]. */
fun turnHue(color: Int, degrees: Float): Int {
    val r = ((color shr 16) and 0xff) / 255f
    val g = ((color shr 8) and 0xff) / 255f
    val b = (color and 0xff) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta == 0f) return color or (0xff shl 24)
    var hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    hue = ((hue + degrees) % 360f + 360f) % 360f
    val saturation = delta / max
    val c = max * saturation
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2f - 1))
    val m = max - c
    val (r1, g1, b1) = when ((hue / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return rgb(channel(r1), channel(g1), channel(b1))
}

/** [pixels] ([width] wide) mirrored left to right. */
fun mirrored(pixels: IntArray, width: Int): IntArray = IntArray(pixels.size) { i ->
    val x = i % width
    pixels[i - x + (width - 1 - x)]
}

/** The share of the border pixels that take a palette color (desktop: 20 %). */
const val NOISE_SHARE = 0.2

/**
 * [pixels] ([width] x [height]) with the anti-AI noise (desktop protectFlipImage): a fifth of the region
 * border pixels of the grey, blurred frame recolored from its palette (not its main color when it has
 * several), the hue turned by 20 to 49 degrees, mirrored left to right one time in two.
 */
fun protectFrame(pixels: IntArray, width: Int, height: Int, random: Random = Random.Default): IntArray {
    val labels = watershed(blurLevels(grayLevels(pixels), width, height, 2), width, height)
    val colors = palette(pixels)
    val out = pixels.copyOf()
    for (p in out.indices) {
        if (colors.isEmpty() || random.nextDouble() >= NOISE_SHARE || !isBorder(labels, width, p)) continue
        out[p] = if (colors.size == 1) colors[0] else colors[1 + random.nextInt(colors.size - 1)]
    }
    val degrees = (20 + random.nextInt(30)).toFloat()
    for (p in out.indices) out[p] = turnHue(out[p], degrees)
    return if (random.nextBoolean()) mirrored(out, width) else out
}

/**
 * The nonsense frame from four pictures of the same size (desktop getAdversarialImage): one of them
 * with at least four regions (at random; desktop: any), grey and much blurred, is cut into regions; each region shows one of the four pictures, every picture
 * at least once when there are enough regions; the region borders are softened.
 */
fun adversarialFrame(sources: List<IntArray>, width: Int, height: Int, random: Random = Random.Default): IntArray {
    require(sources.size == FLIP_FRAMES && sources.all { it.size == width * height }) { "four pictures of $width x $height" }
    // The regions of each picture; the cut follows one with enough of them (a plain picture has one).
    val cuts = sources.map { watershed(blurLevels(grayLevels(it), width, height, 8), width, height) }
    val counts = cuts.map { it.maxOrNull() ?: 0 }
    val enough = counts.indices.filter { counts[it] >= FLIP_FRAMES }
    val base = if (enough.isNotEmpty()) enough[random.nextInt(enough.size)] else counts.indices.maxBy { counts[it] }
    val labels = cuts[base]
    val regions = counts[base] + 1
    // Each picture once first, then at random.
    val firsts = (0 until FLIP_FRAMES).shuffled(random)
    val pictureOf = IntArray(regions) { region -> if (region in 1..FLIP_FRAMES) firsts[region - 1] else random.nextInt(FLIP_FRAMES) }
    val out = IntArray(width * height) { p ->
        val label = labels[p]
        if (label > 0) sources[pictureOf[label]][p] else sources[base][p]
    }
    // Soften the borders: each border pixel takes the mean of its 3 x 3 neighbours.
    val soft = out.copyOf()
    for (p in out.indices) {
        if (!isBorder(labels, width, p)) continue
        val x = p % width
        val y = p / width
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx !in 0 until width || ny !in 0 until height) continue
            val c = out[ny * width + nx]
            r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff; n++
        }
        soft[p] = rgb(r / n, g / n, b / n)
    }
    return soft
}
