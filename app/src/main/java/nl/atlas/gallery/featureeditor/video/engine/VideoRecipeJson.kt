package nl.atlas.gallery.featureeditor.video.engine

import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.OverlayKind
import nl.atlas.gallery.featureeditor.engine.OverlayLayer

class VideoRecipeFormatException(message: String) : IllegalArgumentException(message)

/**
 * Deterministic, versioned project JSON. No reflection, Android JSON stubs or third-party
 * serializer. Reject unknown fields/versions instead of silently losing newer edit data.
 */
object VideoRecipeJson {
    private const val MAX_LENGTH = 1_048_576

    fun encode(recipe: VideoEditRecipe): String {
        recipe.requireValid()
        return VideoJsonWriter.write(recipeMap(recipe))
    }

    fun decode(json: String): VideoEditRecipe {
        val root = readObject(json)
        try {
            val recipe = recipeFrom(root)
            if (recipe.validate().isNotEmpty()) throw VideoRecipeFormatException("Invalid video edit recipe")
            return recipe
        } catch (e: VideoRecipeFormatException) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw VideoRecipeFormatException("Invalid video edit recipe")
        } catch (_: ArithmeticException) {
            throw VideoRecipeFormatException("Invalid video edit recipe")
        }
    }

    /** Store committed state and undo/redo snapshots; pending drags and comparing are transient. */
    fun encodeHistory(history: VideoEditHistory): String =
        VideoJsonWriter.write(linkedMapOf(
            "schema" to "atlas.video-edit-history",
            "version" to VideoEditRecipe.CURRENT_SCHEMA_VERSION.toLong(),
            "base" to recipeMap(history.base),
            "applied" to recipeMap(history.applied),
            "undo" to history.undoStack.map(::recipeMap),
            "redo" to history.redoStack.map(::recipeMap),
        ))

    fun decodeHistory(json: String): VideoEditHistory {
        val root = readObject(json)
        try {
            root.keysExactly("schema", "version", "base", "applied", "undo", "redo")
            if (root.string("schema") != "atlas.video-edit-history" ||
                root.long("version") != VideoEditRecipe.CURRENT_SCHEMA_VERSION.toLong()
            ) throw VideoRecipeFormatException("Unknown video history schema")
            val base = recipeFrom(root.obj("base")).requireValid()
            val applied = recipeFrom(root.obj("applied")).requireValid()
            val undo = root.array("undo").map { recipeFrom(it.asObj()).requireValid() }
            val redo = root.array("redo").map { recipeFrom(it.asObj()).requireValid() }
            return VideoEditHistory(base, applied, undo, redo)
        } catch (e: VideoRecipeFormatException) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw VideoRecipeFormatException("Invalid video edit history")
        }
    }

    private fun readObject(json: String): Map<String, Any?> {
        if (json.length > MAX_LENGTH) throw VideoRecipeFormatException("Video project too large")
        return try {
            VideoJsonParser(json).read().asObj()
        } catch (e: VideoRecipeFormatException) {
            throw e
        } catch (_: RuntimeException) {
            throw VideoRecipeFormatException("Malformed video JSON")
        } catch (_: StackOverflowError) {
            throw VideoRecipeFormatException("Malformed video JSON")
        }
    }

    private fun recipeMap(r: VideoEditRecipe): Map<String, Any?> {
        r.requireValid()
        return linkedMapOf(
            "schema" to VideoEditRecipe.SCHEMA_NAME,
            "version" to r.schemaVersion.toLong(),
            "clips" to r.clips.map { c -> linkedMapOf(
                "id" to c.id, "source" to c.sourceUri,
                "inUs" to c.inUs, "outUs" to c.outUs, "startUs" to c.timelineStartUs,
                "speeds" to c.speeds.map { s -> linkedMapOf(
                    "inUs" to s.inUs, "outUs" to s.outUs, "rate" to s.rate,
                ) },
                "volume" to c.volume, "muted" to c.muted,
                "crop" to listOf(c.crop.left, c.crop.top, c.crop.right, c.crop.bottom),
                "rotation" to c.rotateQuarterTurns.toLong(),
                "filter" to c.filter?.let { f -> linkedMapOf(
                    "id" to f.id, "lutId" to f.lutId, "lutHash" to f.lutHash,
                    "strength" to f.strength,
                ) },
            ) },
            "overlays" to r.overlays.map { o -> linkedMapOf(
                "startUs" to o.startUs, "endUs" to o.endUs,
                "layer" to linkedMapOf(
                    "id" to o.layer.layerId, "kind" to o.layer.kind.key,
                    "cx" to o.layer.centerX, "cy" to o.layer.centerY,
                    "scale" to o.layer.scale, "rotation" to o.layer.rotationDegrees,
                    "opacity" to o.layer.opacity, "hash" to o.layer.contentHash,
                    "props" to o.layer.props.toSortedMap(),
                ),
            ) },
            "transitions" to r.transitions.map { t -> linkedMapOf(
                "from" to t.fromClipId, "to" to t.toClipId,
                "kind" to t.kind.name, "durationUs" to t.durationUs,
            ) },
            "audioBeds" to r.audioBeds.map { a -> linkedMapOf(
                "id" to a.id, "source" to a.sourceUri,
                "startUs" to a.startUs, "endUs" to a.endUs,
                "volume" to a.volume, "loop" to a.loop,
            ) },
        )
    }

    private fun recipeFrom(root: Map<String, Any?>): VideoEditRecipe {
        root.keysExactly("schema", "version", "clips", "overlays", "transitions", "audioBeds")
        if (root.string("schema") != VideoEditRecipe.SCHEMA_NAME ||
            root.long("version") != VideoEditRecipe.CURRENT_SCHEMA_VERSION.toLong()
        ) throw VideoRecipeFormatException("Unknown video recipe schema")
        return VideoEditRecipe(
            clips = root.array("clips").map { raw ->
                val m = raw.asObj()
                m.keysExactly("id", "source", "inUs", "outUs", "startUs", "speeds",
                    "volume", "muted", "crop", "rotation", "filter")
                val filter = m["filter"]?.asObj()?.let { f ->
                    f.keysExactly("id", "lutId", "lutHash", "strength")
                    EditOperation.Filter(f.string("id"), f.optionalString("lutId"),
                        f.optionalString("lutHash"), f.double("strength"))
                }
                val rect = m.array("crop")
                if (rect.size != 4) throw VideoRecipeFormatException("Invalid crop")
                VideoClip(
                    id = m.string("id"), sourceUri = m.string("source"),
                    inUs = m.long("inUs"), outUs = m.long("outUs"),
                    timelineStartUs = m.long("startUs"),
                    speeds = m.array("speeds").map { s ->
                        val sm = s.asObj()
                        sm.keysExactly("inUs", "outUs", "rate")
                        SpeedSection(sm.long("inUs"), sm.long("outUs"), sm.double("rate"))
                    },
                    volume = m.double("volume"), muted = m.boolean("muted"),
                    crop = NormRect(rect[0].asDouble(), rect[1].asDouble(),
                        rect[2].asDouble(), rect[3].asDouble()),
                    rotateQuarterTurns = m.long("rotation").toIntExact(),
                    filter = filter,
                )
            },
            overlays = root.array("overlays").map { raw ->
                val m = raw.asObj()
                m.keysExactly("startUs", "endUs", "layer")
                val l = m.obj("layer")
                l.keysExactly("id", "kind", "cx", "cy", "scale", "rotation", "opacity", "hash", "props")
                val props = l.obj("props").mapValues { (_, v) -> v as? String
                    ?: throw VideoRecipeFormatException("Invalid overlay property") }
                VideoOverlay(m.long("startUs"), m.long("endUs"), OverlayLayer(
                    layerId = l.string("id"),
                    kind = OverlayKind.fromKey(l.string("kind"))
                        ?: throw VideoRecipeFormatException("Unknown overlay kind"),
                    centerX = l.double("cx"), centerY = l.double("cy"),
                    scale = l.double("scale"), rotationDegrees = l.double("rotation"),
                    opacity = l.double("opacity"), contentHash = l.optionalString("hash"),
                    props = props.toSortedMap(),
                ))
            },
            transitions = root.array("transitions").map { raw ->
                val m = raw.asObj()
                m.keysExactly("from", "to", "kind", "durationUs")
                VideoTransition(m.string("from"), m.string("to"),
                    VideoTransitionKind.entries.firstOrNull { it.name == m.string("kind") }
                        ?: throw VideoRecipeFormatException("Unknown video transition"),
                    m.long("durationUs"))
            },
            audioBeds = root.array("audioBeds").map { raw ->
                val m = raw.asObj()
                m.keysExactly("id", "source", "startUs", "endUs", "volume", "loop")
                VideoAudioBed(m.string("id"), m.string("source"), m.long("startUs"),
                    m.long("endUs"), m.double("volume"), m.boolean("loop"))
            },
            schemaVersion = VideoEditRecipe.CURRENT_SCHEMA_VERSION,
        )
    }

    private fun Map<String, Any?>.keysExactly(vararg names: String) {
        if (keys != names.toSet()) throw VideoRecipeFormatException("Video JSON has missing or unknown fields")
    }
    private fun Map<String, Any?>.string(k: String): String = this[k] as? String
        ?: throw VideoRecipeFormatException("Invalid video string field")
    private fun Map<String, Any?>.optionalString(k: String): String? =
        when (val v = this[k]) {
            null -> null
            is String -> v
            else -> throw VideoRecipeFormatException("Invalid optional video field")
        }
    private fun Map<String, Any?>.long(k: String): Long = this[k] as? Long
        ?: throw VideoRecipeFormatException("Invalid video integer field")
    private fun Map<String, Any?>.double(k: String): Double = this[k].asDouble()
    private fun Map<String, Any?>.boolean(k: String): Boolean = this[k] as? Boolean
        ?: throw VideoRecipeFormatException("Invalid video boolean field")
    private fun Map<String, Any?>.array(k: String): List<Any?> = this[k].asArray()
    private fun Map<String, Any?>.obj(k: String): Map<String, Any?> = this[k].asObj()
    private fun Long.toIntExact(): Int {
        if (this !in Int.MIN_VALUE..Int.MAX_VALUE) throw VideoRecipeFormatException("Invalid video rotation")
        return toInt()
    }
    private fun Any?.asDouble(): Double = when (this) {
        is Long -> toDouble()
        is Double -> this
        else -> throw VideoRecipeFormatException("Invalid video number")
    }
    @Suppress("UNCHECKED_CAST")
    private fun Any?.asArray(): List<Any?> = this as? List<Any?>
        ?: throw VideoRecipeFormatException("Invalid video list")
    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObj(): Map<String, Any?> = this as? Map<String, Any?>
        ?: throw VideoRecipeFormatException("Invalid video object")
}

private object VideoJsonWriter {
    fun write(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> quote(value)
            is Boolean, is Long, is Int -> append(value.toString())
            is Double -> {
                if (!value.isFinite()) throw VideoRecipeFormatException("Nonfinite video number")
                append(if (value == 0.0) "0" else value.toString())
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    quote(entry.key as String)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> throw VideoRecipeFormatException("Unsupported video JSON value")
        }
    }

    private fun StringBuilder.quote(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
        }
        append('"')
    }
}

/** Bounded recursive-descent RFC 8259 parser. Duplicated keys and trailing data fail closed. */
private class VideoJsonParser(private val s: String) {
    private var i = 0
    private fun bad(): Nothing = throw VideoRecipeFormatException("Malformed video JSON")
    private fun ws() { while (i < s.length && s[i] in " \t\n\r") i++ }

    fun read(): Any? {
        ws()
        val result = value(0)
        ws()
        if (i != s.length) bad()
        return result
    }

    private fun value(depth: Int): Any? {
        if (depth > 32) bad()
        ws()
        if (i >= s.length) bad()
        return when (s[i]) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun literal(word: String, result: Any?): Any? {
        if (!s.startsWith(word, i)) bad()
        i += word.length
        return result
    }

    private fun obj(depth: Int): Map<String, Any?> {
        i++
        ws()
        val map = linkedMapOf<String, Any?>()
        if (i < s.length && s[i] == '}') { i++; return map }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') bad()
            val key = string()
            ws()
            if (i >= s.length || s[i++] != ':') bad()
            if (map.containsKey(key)) bad()
            map[key] = value(depth)
            ws()
            if (i >= s.length) bad()
            when (s[i++]) {
                '}' -> return map
                ',' -> Unit
                else -> bad()
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        i++
        ws()
        val items = mutableListOf<Any?>()
        if (i < s.length && s[i] == ']') { i++; return items }
        while (true) {
            items += value(depth)
            ws()
            if (i >= s.length) bad()
            when (s[i++]) {
                ']' -> return items
                ',' -> Unit
                else -> bad()
            }
        }
    }

    private fun string(): String {
        if (i >= s.length || s[i++] != '"') bad()
        val out = StringBuilder()
        while (i < s.length) {
            when (val c = s[i++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= s.length) bad()
                    when (s[i++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            if (i + 4 > s.length) bad()
                            out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: bad())
                            i += 4
                        }
                        else -> bad()
                    }
                }
                else -> if (c < ' ') bad() else out.append(c)
            }
        }
        bad()
    }

    private fun number(): Any {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) bad()
        if (s[i] == '0') i++
        else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ }
        else bad()
        var integer = true
        if (i < s.length && s[i] == '.') {
            integer = false; i++
            if (i >= s.length || s[i] !in '0'..'9') bad()
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && s[i] in "eE") {
            integer = false; i++
            if (i < s.length && s[i] in "+-") i++
            if (i >= s.length || s[i] !in '0'..'9') bad()
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val token = s.substring(start, i)
        if (integer) return token.toLongOrNull() ?: bad()
        val result = token.toDoubleOrNull() ?: bad()
        if (!result.isFinite()) bad()
        return result
    }
}
