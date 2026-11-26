package nl.atlas.gallery

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featuregrid.buildGridTimelineIndex
import org.junit.Test

/**
 * #603 step 1 only: actual v3 codec, default Pictures sort and catalogue
 * timeline index. No timing assertion: hosted JVM wall time is diagnostic.
 * Each cold sample is the first decode in a fresh -Xint process; encoding
 * runs in the parent, never warming the measured process.
 */
class CachedFirstFrameStageProbeTest {
    @Test fun stageTable() {
        val dir = Files.createTempDirectory("atlas-a1b-").toFile()
        try {
            val rows = mutableListOf<String>()
            for (size in listOf(150_000, 250_000)) {
                val cache = File(dir, "inventory-$size.bin")
                writeFixture(cache, size)
                repeat(3) { sample ->
                    rows += runChild(cache, size, "cold", sample, dir)
                }
                rows += runChild(cache, size, "warm", 0, dir)
            }
            val table = buildString {
                appendLine("### PERF-A1B synthetic JVM stage table (#603)")
                appendLine("JDK: ${System.getProperty("java.version")}; cold = fresh -Xint, warm = 5 warmups then 5 samples.")
                appendLine("Decode includes file open/read; OS page cache not flushed. Inventory in DATE_ADDED DESC-like order, with every tenth row an older import.")
                appendLine("| Rows | Mode | Sample | Decode ms | Sort ms | Timeline ms | Decode + sort ms |")
                appendLine("|---:|---|---:|---:|---:|---:|---:|")
                rows.forEach { appendLine(it) }
            }
            println(table)
            // CI's ordinary Gradle console suppresses passing test stdout.
            // Publish the synthetic measurements to the existing step summary
            // as well, without modifying workflow files or release configuration.
            System.getenv("GITHUB_STEP_SUMMARY")?.takeIf { it.isNotBlank() }?.let {
                File(it).appendText("\n$table\n")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeFixture(file: File, size: Int) {
        val origin = 1_760_000_000_000L
        val records = List(size) { i ->
            val id = (size - i).toLong()
            val video = i % 10 == 5
            val taken = origin - i * 1_800_000L -
                if (i % 10 == 0) (i % 73L) * 86_400_000L else 0L
            val kind = if (video) "video" else "images"
            MediaRecord(id, "content://media/external_primary/$kind/media/$id",
                "Synthetic-$id.${if (video) "mp4" else "jpg"}",
                "DCIM/SYNTHETIC-${i % 12}/", 1_000_000L + id,
                taken, video, durationMillis = if (video) 5000L else 0L,
                modifiedAtSeconds = (origin - i * 1_800_000L) / 1000L,
                volumeName = "external_primary", providerVersion = "synthetic-v1",
                generationModified = id, widthPixels = 4000, heightPixels = 3000)
        }
        file.outputStream().use {
            GalleryInventoryCacheCodec.encode(it, GalleryMediaGrant(true, true, false), records,
                checkpoint = GalleryMediaCheckpoint(mapOf("external_primary" to "synthetic-v1"),
                    mapOf("external_primary" to size.toLong())))
        }
    }

    private fun runChild(cache: File, size: Int, mode: String, sample: Int, dir: File): List<String> {
        val output = File(dir, "$size-$mode-$sample.txt")
        val java = File(System.getProperty("java.home"), "bin/java")
        // Gradle test workers may expose only gradle-worker.jar in
        // java.class.path. Include their actual test/application URL loaders.
        val entries = linkedSetOf<String>()
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }
                .forEach { entries.add(File(it.toURI()).path) }
            loader = loader.parent
        }
        for (type in listOf(javaClass, CachedFirstFrameStageChild::class.java,
            GalleryInventoryCacheCodec::class.java, MediaRecord::class.java, Unit::class.java)) {
            type.protectionDomain?.codeSource?.location?.takeIf { it.protocol == "file" }
                ?.let { entries.add(File(it.toURI()).path) }
        }
        entries.addAll(System.getProperty("java.class.path").split(File.pathSeparator))
        val command = mutableListOf(java.absolutePath, "-Xmx768m")
        if (mode == "cold") command.add("-Xint")
        command += listOf("-cp", entries.joinToString(File.pathSeparator),
            CachedFirstFrameStageChild::class.java.name, cache.absolutePath,
            size.toString(), mode, sample.toString())
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
        try {
            check(process.waitFor(120, TimeUnit.SECONDS)) { "Stage probe timed out: $size $mode" }
            val log = output.readText()
            check(process.exitValue() == 0) { "Stage probe failed: $size $mode\n$log" }
            val rows = log.lineSequence().filter { it.startsWith("| $size | $mode |") }.toList()
            check(rows.size == if (mode == "cold") 1 else 5) { "Missing stage rows: $log" }
            return rows
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Standalone desktop JVM entry point, not an Android instrumentation test. */
object CachedFirstFrameStageChild {
    @JvmStatic fun main(args: Array<String>) {
        val cache = File(args[0])
        val size = args[1].toInt()
        val mode = args[2]
        val sample = args[3].toInt()
        require(mode == "cold" || mode == "warm")
        val zone = ZoneId.of("Europe/Amsterdam")
        val locale = Locale.UK
        val clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), zone)

        fun measure(index: Int, report: Boolean) {
            val start = System.nanoTime()
            val snapshot = checkNotNull(cache.inputStream().use { GalleryInventoryCacheCodec.decode(it) })
            val decoded = System.nanoTime()
            val sorted = sortMedia(snapshot.items, MediaSort.default, 0L, locale)
            val sortedAt = System.nanoTime()
            val timeline = buildGridTimelineIndex(sorted.size,
                { sorted[it].takenAtMillis }, { sorted[it].uri },
                TimelineSort.NEWEST, GridDensity.MEDIUM, zone, locale,
                mergeSparseDaysBelow = 0, clock = clock)
            val indexed = System.nanoTime()
            check(snapshot.items.size == size && sorted.size == size && timeline.mediaCount == size)
            check(snapshot.grant == GalleryMediaGrant(true, true, false))
            check(snapshot.checkpoint?.generations?.get("external_primary") == size.toLong())
            check(timeline.grouped && timeline.headers.isNotEmpty())
            check(sorted.zipWithNext().all { (a, b) ->
                a.takenAtMillis >= b.takenAtMillis &&
                    (a.takenAtMillis != b.takenAtMillis || a.id <= b.id)
            })
            if (report) {
                fun ms(nanos: Long) = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
                println("| $size | $mode | $index | ${ms(decoded - start)} | " +
                    "${ms(sortedAt - decoded)} | ${ms(indexed - sortedAt)} | ${ms(sortedAt - start)} |")
            }
        }
        if (mode == "cold") measure(sample + 1, true)
        else {
            repeat(5) { measure(it, false) }
            repeat(5) { measure(it + 1, true) }
        }
    }
}
