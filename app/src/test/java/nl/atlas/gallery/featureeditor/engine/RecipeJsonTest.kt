package nl.atlas.gallery.featureeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecipeJsonTest {
    @Test fun everyOperationRoundTripsExactly() {
        val recipe = EngineFixtures.everyOperation()
        val json = RecipeJson.encode(recipe)
        assertEquals(recipe, RecipeJson.decode(json))
    }

    @Test fun encodingIsDeterministicAndStable() {
        val recipe = EngineFixtures.everyOperation()
        val a = RecipeJson.encode(recipe)
        val b = RecipeJson.encode(RecipeJson.decode(a))
        assertEquals(a, b)
        // Overlay props are written sorted whatever map order the caller used.
        val shuffled = recipe.copy(operations = recipe.operations.map { op ->
            if (op is EditOperation.OverlayAdd && op.layer.kind == OverlayKind.TEXT) {
                op.copy(layer = op.layer.copy(props = linkedMapOf("text" to op.layer.props["text"]!!, "colour" to "#ffffff", "font" to "atlas-sans")))
            } else op
        })
        assertEquals(a, RecipeJson.encode(shuffled))
    }

    @Test fun headerCarriesSchemaNameAndVersion() {
        val json = RecipeJson.encode(EditRecipe(10, 20))
        assertEquals("""{"schema":"atlas.edit-recipe","version":1,"source":{"width":10,"height":20},"operations":[]}""", json)
    }

    @Test fun numbersAreCanonical() {
        val recipe = EditRecipe(10, 10, listOf(
            EditOperation.Adjust("a", AdjustParam.EXPOSURE, -0.0),
            EditOperation.Adjust("b", AdjustParam.CONTRAST, 1.0),
            EditOperation.Adjust("c", AdjustParam.SATURATION, 0.125),
            EditOperation.Adjust("d", AdjustParam.TINT, 1e-7),
        ))
        val json = RecipeJson.encode(recipe)
        assertTrue(json, json.contains(""""param":"exposure","value":0}"""))
        assertTrue(json, json.contains(""""param":"contrast","value":1}"""))
        assertTrue(json, json.contains(""""value":0.125}"""))
        assertTrue(json, json.contains(""""value":1.0E-7}"""))
        assertEquals(1e-7, (RecipeJson.decode(json).operations[3] as EditOperation.Adjust).value, 0.0)
    }

    @Test fun newerSchemaIsRefused() {
        val json = RecipeJson.encode(EditRecipe(10, 10)).replace("\"version\":1", "\"version\":2")
        assertFormatError { RecipeJson.decode(json) }
    }

    @Test fun migrationHookUpgradesOlderSchema() {
        // A hypothetical v0 stored operations under "ops" and the straighten angle as "angle".
        val v0 = """{"schema":"atlas.edit-recipe","version":0,"source":{"width":30,"height":20},""" +
            """"ops":[{"id":"x","type":"straighten","angle":2.5}]}"""
        val migration = RecipeMigration(0) { old ->
            @Suppress("UNCHECKED_CAST")
            val ops = (old["ops"] as List<Map<String, Any?>>).map { op ->
                if (op["type"] == "straighten") op - "angle" + ("degrees" to op["angle"]) else op
            }
            old - "ops" + ("operations" to ops) + ("version" to 1L)
        }
        val recipe = RecipeJson.decode(v0, listOf(migration))
        assertEquals(EditRecipe(30, 20, listOf(EditOperation.Straighten("x", 2.5))), recipe)
        // Without the hook, the old file is refused rather than misread.
        assertFormatError { RecipeJson.decode(v0) }
    }

    @Test fun migrationThatDoesNotAdvanceIsRefused() {
        val v0 = """{"schema":"atlas.edit-recipe","version":0,"source":{"width":30,"height":20},"operations":[]}"""
        assertFormatError { RecipeJson.decode(v0, listOf(RecipeMigration(0) { it })) }
    }

    @Test fun malformedAndInvalidInputIsRefusedWithoutEchoingIt() {
        val secret = "private-caption-text"
        val cases = listOf(
            "",
            "[]",
            "{",
            """{"schema":"other","version":1}""",
            """{"schema":"atlas.edit-recipe","version":1,"source":{"width":0,"height":5},"operations":[]}""",
            """{"schema":"atlas.edit-recipe","version":1,"source":{"width":5,"height":5},"operations":[{"id":"a","type":"$secret"}]}""",
            """{"schema":"atlas.edit-recipe","version":1,"source":{"width":5,"height":5},"operations":[{"id":"a","type":"straighten","degrees":90}]}""",
            """{"schema":"atlas.edit-recipe","version":1,"source":{"width":5,"height":5},"operations":[{"id":"a","type":"rasterPatch","tool":"erase","hash":"$secret","rect":[0,0,1,1],"pixelWidth":1,"pixelHeight":1}]}""",
            """{"schema":"atlas.edit-recipe","version":1,"source":{"width":5,"height":5},"operations":[{"id":"a","type":"resetGeometry"},{"id":"a","type":"resetGeometry"}]}""",
            """{"schema":"atlas.edit-recipe","version":1,"version":1,"source":{"width":5,"height":5},"operations":[]}""",
            """{"schema":"atlas.edit-recipe","version":1,"source":{"width":5,"height":5},"operations":[]} trailing""",
            "[".repeat(100) + "]".repeat(100),
        )
        for (case in cases) {
            val error = assertFormatError { RecipeJson.decode(case) }
            assertFalse(error.message, error.message.orEmpty().contains(secret))
        }
    }

    @Test fun historyRoundTripsWithRedoStack() {
        val recipe = EngineFixtures.everyOperation()
        var h = EditHistory.start(recipe.copy(operations = recipe.operations.take(5)))
        recipe.operations.drop(5).forEach { h = h.apply(it) }
        h = h.undo().undo().undo()
        val restored = RecipeJson.decodeHistory(RecipeJson.encodeHistory(h))
        assertEquals(h, restored)
        assertEquals(h.redo().redo().current, restored.redo().redo().current)
    }

    private fun assertFormatError(block: () -> Unit): RecipeFormatException {
        try {
            block()
        } catch (e: RecipeFormatException) {
            return e
        }
        fail("Expected RecipeFormatException")
        throw AssertionError()
    }
}
