package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.annotation.RequiresApi
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The editor's GPU pixel pass: one AGSL [RuntimeShader] that executes a [SegmentProgram]
 * (geometry warp, the twelve colour controls, 3D LUT) per output pixel.
 *
 * Inputs are bound with [RuntimeShader.setInputBuffer], so the GPU reads the region and LUT bytes
 * raw, without Android's colour management. The shader does its own colour work in linear
 * Display P3 ([ColourScience]) and writes encoded values for the output space, exactly as
 * [CpuRenderer] does; tests hold the two within a few 8-bit steps.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AgslProgram {
    private val shader = RuntimeShader(SHADER)
    private var lutKey: Lut3D? = null
    private var lutShader: BitmapShader = bufferShader(packLut(Lut3D.identity(2)))

    /** Points the shader at [program], [region], [lut] and [outputSpace]; returns it for drawing. */
    fun bind(program: SegmentProgram, region: SourceRegion, lut: Lut3D?, outputSpace: PixelSpace): RuntimeShader {
        val m = program.outputToSourceFloats
        shader.setFloatUniform("uM0", m[0], m[1], m[2])
        shader.setFloatUniform("uM1", m[3], m[4], m[5])
        shader.setFloatUniform("uM2", m[6], m[7], m[8])
        shader.setFloatUniform("uSrcSize", program.sourceWidth.toFloat(), program.sourceHeight.toFloat())
        shader.setFloatUniform("uRegion", region.originX, region.originY, region.scale, if (program.rawValues) 1f else 0f)
        shader.setFloatUniform("uRegionSize", region.bitmap.width.toFloat(), region.bitmap.height.toFloat())
        rows("uToW", if (program.rawValues) ColourScience.IDENTITY else region.space.toWorking)
        rows("uFromW", if (program.rawValues) ColourScience.IDENTITY else outputSpace.fromWorking)
        rows("uP2S", ColourScience.P3_TO_SRGB)
        rows("uS2P", ColourScience.SRGB_TO_P3)
        val a = program.adjust.values
        shader.setFloatUniform("uAdjA", a[0], a[1], a[2], a[3])
        shader.setFloatUniform("uAdjB", a[4], a[5], a[6], a[7])
        shader.setFloatUniform("uAdjC", a[8], a[9], a[10], a[11])
        shader.setFloatUniform("uOutSize", program.outputWidth.toFloat(), program.outputHeight.toFloat())
        shader.setFloatUniform("uDefRadius", program.definitionRadius)
        val strength = if (lut != null && program.lut != null) program.lut.strength.toFloat() else 0f
        if (lut != null && lut !== lutKey) {
            lutShader = bufferShader(packLut(lut))
            lutKey = lut
        }
        shader.setFloatUniform("uLut", (lut ?: Lut3D.identity(2)).size.toFloat(), strength)
        shader.setInputBuffer("uLutTex", lutShader)
        shader.setInputBuffer("uSource", bufferShader(region.bitmap))
        return shader
    }

    private fun rows(prefix: String, m: FloatArray) {
        shader.setFloatUniform("${prefix}0", m[0], m[1], m[2])
        shader.setFloatUniform("${prefix}1", m[3], m[4], m[5])
        shader.setFloatUniform("${prefix}2", m[6], m[7], m[8])
    }

    companion object {
        init {
            // The shader indexes adjust values by AdjustParam order; fail fast if that changes.
            check(AdjustParam.entries.map { it.key } == listOf(
                "exposure", "brightness", "contrast", "highlights", "shadows", "temperature",
                "tint", "saturation", "hue", "definition", "sharpness", "vignette",
            ))
        }

        private fun bufferShader(bitmap: Bitmap) =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_NEAREST
            }

        /** Packs a LUT as an `RGBA_F16` strip: texel (r + b * size, g) holds entry (r, g, b). */
        fun packLut(lut: Lut3D): Bitmap {
            val n = lut.size
            val bitmap = Bitmap.createBitmap(n * n, n, Bitmap.Config.RGBA_F16)
            val buf = ByteBuffer.allocateDirect(n * n * n * 8).order(ByteOrder.nativeOrder())
            val px = FloatArray(3)
            for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
                val i = ((b * n + g) * n + r) * 3
                px[0] = lut.data[i]; px[1] = lut.data[i + 1]; px[2] = lut.data[i + 2]
                buf.putShort(Half.toHalf(px[0])); buf.putShort(Half.toHalf(px[1]))
                buf.putShort(Half.toHalf(px[2])); buf.putShort(Half.toHalf(1f))
            }
            buf.rewind()
            bitmap.copyPixelsFromBuffer(buf)
            return bitmap
        }

        /**
         * Mirrors [CpuRenderer] and [ColourOps] statement for statement. Constants here must match
         * [ColourOps] (the JVM test `AgslShaderSourceTest` checks they do).
         */
        val SHADER: String = """
uniform shader uSource;
uniform shader uLutTex;
uniform float3 uM0; uniform float3 uM1; uniform float3 uM2;
uniform float2 uSrcSize;
uniform float4 uRegion;
uniform float2 uRegionSize;
uniform float3 uToW0; uniform float3 uToW1; uniform float3 uToW2;
uniform float3 uFromW0; uniform float3 uFromW1; uniform float3 uFromW2;
uniform float3 uP2S0; uniform float3 uP2S1; uniform float3 uP2S2;
uniform float3 uS2P0; uniform float3 uS2P1; uniform float3 uS2P2;
uniform float4 uAdjA; uniform float4 uAdjB; uniform float4 uAdjC;
uniform float2 uOutSize;
uniform float uDefRadius;
uniform float2 uLut;

const float3 LUMA = float3(0.22897457, 0.6917385, 0.07928691); // guard: allow-public (colour-space constant)

float dec(float e) {
    float a = abs(e);
    float l = a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4);
    return e < 0.0 ? -l : l;
}
float enc(float l) {
    float a = abs(l);
    float e = a <= 0.0031308 ? a * 12.92 : 1.055 * pow(a, 1.0 / 2.4) - 0.055;
    return l < 0.0 ? -e : e;
}
float3 dec3(float3 e) { return float3(dec(e.r), dec(e.g), dec(e.b)); }
float3 enc3(float3 l) { return float3(enc(l.r), enc(l.g), enc(l.b)); }
float3 mul3(float3 r0, float3 r1, float3 r2, float3 c) { return float3(dot(r0, c), dot(r1, c), dot(r2, c)); }
float spow(float x, float p) { return x < 0.0 ? -pow(-x, p) : pow(x, p); }

float4 texel(float2 i) {
    i = clamp(i, float2(0.0), uRegionSize - 1.0);
    float4 t = float4(uSource.eval(i + 0.5));
    float a = t.a;
    if (a <= 0.0) { return float4(0.0); }
    float3 e = a >= 1.0 ? t.rgb : min(t.rgb / a, float3(1.0));
    float3 l = uRegion.w > 0.5 ? e : mul3(uToW0, uToW1, uToW2, dec3(e));
    return float4(l * a, a);
}

float4 sampleSrc(float2 s) {
    float2 p = (s - uRegion.xy) * uRegion.z - 0.5;
    float2 f0 = floor(p);
    float2 f = p - f0;
    float4 acc = texel(f0) * ((1.0 - f.x) * (1.0 - f.y))
        + texel(f0 + float2(1.0, 0.0)) * (f.x * (1.0 - f.y))
        + texel(f0 + float2(0.0, 1.0)) * ((1.0 - f.x) * f.y)
        + texel(f0 + float2(1.0, 1.0)) * (f.x * f.y);
    if (acc.a > 0.0) { acc.rgb = acc.rgb / acc.a; }
    return acc;
}

float2 toSource(float2 o) {
    float3 h = float3(o, 1.0);
    return float2(dot(uM0, h), dot(uM1, h)) / dot(uM2, h);
}

float lumaAt(float2 o) { return enc(dot(LUMA, sampleSrc(toSource(o)).rgb)); }

float3 scaleBy(float3 c, float k) { return c * k; }

float3 addDetail(float3 c, float delta) {
    float y = dot(LUMA, c);
    if (y <= 0.000001) { return c; }
    float target = dec(enc(y) + delta);
    return c * (max(0.0, target) / y);
}

float3 rotateHue(float3 c, float theta) {
    float cs = cos(theta);
    float sn = sin(theta);
    float k = 0.57735026;
    float d = (c.r + c.g + c.b) * k;
    float3 x = float3(k * (c.b - c.g), k * (c.r - c.b), k * (c.g - c.r));
    float t = d * k * (1.0 - cs);
    return c * cs + x * sn + float3(t);
}

float3 lutTexel(float x, float y, float z) {
    return float4(uLutTex.eval(float2(x + z * uLut.x + 0.5, y + 0.5))).rgb;
}

float3 applyLut(float3 c, float strength) {
    float3 t = mul3(uP2S0, uP2S1, uP2S2, c);
    float3 e = enc3(t);
    float3 k = clamp(e, 0.0, 1.0);
    float n1 = uLut.x - 1.0;
    float3 p = k * n1;
    float3 p0 = clamp(floor(p), float3(0.0), float3(uLut.x - 2.0));
    float3 f = p - p0;
    float3 c000 = lutTexel(p0.x, p0.y, p0.z);
    float3 c100 = lutTexel(p0.x + 1.0, p0.y, p0.z);
    float3 c010 = lutTexel(p0.x, p0.y + 1.0, p0.z);
    float3 c110 = lutTexel(p0.x + 1.0, p0.y + 1.0, p0.z);
    float3 c001 = lutTexel(p0.x, p0.y, p0.z + 1.0);
    float3 c101 = lutTexel(p0.x + 1.0, p0.y, p0.z + 1.0);
    float3 c011 = lutTexel(p0.x, p0.y + 1.0, p0.z + 1.0);
    float3 c111 = lutTexel(p0.x + 1.0, p0.y + 1.0, p0.z + 1.0);
    float3 c00 = c000 + (c100 - c000) * f.x;
    float3 c10 = c010 + (c110 - c010) * f.x;
    float3 c01 = c001 + (c101 - c001) * f.x;
    float3 c11 = c011 + (c111 - c011) * f.x;
    float3 c0 = c00 + (c10 - c00) * f.y;
    float3 c1 = c01 + (c11 - c01) * f.y;
    float3 looked = c0 + (c1 - c0) * f.z + (e - k);
    float3 mixed = dec3(e + (looked - e) * strength);
    return mul3(uS2P0, uS2P1, uS2P2, mixed);
}

half4 main(float2 o) {
    float3 h = float3(o, 1.0);
    float w = dot(uM2, h);
    if (w <= 0.0) { return half4(0.0); }
    float2 s = float2(dot(uM0, h), dot(uM1, h)) / w;
    if (s.x < 0.0 || s.y < 0.0 || s.x > uSrcSize.x || s.y > uSrcSize.y) { return half4(0.0); }
    float4 src = sampleSrc(s);
    if (src.a <= 0.0) { return half4(0.0); }
    float3 c = src.rgb;
    float a = src.a;
    if (uRegion.w > 0.5) {
        return half4(clamp(c, 0.0, 1.0) * a, a);
    }
    float defD = 0.0;
    float sharpD = 0.0;
    if (uAdjC.y != 0.0 || uAdjC.z != 0.0) {
        float yc = enc(dot(LUMA, c));
        if (uAdjC.y != 0.0) {
            float sum = 0.0;
            for (int k = 0; k < 8; k++) {
                float ang = float(k) * 0.7853981633974483;
                sum += lumaAt(o + uDefRadius * float2(cos(ang), sin(ang)));
            }
            defD = yc - sum / 8.0;
        }
        if (uAdjC.z != 0.0) {
            float sum4 = lumaAt(o + float2(1.0, 0.0)) + lumaAt(o - float2(1.0, 0.0))
                + lumaAt(o + float2(0.0, 1.0)) + lumaAt(o - float2(0.0, 1.0));
            sharpD = yc - sum4 / 4.0;
        }
    }
    if (uAdjA.x != 0.0) { c = scaleBy(c, exp2(${ColourOps.EXPOSURE_STOPS} * uAdjA.x)); }
    if (uAdjA.y != 0.0) {
        float p = exp2(-${ColourOps.BRIGHTNESS_GAMMA} * uAdjA.y);
        float3 e = enc3(c);
        c = dec3(float3(spow(e.r, p), spow(e.g, p), spow(e.b, p)));
    }
    if (uAdjA.z != 0.0) {
        float kc = 1.0 + ${ColourOps.CONTRAST_GAIN} * uAdjA.z;
        c = dec3((enc3(c) - 0.5) * kc + 0.5);
    }
    if (uAdjA.w != 0.0) {
        float ye = enc(dot(LUMA, c));
        c = scaleBy(c, exp2(uAdjA.w * ${ColourOps.HIGHLIGHT_STOPS} * smoothstep(0.5, 1.0, ye)));
    }
    if (uAdjB.x != 0.0) {
        float ye2 = enc(dot(LUMA, c));
        c = scaleBy(c, exp2(uAdjB.x * ${ColourOps.SHADOW_STOPS} * (1.0 - smoothstep(0.0, 0.5, ye2))));
    }
    if (uAdjB.y != 0.0) {
        c.r = c.r * exp2(${ColourOps.WB_STOPS} * uAdjB.y);
        c.b = c.b * exp2(-${ColourOps.WB_STOPS} * uAdjB.y);
    }
    if (uAdjB.z != 0.0) { c.g = c.g * exp2(-${ColourOps.WB_STOPS} * uAdjB.z); }
    if (uAdjB.w != 0.0) {
        float y = dot(LUMA, c);
        c = float3(y) + (c - float3(y)) * (1.0 + uAdjB.w);
    }
    if (uAdjC.x != 0.0) { c = rotateHue(c, uAdjC.x * ${ColourOps.HUE_RANGE}); }
    if (uAdjC.y != 0.0) { c = addDetail(c, ${ColourOps.DEFINITION_GAIN} * uAdjC.y * defD); }
    if (uAdjC.z != 0.0) { c = addDetail(c, ${ColourOps.SHARPNESS_GAIN} * uAdjC.z * sharpD); }
    if (uAdjC.w != 0.0) {
        float2 dv = o / uOutSize - 0.5;
        float dd = length(dv) / 0.70710677;
        c = scaleBy(c, exp2(-${ColourOps.VIGNETTE_STOPS} * uAdjC.w * smoothstep(0.3, 1.0, dd)));
    }
    if (uLut.y > 0.0) { c = applyLut(c, uLut.y); }
    float3 outc = clamp(enc3(mul3(uFromW0, uFromW1, uFromW2, c)), 0.0, 1.0);
    return half4(outc * a, a);
}
"""
    }
}
