package nl.atlas.gallery.featurevisual.tags

import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The committed TAGS-0 asset pair decodes, matches the shipped index model and lists the same tags. */
class TagAssetsTest {
    private val dir: File = listOf(File("src/main/assets/models/siglip2_tags"), File("app/src/main/assets/models/siglip2_tags"))
        .first { it.isDirectory }

    @Test fun shippedMatrixDecodesAndIsFencedToTheShippedImageModel() {
        val matrix = TagMatrixCodec.decode(File(dir, "tags.bin").readBytes())
        assertEquals(768, matrix.dimension)
        assertEquals(SiglipModel.MODEL_VERSION, matrix.imageModelVersion)
        assertEquals("siglip2_b16_224.text.int8:6fd97706a619", matrix.textModelVersion)
        assertEquals(159, matrix.tags.size)
        assertEquals(117, matrix.tags.count { it.defaultOn })
        assertTrue(matrix.tags.all { it.threshold in 0.03f..0.2f })
        val names = TagVocabulary.parse(File(dir, "tags.tsv").readText())
        TagVocabulary.requireMatches(matrix, names)
        assertEquals("Hond", names.first { it.id == "dog" }.label("nl"))
        // Distinct tags point in distinct directions (no duplicated row).
        val dog = matrix.row(matrix.tags.indexOfFirst { it.id == "dog" })
        val cat = matrix.row(matrix.tags.indexOfFirst { it.id == "cat" })
        val cos = dog.indices.sumOf { dog[it].toDouble() * cat[it] }
        assertTrue("dog·cat = $cos", cos < 0.95)
    }
}
