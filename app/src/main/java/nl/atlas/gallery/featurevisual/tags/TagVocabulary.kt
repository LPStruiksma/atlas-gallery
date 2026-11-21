package nl.atlas.gallery.featurevisual.tags

/** Display data for one tag from `assets/models/siglip2_tags/tags.tsv`. */
data class TagName(val id: String, val category: String, val english: String, val dutch: String) {
    /** Dutch for `nl`, English otherwise. */
    fun label(language: String): String = if (language.equals("nl", ignoreCase = true)) dutch else english
}

/** Parser for `tags.tsv` (header `id category en nl`, tab-separated, UTF-8). */
object TagVocabulary {
    private val HEADER = listOf("id", "category", "en", "nl")

    fun parse(text: String): List<TagName> {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotEmpty() }
        require(lines.isNotEmpty() && lines[0].split('\t') == HEADER) { "Not a tag vocabulary" }
        val names = lines.drop(1).mapIndexed { n, line ->
            val c = line.split('\t')
            require(c.size == 4 && c.all { it.isNotBlank() }) { "Bad tag vocabulary row ${n + 2}" }
            TagName(c[0], c[1], c[2], c[3])
        }
        require(names.map { it.id }.toSet().size == names.size) { "Duplicate tag id" }
        return names
    }

    /** The names must list exactly the matrix's tags, in the same order. */
    fun requireMatches(matrix: TagMatrix, names: List<TagName>) {
        require(names.map { it.id } == matrix.tags.map { it.id }) { "tags.tsv does not match tags.bin" }
    }
}
