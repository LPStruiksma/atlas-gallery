package nl.atlas.gallery.featureeditor.video.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.OverlayLayer

/** Pure data: microsecond-accurate source ranges are half-open [inUs, outUs). */
data class SpeedSection(val inUs: Long, val outUs: Long, val rate: Double = 1.0) {
    fun valid(): Boolean = inUs >= 0 && outUs > inUs && rate.isFinite() && rate in 0.25..4.0
}

/** A clip's position is in output time; speed sections cover its SOURCE range exactly. */
data class VideoClip(
    val id: String,
    val sourceUri: String,
    val inUs: Long,
    val outUs: Long,
    val timelineStartUs: Long,
    val speeds: List<SpeedSection> = emptyList(),
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val crop: NormRect = NormRect.FULL,
    val rotateQuarterTurns: Int = 0,
    val filter: EditOperation.Filter? = null,
) {
    fun validate(): String? {
        if (id.isBlank() || sourceUri.isBlank()) return "blank clip id or source"
        if (inUs < 0 || outUs <= inUs || outUs > MAX_VIDEO_US) return "invalid source trim"
        if (timelineStartUs < 0 || timelineStartUs > MAX_VIDEO_US) return "invalid timeline position"
        if (!volume.isFinite() || volume !in 0.0..1.0) return "invalid source volume"
        if (!crop.isValid() || rotateQuarterTurns !in 0..3) return "invalid clip geometry"
        filter?.validate()?.let { return it }
        if (speeds.isNotEmpty()) {
            var end = inUs
            for (section in speeds) {
                if (!section.valid() || section.inUs != end || section.outUs > outUs) {
                    return "speed sections must partition the trimmed source in order"
                }
                end = section.outUs
            }
            if (end != outUs) return "speed sections must cover the clip"
        }
        val duration = runCatching { playbackDurationUs() }.getOrNull()
            ?: return "invalid playback duration"
        if (duration <= 0 || duration > MAX_VIDEO_US ||
            timelineStartUs > MAX_VIDEO_US - duration
        ) return "playback exceeds timeline bounds"
        return null
    }

    /** Round once at the end, using decimal arithmetic; never accumulate double errors. */
    fun playbackDurationUs(): Long {
        val parts = if (speeds.isEmpty()) listOf(SpeedSection(inUs, outUs)) else speeds
        val exact = parts.fold(BigDecimal.ZERO) { sum, part ->
            sum.add(
                BigDecimal.valueOf(part.outUs - part.inUs)
                    .divide(BigDecimal.valueOf(part.rate), MathContext.DECIMAL128)
            )
        }
        return exact.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    val endUs: Long get() = timelineStartUs + playbackDurationUs()
}

enum class VideoTransitionKind { CUT, FADE, DISSOLVE, SLIDE }

data class VideoTransition(
    val fromClipId: String,
    val toClipId: String,
    val kind: VideoTransitionKind,
    val durationUs: Long = 0L,
)

/** Global output-time layer, with placement/assets shared with the photo editor. */
data class VideoOverlay(val startUs: Long, val endUs: Long, val layer: OverlayLayer)

data class VideoAudioBed(
    val id: String,
    val sourceUri: String,
    val startUs: Long,
    val endUs: Long,
    val volume: Double = 1.0,
    val loop: Boolean = false,
)

/**
 * Non-destructive video project, no pixels, file access, Android runtime or Media3 dependency.
 * Explicit clip positions permit gaps, but never overlapping video clips.
 */
data class VideoEditRecipe(
    val clips: List<VideoClip> = emptyList(),
    val overlays: List<VideoOverlay> = emptyList(),
    val transitions: List<VideoTransition> = emptyList(),
    val audioBeds: List<VideoAudioBed> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion != CURRENT_SCHEMA_VERSION) errors += "unsupported video schema version"
        val ids = mutableSetOf<String>()
        val sorted = clips.sortedBy { it.timelineStartUs }
        val durations = mutableMapOf<String, Long>()
        for (clip in clips) {
            if (!ids.add(clip.id)) errors += "duplicate clip id"
            val problem = clip.validate()
            if (problem != null) errors += problem
            else durations[clip.id] = clip.playbackDurationUs()
        }
        sorted.zipWithNext().forEach { (a, b) ->
            if (durations[a.id] != null && a.endUs > b.timelineStartUs) {
                errors += "overlapping video clips"
            }
        }
        val total = sorted.mapNotNull { clip ->
            if (durations[clip.id] != null) clip.endUs else null
        }.maxOrNull() ?: 0L
        val layerIds = mutableSetOf<String>()
        overlays.forEach { overlay ->
            if (overlay.startUs < 0 || overlay.endUs <= overlay.startUs || overlay.endUs > total) {
                errors += "overlay outside output duration"
            }
            overlay.layer.validate()?.let { errors += it }
            if (!layerIds.add(overlay.layer.layerId)) errors += "duplicate overlay layer"
        }
        val transitionPairs = mutableSetOf<Pair<String, String>>()
        val transitionBudgets = mutableMapOf<String, Long>()
        transitions.forEach { t ->
            val pair = t.fromClipId to t.toClipId
            if (!transitionPairs.add(pair)) errors += "duplicate transition"
            val index = sorted.indexOfFirst { it.id == t.fromClipId }
            val left = sorted.getOrNull(index)
            val right = sorted.getOrNull(index + 1)
            if (left == null || right == null || right.id != t.toClipId ||
                durations[left.id] == null || durations[right.id] == null ||
                left.endUs != right.timelineStartUs
            ) {
                errors += "transition must join contiguous neighbouring clips"
            } else {
                if (t.kind == VideoTransitionKind.CUT && t.durationUs != 0L ||
                    t.kind != VideoTransitionKind.CUT &&
                    (t.durationUs <= 0 || t.durationUs > minOf(durations.getValue(left.id), durations.getValue(right.id)))
                ) errors += "invalid transition duration"
                if (t.durationUs > 0) {
                    transitionBudgets[left.id] = (transitionBudgets[left.id] ?: 0L) + t.durationUs
                    transitionBudgets[right.id] = (transitionBudgets[right.id] ?: 0L) + t.durationUs
                }
            }
        }
        transitionBudgets.forEach { (id, used) ->
            if (used > (durations[id] ?: 0L)) errors += "transitions exceed clip duration"
        }
        val audioIds = mutableSetOf<String>()
        audioBeds.forEach { bed ->
            if (bed.id.isBlank() || !audioIds.add(bed.id) || bed.sourceUri.isBlank()) {
                errors += "invalid audio bed identity"
            }
            if (bed.startUs < 0 || bed.endUs <= bed.startUs || bed.endUs > total ||
                !bed.volume.isFinite() || bed.volume !in 0.0..1.0
            ) errors += "invalid audio bed timing or volume"
        }
        return errors
    }

    fun requireValid(): VideoEditRecipe {
        require(validate().isEmpty()) { "Invalid video edit recipe" }
        return this
    }

    val durationUs: Long
        get() {
            requireValid()
            return clips.maxOfOrNull { it.endUs } ?: 0L
        }

    /**
     * Split at a source timestamp, preserving speed, source trim, geometry and filters.
     * Rounding may change the final output length by one microsecond: following output
     * timestamps (including overlays/audio) are shifted at the splice to stay aligned.
     */
    fun split(clipId: String, sourceUs: Long, newClipId: String): VideoEditRecipe {
        requireValid()
        require(newClipId.isNotBlank() && clips.none { it.id == newClipId }) { "Clip id already exists" }
        val target = clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found")
        require(sourceUs > target.inUs && sourceUs < target.outUs) { "Split outside clip" }
        fun part(before: Boolean): List<SpeedSection> =
            if (target.speeds.isEmpty()) emptyList() else target.speeds.mapNotNull { s ->
                val start = if (before) s.inUs else maxOf(s.inUs, sourceUs)
                val end = if (before) minOf(s.outUs, sourceUs) else s.outUs
                if (end <= start) null else SpeedSection(start, end, s.rate)
            }
        val left = target.copy(outUs = sourceUs, speeds = part(true))
        val right = target.copy(
            id = newClipId, inUs = sourceUs,
            timelineStartUs = left.endUs, speeds = part(false),
        )
        require(left.validate() == null && right.validate() == null) { "Split creates empty output" }
        val splice = left.endUs
        val change = left.playbackDurationUs() + right.playbackDurationUs() - target.playbackDurationUs()
        fun shift(time: Long): Long = if (time >= splice) time + change else time
        val updatedClips = clips.flatMap { c ->
            if (c.id == clipId) listOf(left, right)
            else listOf(if (c.timelineStartUs >= target.endUs) c.copy(timelineStartUs = c.timelineStartUs + change) else c)
        }
        val updated = copy(
            clips = updatedClips,
            overlays = overlays.map { it.copy(startUs = shift(it.startUs), endUs = shift(it.endUs)) },
            audioBeds = audioBeds.map { it.copy(startUs = shift(it.startUs), endUs = shift(it.endUs)) },
            transitions = transitions.map {
                if (it.fromClipId == clipId) it.copy(fromClipId = newClipId) else it
            } + VideoTransition(clipId, newClipId, VideoTransitionKind.CUT),
        )
        return updated.requireValid()
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val SCHEMA_NAME = "atlas.video-edit-recipe"
    }
}

/** This ceiling catches overflow, implausible timeline positions and unbounded projects. */
private const val MAX_VIDEO_US = 86_400_000_000L
