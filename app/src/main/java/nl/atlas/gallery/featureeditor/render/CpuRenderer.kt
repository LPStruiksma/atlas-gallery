package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A block of input pixels: premultiplied 8-bit RGBA (the byte order of an Android `ARGB_8888`
 * buffer), encoded in [space]. Texel (0, 0) covers full-resolution source pixels starting at
 * ([originX], [originY]); [scale] is texels per source pixel (1 for a tile of the full photo,
 * below 1 for a downsampled preview proxy or a subsampled region decode).
 */
class RgbaRegion(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val originX: Float = 0f,
    val originY: Float = 0f,
    val scale: Float = 1f,
    val space: PixelSpace = PixelSpace.SRGB,
) {
    init {
        require(width > 0 && height > 0) { "Region must not be empty" }
        require(data.size == width * height * 4) { "Region data has the wrong length" }
    }
}

/**
 * Reference pixel backend: executes a [SegmentProgram] on the CPU, one output pixel at a time.
 *
 * It is the oracle the GPU backend is tested against, the backend on API 29-32 (no AGSL), and
 * what JVM tests use to prove tiling, colour and LUT behaviour without a device. The maths is
 * the same as `AgslRenderer.SHADER`, statement for statement.
 */
object CpuRenderer {
    /**
     * Renders [tile] of [program]'s output from [region] (which must contain
     * `program.sourceRegionFor(tile)`), as premultiplied RGBA bytes encoded in [outputSpace].
     */
    fun render(program: SegmentProgram, region: RgbaRegion, tile: IntRect, lut: Lut3D?, outputSpace: PixelSpace): ByteArray {
        require(program.lut == null || lut != null) { "Program needs its LUT" }
        val out = ByteArray(tile.width * tile.height * 4)
        val m = program.outputToSourceFloats
        val adjust = program.adjust
        val strength = program.lut?.strength?.toFloat() ?: 0f
        val raw = program.rawValues
        val sampler = Sampler(region, m, raw)
        val c = FloatArray(4)
        val fromWorking = outputSpace.fromWorking
        val enc = FloatArray(3)
        val defR = program.definitionRadius
        val useDef = adjust[AdjustParam.DEFINITION] != 0f
        val useSharp = adjust[AdjustParam.SHARPNESS] != 0f
        val sw = program.sourceWidth.toFloat(); val sh = program.sourceHeight.toFloat()
        val ow = program.outputWidth.toFloat(); val oh = program.outputHeight.toFloat()
        for (y in tile.top until tile.bottom) for (x in tile.left until tile.right) {
            val ox = x + 0.5f; val oy = y + 0.5f
            val o = ((y - tile.top) * tile.width + (x - tile.left)) * 4
            val w = m[6] * ox + m[7] * oy + m[8]
            val sx = (m[0] * ox + m[1] * oy + m[2]) / w
            val sy = (m[3] * ox + m[4] * oy + m[5]) / w
            if (!(w > 0f) || sx < 0f || sy < 0f || sx > sw || sy > sh) continue // transparent
            sampler.sample(sx, sy, c)
            if (c[3] <= 0f) continue
            var defDetail = 0f
            var sharpDetail = 0f
            if (useDef || useSharp) {
                val yc = ColourScience.encode(ColourOps.luma(c))
                if (useDef) {
                    var sum = 0f
                    for (k in 0 until 8) sum += sampler.lumaAt(ox + defR * DIRS_X[k], oy + defR * DIRS_Y[k])
                    defDetail = yc - sum / 8f
                }
                if (useSharp) {
                    val sum = sampler.lumaAt(ox + 1f, oy) + sampler.lumaAt(ox - 1f, oy) +
                        sampler.lumaAt(ox, oy + 1f) + sampler.lumaAt(ox, oy - 1f)
                    sharpDetail = yc - sum / 4f
                }
            }
            if (!adjust.isIdentity) ColourOps.apply(adjust, c, ox / ow, oy / oh, defDetail, sharpDetail)
            if (lut != null && strength > 0f) lut.apply(c, strength)
            val a = c[3]
            if (raw) {
                out[o] = unorm(c[0], a); out[o + 1] = unorm(c[1], a); out[o + 2] = unorm(c[2], a)
            } else {
                ColourScience.apply(fromWorking, c[0], c[1], c[2], enc)
                out[o] = unorm(ColourScience.encode(enc[0]), a)
                out[o + 1] = unorm(ColourScience.encode(enc[1]), a)
                out[o + 2] = unorm(ColourScience.encode(enc[2]), a)
            }
            out[o + 3] = unorm(1f, a)
        }
        return out
    }

    /** Clamp to 0..1, premultiply by [a], round to nearest 8-bit, as GPU colour writes do. */
    private fun unorm(v: Float, a: Float): Byte = (min(1f, max(0f, v)) * a * 255f + 0.5f).toInt().toByte()

    /** Bilinear sampling of [region] in linear Display P3, premultiplied blend. */
    internal class Sampler(private val region: RgbaRegion, private val m: FloatArray, private val raw: Boolean = false) {
        private val toWorking = if (raw) ColourScience.IDENTITY else region.space.toWorking
        private val tmp = FloatArray(4)
        private val tex = FloatArray(4)
        private val decodeTable = FloatArray(256) { ColourScience.decode(it / 255f) }

        /** Straight-alpha linear P3 colour at source pixel position ([sx], [sy]) into [out]. */
        fun sample(sx: Float, sy: Float, out: FloatArray) {
            val px = (sx - region.originX) * region.scale - 0.5f
            val py = (sy - region.originY) * region.scale - 0.5f
            val fx0 = floor(px); val fy0 = floor(py)
            val fx = px - fx0; val fy = py - fy0
            val x0 = fx0.toInt(); val y0 = fy0.toInt()
            out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f
            accumulate(x0, y0, (1f - fx) * (1f - fy), out)
            accumulate(x0 + 1, y0, fx * (1f - fy), out)
            accumulate(x0, y0 + 1, (1f - fx) * fy, out)
            accumulate(x0 + 1, y0 + 1, fx * fy, out)
            val a = out[3]
            if (a > 0f) { out[0] /= a; out[1] /= a; out[2] /= a }
        }

        /** Encoded P3 luma of the warped picture at output position ([ox], [oy]). */
        fun lumaAt(ox: Float, oy: Float): Float {
            val w = m[6] * ox + m[7] * oy + m[8]
            val sx = (m[0] * ox + m[1] * oy + m[2]) / w
            val sy = (m[3] * ox + m[4] * oy + m[5]) / w
            sample(sx, sy, tmp)
            return ColourScience.encode(ColourOps.luma(tmp))
        }

        private fun accumulate(x: Int, y: Int, weight: Float, out: FloatArray) {
            val cx = min(region.width - 1, max(0, x))
            val cy = min(region.height - 1, max(0, y))
            val i = (cy * region.width + cx) * 4
            val d = region.data
            val a = (d[i + 3].toInt() and 0xff) / 255f
            if (a <= 0f) return
            // Unpremultiply in encoded space, decode, convert primaries, re-premultiply.
            val r = linear((d[i].toInt() and 0xff), a)
            val g = linear((d[i + 1].toInt() and 0xff), a)
            val b = linear((d[i + 2].toInt() and 0xff), a)
            ColourScience.apply(toWorking, r, g, b, tex)
            out[0] += tex[0] * a * weight
            out[1] += tex[1] * a * weight
            out[2] += tex[2] * a * weight
            out[3] += a * weight
        }

        private fun linear(v: Int, a: Float): Float = when {
            raw -> if (a >= 1f) v / 255f else min(1f, v / 255f / a)
            a >= 1f -> decodeTable[v]
            else -> ColourScience.decode(min(1f, v / 255f / a))
        }
    }

    // Definition taps: eight directions around the pixel, at the definition radius.
    private val DIRS_X = FloatArray(8) { cos(it * Math.PI / 4).toFloat() }
    private val DIRS_Y = FloatArray(8) { sin(it * Math.PI / 4).toFloat() }
}
