package nl.atlas.gallery.featureeditor.video.engine

import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.NormRect

/** Outputs are requests, not exports; EDIT-13 owns actual Media3/MediaStore work. */
sealed interface VideoRenderOutput {
    data object Movie : VideoRenderOutput
    data class StillFrame(val atUs: Long) : VideoRenderOutput
    data class Gif(val startUs: Long, val endUs: Long, val fps: Int, val loop: Boolean = true) : VideoRenderOutput
}

/**
 * An adapter can turn each [VideoRenderClip] into one Media3 EditedMediaItem (trim,
 * speed/effects, per-clip audio) and use the sidecar tracks for compositions.
 * No Media3 type is referenced here; unsupported HDR or effect features must fail
 * explicitly in the downstream adapter instead of being dropped.
 */
data class VideoRenderClip(
    val id: String,
    val sourceUri: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
    val speeds: List<SpeedSection>,
    val volume: Double,
    val muted: Boolean,
    val crop: NormRect,
    val rotateQuarterTurns: Int,
    val filter: EditOperation.Filter?,
)

data class VideoRenderPlan(
    val clips: List<VideoRenderClip>,
    val overlays: List<VideoOverlay>,
    val transitions: List<VideoTransition>,
    val audioBeds: List<VideoAudioBed>,
    val durationUs: Long,
    val output: VideoRenderOutput,
    /** Adapter requirement: a missing HDR-preserving path is an error, never implicit SDR. */
    val preserveSourceDynamicRange: Boolean = true,
)

fun VideoEditRecipe.toRenderPlan(output: VideoRenderOutput = VideoRenderOutput.Movie): VideoRenderPlan {
    requireValid()
    require(clips.isNotEmpty()) { "Cannot render an empty video project" }
    val duration = durationUs
    when (output) {
        VideoRenderOutput.Movie -> Unit
        is VideoRenderOutput.StillFrame -> {
            require(output.atUs >= 0 && output.atUs < duration &&
                clips.any { output.atUs >= it.timelineStartUs && output.atUs < it.endUs }
            ) { "Frame lies outside video content" }
        }
        is VideoRenderOutput.Gif -> {
            require(output.startUs >= 0 && output.endUs > output.startUs &&
                output.endUs <= duration && output.fps in 1..60
            ) { "Invalid GIF export range" }
            // A gap is not a real video frame, so refuse rather than synthesising black.
            val ordered = clips.sortedBy { it.timelineStartUs }
            var cursor = output.startUs
            for (clip in ordered) {
                if (clip.endUs <= cursor || clip.timelineStartUs >= output.endUs) continue
                require(clip.timelineStartUs <= cursor) { "GIF range includes a timeline gap" }
                cursor = minOf(clip.endUs, output.endUs)
            }
            require(cursor == output.endUs) { "GIF range includes a timeline gap" }
        }
    }
    return VideoRenderPlan(
        clips = clips.sortedBy { it.timelineStartUs }.map { c ->
            VideoRenderClip(
                id = c.id, sourceUri = c.sourceUri,
                sourceInUs = c.inUs, sourceOutUs = c.outUs,
                timelineStartUs = c.timelineStartUs, timelineEndUs = c.endUs,
                speeds = if (c.speeds.isEmpty()) listOf(SpeedSection(c.inUs, c.outUs)) else c.speeds,
                volume = c.volume, muted = c.muted,
                crop = c.crop, rotateQuarterTurns = c.rotateQuarterTurns, filter = c.filter,
            )
        },
        overlays = overlays.sortedWith(compareBy({ it.startUs }, { it.layer.layerId })),
        transitions = transitions.sortedWith(compareBy({ it.fromClipId }, { it.toClipId })),
        audioBeds = audioBeds.sortedWith(compareBy({ it.startUs }, { it.id })),
        durationUs = duration,
        output = output,
    )
}
