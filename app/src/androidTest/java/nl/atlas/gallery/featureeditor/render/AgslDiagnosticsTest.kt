package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** TEMP diagnostics for #632 (removed before review). */
@RunWith(AndroidJUnit4::class)
class AgslDiagnosticsTest {
    private fun draw(shader: Shader, w: Int = 8, h: Int = 8): Bitmap {
        val reader = RenderTestKit.reader(w, h)
        val r = HardwareRenderer().apply { setSurface(reader.surface); isOpaque = false }
        val node = RenderNode("d").apply { setPosition(0, 0, w, h) }
        val c = node.beginRecording(w, h)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        node.endRecording()
        r.setContentRoot(node)
        r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val b = RenderTestKit.readLatest(reader, PixelSpace.SRGB)
        r.destroy(); reader.close()
        return b
    }

    private fun hex(b: Bitmap) = "%08x".format(b.getPixel(b.width / 2, b.height / 2))

    @Test fun probe() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val src = RenderTestKit.solid(8, 8, 200, 100, 50)
        RenderTestKit.log("diag constant=" + hex(draw(RuntimeShader("half4 main(float2 p){return half4(1,0,0,1);}"))))
        val s1 = RuntimeShader("uniform shader s; half4 main(float2 p){return s.eval(p);}")
        s1.setInputBuffer("s", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
        RenderTestKit.log("diag buffer=" + hex(draw(s1)))
        val s2 = RuntimeShader("uniform shader s; half4 main(float2 p){return s.eval(p);}")
        s2.setInputShader("s", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        RenderTestKit.log("diag shader=" + hex(draw(s2)))
        val s3 = RuntimeShader("uniform float2 u; half4 main(float2 p){return half4(u.x/255.0, u.y/255.0, 0, 1);}")
        s3.setFloatUniform("u", 10f, 20f)
        RenderTestKit.log("diag uniform=" + hex(draw(s3)))
        val s4 = RuntimeShader("uniform float3 a; uniform float3 b; half4 main(float2 p){float3 h=float3(p,1.0); return half4(dot(a,h)/255.0, dot(b,h)/255.0, 0, 1);}")
        s4.setFloatUniform("a", 1f, 0f, 0f); s4.setFloatUniform("b", 0f, 1f, 0f)
        RenderTestKit.log("diag coord=" + hex(draw(s4)))
        // The real program, identity recipe.
        val plan = nl.atlas.gallery.featureeditor.engine.RenderPlan.build(nl.atlas.gallery.featureeditor.engine.EditRecipe(8, 8))
        val prog = SegmentProgram.of(plan)
        val agsl = AgslProgram()
        RenderTestKit.log("diag program=" + hex(draw(agsl.bind(prog, SourceRegion(src, 0f, 0f, 1f, PixelSpace.SRGB), null, PixelSpace.SRGB))))
        val region = SourceRegion(src, 0f, 0f, 1f, PixelSpace.SRGB)
        fun variant(name: String, body: String) {
            val v = AgslProgram(AgslProgram.SHADER.replace("half4 main(float2 o) {", "half4 main(float2 o) { float3 hq = float3(o, 1.0); float2 sq = float2(dot(uM0, hq), dot(uM1, hq)) / dot(uM2, hq); $body"))
            RenderTestKit.log("diag $name=" + hex(draw(v.bind(prog, region, null, PixelSpace.SRGB))))
        }
        variant("mapped", "return half4(sq.x / 8.0, sq.y / 8.0, dot(uM2, hq), 1.0);")
        variant("srcsize", "return half4(uSrcSize.x / 255.0, uRegionSize.x / 255.0, uRegion.z, 1.0);")
        variant("rawtexel", "return uSource.eval(floor(sq) + 0.5);")
        variant("texel", "float4 t = texel(floor(sq)); return half4(t);")
        variant("sample", "return half4(sampleSrc(sq));")
        variant("tow", "return half4(uToW0, 1.0);")
    }

    private fun firstMismatch(a: Bitmap, b: Bitmap): String {
        var n = 0; var first = ""
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (p != q) { if (n == 0) first = "($x,$y) %08x vs %08x".format(p, q); n++ }
        }
        return "mismatch=$n first=$first"
    }

    @Test fun probeSizes() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        for ((w, h) in listOf(8 to 8, 64 to 64, 240 to 160, 256 to 160)) {
            val pic = RenderTestKit.picture(w, h)
            val pass = RuntimeShader("uniform shader s; half4 main(float2 p){return s.eval(p);}")
            pass.setInputBuffer("s", BitmapShader(pic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
            RenderTestKit.log("diag2 ${w}x$h passthrough " + firstMismatch(draw(pass, w, h), pic))
            val plan = nl.atlas.gallery.featureeditor.engine.RenderPlan.build(nl.atlas.gallery.featureeditor.engine.EditRecipe(w, h))
            val prog = SegmentProgram.of(plan)
            val region = SourceRegion(pic, 0f, 0f, 1f, PixelSpace.SRGB)
            val g = AgslPixelBackend().use { it.render(prog, region, IntRect.size(w, h), null, PixelSpace.SRGB) }
            val c = CpuPixelBackend().render(prog, region, IntRect.size(w, h), null, PixelSpace.SRGB)
            RenderTestKit.log("diag2 ${w}x$h gpu_vs_src " + firstMismatch(g, pic) + " cpu_vs_src " + firstMismatch(c, pic) + " gpuconf=${g.config} ${g.width}x${g.height}")
            val direct = draw(AgslProgram().bind(prog, region, null, PixelSpace.SRGB), w, h)
            RenderTestKit.log("diag2 ${w}x$h direct_vs_src " + firstMismatch(direct, pic))
        }
    }
}
