package nl.atlas.gallery.featureeditor.engine

/** Thrown for malformed, invalid or too-new recipe JSON. Messages never echo the input. */
class RecipeFormatException(message: String) : Exception(message)

/**
 * Upgrades a decoded JSON object from [fromVersion] to `fromVersion + 1`. Register one per
 * schema bump in [RecipeJson.MIGRATIONS]; decode applies them in sequence.
 */
class RecipeMigration(val fromVersion: Int, val migrate: (Map<String, Any?>) -> Map<String, Any?>)

/**
 * Deterministic JSON for [EditRecipe] and [EditHistory] (EDIT-1). The same recipe always
 * encodes to the same bytes: fixed key order, canonical numbers, no whitespace. Saved alongside
 * an Atlas edit (EDIT-3) so the edit can be reopened, adjusted and reverted.
 */
object RecipeJson {
    /** Schema migrations, oldest first. Version 1 is the first schema, so none exist yet. */
    val MIGRATIONS: List<RecipeMigration> = emptyList()

    private const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(recipe: EditRecipe): String = Writer().apply { recipe(recipe) }.toString()

    fun decode(text: String, migrations: List<RecipeMigration> = MIGRATIONS): EditRecipe {
        val root = parseRoot(text)
        return recipeFrom(migrate(root, migrations))
    }

    /** Encodes a session (recipe + redo stack, no pending drag) for process-death restore. */
    fun encodeHistory(history: EditHistory): String = Writer().apply {
        obj {
            key("schema"); str("atlas.edit-history")
            key("version"); num(EditRecipe.CURRENT_SCHEMA_VERSION.toLong())
            key("base"); recipe(history.base)
            key("applied"); arr(history.applied) { op(it) }
            key("redo"); arr(history.redoStack) { op(it) }
        }
    }.toString()

    fun decodeHistory(text: String, migrations: List<RecipeMigration> = MIGRATIONS): EditHistory {
        val root = parseRoot(text)
        if (root["schema"] != "atlas.edit-history") throw RecipeFormatException("Not an edit history")
        val version = root.int("version")
        val wrap = { ops: Any? ->
            mapOf("schema" to EditRecipe.SCHEMA_NAME, "version" to version, "source" to root.obj("base")["source"], "operations" to ops)
        }
        val base = recipeFrom(migrate(root.obj("base"), migrations))
        val applied = recipeFrom(migrate(wrap(root["applied"]), migrations)).operations
        val redo = recipeFrom(migrate(wrap(root["redo"]), migrations)).operations
        val history = EditHistory(base, applied, redo)
        val ids = (applied + redo).map { it.id }
        if (ids.size != ids.toSet().size) throw RecipeFormatException("History repeats an operation id")
        return history
    }

    // ---- decoding ----

    private fun parseRoot(text: String): Map<String, Any?> {
        if (text.length > MAX_BYTES) throw RecipeFormatException("Recipe too large")
        val value = try {
            JsonReader(text).readDocument()
        } catch (e: RecipeFormatException) {
            throw e
        } catch (_: RuntimeException) {
            throw RecipeFormatException("Malformed JSON")
        } catch (_: StackOverflowError) {
            throw RecipeFormatException("Malformed JSON")
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw RecipeFormatException("Root is not an object")
    }

    private fun migrate(root: Map<String, Any?>, migrations: List<RecipeMigration>): Map<String, Any?> {
        if (root["schema"] != EditRecipe.SCHEMA_NAME) throw RecipeFormatException("Not an edit recipe")
        var current = root
        var version = current.int("version")
        if (version > EditRecipe.CURRENT_SCHEMA_VERSION) throw RecipeFormatException("Recipe from a newer Atlas (v$version)")
        while (version < EditRecipe.CURRENT_SCHEMA_VERSION) {
            val step = migrations.firstOrNull { it.fromVersion == version }
                ?: throw RecipeFormatException("No migration from v$version")
            current = step.migrate(current)
            val next = current.int("version")
            if (next != version + 1) throw RecipeFormatException("Migration from v$version did not advance")
            version = next
        }
        return current
    }

    private fun recipeFrom(root: Map<String, Any?>): EditRecipe {
        val source = root.obj("source")
        val w = source.int("width"); val h = source.int("height")
        if (w <= 0 || h <= 0) throw RecipeFormatException("Source size must be positive")
        val ops = root.list("operations").mapIndexed { i, raw ->
            @Suppress("UNCHECKED_CAST")
            val m = raw as? Map<String, Any?> ?: throw RecipeFormatException("Operation $i is not an object")
            try {
                opFrom(m)
            } catch (e: RecipeFormatException) {
                throw RecipeFormatException("Operation $i: ${e.message}")
            } catch (_: IllegalArgumentException) {
                throw RecipeFormatException("Operation $i is invalid")
            }
        }
        val recipe = EditRecipe(w, h, ops, root.int("version"))
        val problems = recipe.validate()
        if (problems.isNotEmpty()) throw RecipeFormatException("Invalid recipe: ${problems.joinToString()}")
        return recipe
    }

    private fun opFrom(m: Map<String, Any?>): EditOperation {
        val id = m.str("id")
        return when (val type = m.str("type")) {
            "rotate90" -> EditOperation.Rotate90(id, m.int("turns"))
            "flip" -> EditOperation.Flip(id, FlipAxis.entries.firstOrNull { it.key == m.str("axis") } ?: bad("axis"))
            "straighten" -> EditOperation.Straighten(id, m.dbl("degrees"))
            "perspective" -> EditOperation.Perspective(id, quadFrom(m.list("quad")))
            "crop" -> EditOperation.Crop(id, rectFrom(m.list("rect")), m.optStr("aspect"))
            "resetGeometry" -> EditOperation.ResetGeometry(id)
            "adjust" -> EditOperation.Adjust(id, AdjustParam.fromKey(m.str("param")) ?: bad("param"), m.dbl("value"))
            "resetAdjustments" -> EditOperation.ResetAdjustments(id)
            "filter" -> EditOperation.Filter(id, m.optStr("lut"), m.optStr("lutHash"), m.dbl("strength"))
            "overlayAdd" -> EditOperation.OverlayAdd(id, layerFrom(m.obj("layer")))
            "overlayUpdate" -> EditOperation.OverlayUpdate(id, layerFrom(m.obj("layer")))
            "overlayRemove" -> EditOperation.OverlayRemove(id, m.str("layerId"))
            "rasterPatch" -> EditOperation.RasterPatch(
                id = id,
                tool = PatchTool.fromKey(m.str("tool")) ?: bad("tool"),
                contentHash = m.str("hash"),
                rect = rectFrom(m.list("rect")),
                pixelWidth = m.int("pixelWidth"),
                pixelHeight = m.int("pixelHeight"),
                maskHash = m.optStr("maskHash"),
                space = PatchSpace.fromKey(m.str("space")) ?: bad("space"),
            )
            else -> throw RecipeFormatException("Unknown operation type (${type.length} chars)")
        }
    }

    private fun layerFrom(m: Map<String, Any?>): OverlayLayer {
        val props = m.obj("props").mapValues { (_, v) -> v as? String ?: bad("props") }
        return OverlayLayer(
            layerId = m.str("id"),
            kind = OverlayKind.fromKey(m.str("kind")) ?: bad("kind"),
            centerX = m.dbl("cx"), centerY = m.dbl("cy"),
            scale = m.dbl("scale"), rotationDegrees = m.dbl("rotation"), opacity = m.dbl("opacity"),
            contentHash = m.optStr("hash"),
            props = props.toSortedMap(),
        )
    }

    private fun rectFrom(l: List<Any?>): NormRect {
        if (l.size != 4) bad("rect")
        val d = l.map { (it as? Number)?.toDouble() ?: bad("rect") }
        return NormRect(d[0], d[1], d[2], d[3])
    }

    private fun quadFrom(l: List<Any?>): Quad {
        if (l.size != 8) bad("quad")
        val d = l.map { (it as? Number)?.toDouble() ?: bad("quad") }
        return Quad(Point2(d[0], d[1]), Point2(d[2], d[3]), Point2(d[4], d[5]), Point2(d[6], d[7]))
    }

    private fun bad(field: String): Nothing = throw RecipeFormatException("Field '$field' is missing or wrong")

    private fun Map<String, Any?>.str(k: String) = this[k] as? String ?: bad(k)
    private fun Map<String, Any?>.optStr(k: String): String? = when (val v = this[k]) {
        null -> null
        is String -> v
        else -> bad(k)
    }
    private fun Map<String, Any?>.dbl(k: String): Double = (this[k] as? Number)?.toDouble() ?: bad(k)
    private fun Map<String, Any?>.int(k: String): Int {
        val v = this[k]
        return when {
            v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE -> v.toInt()
            v is Int -> v
            else -> bad(k)
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(k: String) = this[k] as? Map<String, Any?> ?: bad(k)
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(k: String) = this[k] as? List<Any?> ?: bad(k)

    // ---- encoding ----

    private class Writer {
        private val sb = StringBuilder()
        private var needComma = false

        fun recipe(r: EditRecipe) = obj {
            key("schema"); str(EditRecipe.SCHEMA_NAME)
            key("version"); num(r.schemaVersion.toLong())
            key("source"); obj { key("width"); num(r.sourceWidth.toLong()); key("height"); num(r.sourceHeight.toLong()) }
            key("operations"); arr(r.operations) { op(it) }
        }

        fun op(op: EditOperation) = obj {
            key("id"); str(op.id)
            key("type")
            when (op) {
                is EditOperation.Rotate90 -> { str("rotate90"); key("turns"); num(op.quarterTurns.toLong()) }
                is EditOperation.Flip -> { str("flip"); key("axis"); str(op.axis.key) }
                is EditOperation.Straighten -> { str("straighten"); key("degrees"); num(op.degrees) }
                is EditOperation.Perspective -> {
                    str("perspective"); key("quad")
                    arr(op.quad.points().flatMap { listOf(it.x, it.y) }) { num(it) }
                }
                is EditOperation.Crop -> {
                    str("crop"); key("rect"); rect(op.rect)
                    op.aspect?.let { key("aspect"); str(it) }
                }
                is EditOperation.ResetGeometry -> str("resetGeometry")
                is EditOperation.Adjust -> { str("adjust"); key("param"); str(op.param.key); key("value"); num(op.value) }
                is EditOperation.ResetAdjustments -> str("resetAdjustments")
                is EditOperation.Filter -> {
                    str("filter")
                    op.lutId?.let { key("lut"); str(it) }
                    op.lutHash?.let { key("lutHash"); str(it) }
                    key("strength"); num(op.strength)
                }
                is EditOperation.OverlayAdd -> { str("overlayAdd"); key("layer"); layer(op.layer) }
                is EditOperation.OverlayUpdate -> { str("overlayUpdate"); key("layer"); layer(op.layer) }
                is EditOperation.OverlayRemove -> { str("overlayRemove"); key("layerId"); str(op.layerId) }
                is EditOperation.RasterPatch -> {
                    str("rasterPatch")
                    key("tool"); str(op.tool.key)
                    key("space"); str(op.space.key)
                    key("hash"); str(op.contentHash)
                    op.maskHash?.let { key("maskHash"); str(it) }
                    key("rect"); rect(op.rect)
                    key("pixelWidth"); num(op.pixelWidth.toLong())
                    key("pixelHeight"); num(op.pixelHeight.toLong())
                }
            }
        }

        fun layer(l: OverlayLayer) = obj {
            key("id"); str(l.layerId)
            key("kind"); str(l.kind.key)
            key("cx"); num(l.centerX); key("cy"); num(l.centerY)
            key("scale"); num(l.scale); key("rotation"); num(l.rotationDegrees); key("opacity"); num(l.opacity)
            l.contentHash?.let { key("hash"); str(it) }
            key("props"); obj { l.props.toSortedMap().forEach { (k, v) -> key(k); str(v) } }
        }

        fun rect(r: NormRect) = arr(listOf(r.left, r.top, r.right, r.bottom)) { num(it) }

        fun obj(body: Writer.() -> Unit) {
            sep(); sb.append('{'); needComma = false; body(); sb.append('}'); needComma = true
        }

        fun <T> arr(items: List<T>, each: Writer.(T) -> Unit) {
            sep(); sb.append('['); needComma = false
            items.forEach { each(it) }
            sb.append(']'); needComma = true
        }

        fun key(k: String) { sep(); quote(k); sb.append(':'); needComma = false }

        fun str(v: String) { sep(); quote(v); needComma = true }

        fun num(v: Long) { sep(); sb.append(v); needComma = true }

        fun num(v: Double) {
            require(v.isFinite()) { "Non-finite number" }
            sep()
            val d = if (v == 0.0) 0.0 else v // no negative zero
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
            needComma = true
        }

        private fun sep() { if (needComma) sb.append(','); }

        private fun quote(s: String) {
            sb.append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
            sb.append('"')
        }

        override fun toString() = sb.toString()
    }
}

/** Strict RFC 8259 reader returning Map / List / String / Long / Double / Boolean / null. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): Any? {
        ws(); val v = value(0); ws()
        if (i != s.length) fail()
        return v
    }

    private fun fail(): Nothing = throw RecipeFormatException("Malformed JSON")

    private fun ws() { while (i < s.length && s[i] in " \t\n\r") i++ }

    private fun value(depth: Int): Any? {
        if (depth > 32) fail()
        if (i >= s.length) fail()
        return when (s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> string()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> number()
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail()
        i += word.length
        return v
    }

    private fun obj(depth: Int): Map<String, Any?> {
        i++; ws()
        val m = LinkedHashMap<String, Any?>()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws(); if (i >= s.length || s[i] != '"') fail()
            val k = string(); ws()
            if (i >= s.length || s[i] != ':') fail()
            i++; ws()
            if (m.containsKey(k)) fail()
            m[k] = value(depth + 1); ws()
            if (i >= s.length) fail()
            when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> fail() }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        i++; ws()
        val l = ArrayList<Any?>()
        if (i < s.length && s[i] == ']') { i++; return l }
        while (true) {
            ws(); l += value(depth + 1); ws()
            if (i >= s.length) fail()
            when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> fail() }
        }
    }

    private fun string(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail()
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail()
                    when (s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail()
                            sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail()); i += 4
                        }
                        else -> fail()
                    }
                }
                c < ' ' -> fail()
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) fail()
        if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i].isDigit()) i++ } else fail()
        var isInt = true
        if (i < s.length && s[i] == '.') {
            isInt = false; i++
            if (i >= s.length || !s[i].isDigit()) fail()
            while (i < s.length && s[i].isDigit()) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            isInt = false; i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || !s[i].isDigit()) fail()
            while (i < s.length && s[i].isDigit()) i++
        }
        val text = s.substring(start, i)
        if (isInt) text.toLongOrNull()?.let { return it }
        val d = text.toDouble()
        if (!d.isFinite()) fail()
        return d
    }
}
