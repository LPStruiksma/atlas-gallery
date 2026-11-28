package nl.atlas.gallery.featureeditor.video.engine

import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.OverlayKind
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoEngineTest {
    private fun clip(
        id: String = "a", start: Long = 0,
        inUs: Long = 0, outUs: Long = 10_000_000,
        speeds: List<SpeedSection> = emptyList(),
    ) = VideoClip(
        id = id, sourceUri = "content://synthetic/$id", inUs = inUs,
        outUs = outUs, timelineStartUs = start, speeds = speeds,
    )

    private val sections = listOf(
        SpeedSection(0, 4_000_000, 2.0),
        SpeedSection(4_000_000, 10_000_000, 1.0),
    )

    private fun twoClipProject(): VideoEditRecipe {
        val first = clip(speeds = sections).copy(
            volume = 0.75,
            crop = NormRect(0.1, 0.1, 0.9, 0.9),
            rotateQuarterTurns = 1,
            filter = EditOperation.Filter("filter-a", "atlas-warm", strength = 0.4),
        )
        val second = clip("b", 8_000_000, 100, 4_000_100).copy(muted = true)
        return VideoEditRecipe(
            clips = listOf(first, second),
            transitions = listOf(VideoTransition("a", "b", VideoTransitionKind.FADE, 500_000)),
            overlays = listOf(VideoOverlay(
                1_000_000, 2_000_000,
                OverlayLayer("text-1", OverlayKind.TEXT, 0.4, 0.6,
                    props = linkedMapOf("z" to "last", "a" to "first")),
            )),
            audioBeds = listOf(VideoAudioBed("music-1", "content://synthetic/audio", 0, 12_000_000, 0.4)),
        )
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (t is T) return
            throw t
        }
        fail("Expected failure")
    }

    @Test fun videoRecipeRoundTripsDeterministically() {
        val recipe = twoClipProject().requireValid()
        val encoded = VideoRecipeJson.encode(recipe)
        val decoded = VideoRecipeJson.decode(encoded)
        assertEquals(recipe, decoded)
        assertEquals(encoded, VideoRecipeJson.encode(decoded))
        assertTrue(encoded.indexOf("\"a\":\"first\"") < encoded.indexOf("\"z\":\"last\""))
        assertEquals(12_000_000L, decoded.durationUs)
    }

    @Test fun quoteAndUnicodeSourceRoundTrip() {
        val uri = "content://synthetic/\u00e9 /\"quote\"\\backslash\n"
        val project = VideoEditRecipe(clips = listOf(clip().copy(sourceUri = uri)))
        assertEquals(project, VideoRecipeJson.decode(VideoRecipeJson.encode(project)))
    }

    @Test fun rejectsMalformedFutureOrDuplicateJson() {
        val json = VideoRecipeJson.encode(twoClipProject())
        expectFailure<VideoRecipeFormatException> {
            VideoRecipeJson.decode(json.replace("\"version\":1", "\"version\":2"))
        }
        expectFailure<VideoRecipeFormatException> {
            VideoRecipeJson.decode(json.replace("\"version\":1", "\"version\":1,\"version\":1"))
        }
        expectFailure<VideoRecipeFormatException> {
            VideoRecipeJson.decode(json.replace("\"clips\":", "\"unrecognised\":0,\"clips\":"))
        }
        expectFailure<VideoRecipeFormatException> { VideoRecipeJson.decode(json + " {}") }
        expectFailure<VideoRecipeFormatException> { VideoRecipeJson.decode("{") }
    }

    @Test fun rejectsOverlappingClipsAndUnpartitionedOrOutOfBoundsSpeed() {
        assertTrue(VideoEditRecipe(clips = listOf(
            clip(), clip("b", 9_999_999),
        )).validate().contains("overlapping video clips"))
        assertTrue(VideoEditRecipe(clips = listOf(
            clip(speeds = listOf(SpeedSection(1, 10_000_000, 1.0))),
        )).validate().isNotEmpty())
        assertTrue(VideoEditRecipe(clips = listOf(
            clip(speeds = listOf(SpeedSection(0, 10_000_001, 1.0))),
        )).validate().isNotEmpty())
        listOf(0.0, 0.249, 4.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { rate ->
            assertTrue(VideoEditRecipe(clips = listOf(
                clip(speeds = listOf(SpeedSection(0, 10_000_000, rate))),
            )).validate().isNotEmpty())
        }
    }

    @Test fun durationMathAcrossSupportedSpeedsAndSelectedSections() {
        val expected = mapOf(
            0.25 to 32_000_000L,
            0.5 to 16_000_000L,
            1.0 to 8_000_000L,
            2.0 to 4_000_000L,
            4.0 to 2_000_000L,
        )
        for ((speed, duration) in expected) {
            val c = clip(outUs = 8_000_000,
                speeds = listOf(SpeedSection(0, 8_000_000, speed)))
            assertEquals(duration, c.playbackDurationUs())
            assertEquals(duration, VideoEditRecipe(clips = listOf(c)).durationUs)
        }
        assertEquals(8_000_000L, clip(speeds = sections).playbackDurationUs())
    }

    @Test fun splitPreservesSpeedEffectsTimingAndUndoRedo() {
        val initial = twoClipProject()
        val split = initial.split("a", 3_000_000, "a-right")
        assertEquals(3, split.clips.size)
        assertEquals(8_000_000L, split.clips[2].timelineStartUs)
        assertEquals(1_500_000L, split.clips[1].timelineStartUs)
        assertEquals(6_500_000L, split.clips[1].playbackDurationUs())
        assertEquals(initial.clips[0].filter, split.clips[1].filter)
        assertEquals(2, split.transitions.size)
        assertTrue(split.transitions.contains(
            VideoTransition("a", "a-right", VideoTransitionKind.CUT)))
        assertTrue(split.transitions.contains(
            VideoTransition("a-right", "b", VideoTransitionKind.FADE, 500_000)))
        val history = VideoEditHistory.start(initial)
        val preview = history.preview(split)
        assertEquals(initial, preview.committed)
        assertEquals(split, preview.current)
        assertEquals(initial, preview.setComparing(true).displayed)
        assertEquals(initial, preview.undo().current)
        val committed = preview.commit()
        assertEquals(split, committed.committed)
        assertEquals(initial, committed.undo().committed)
        assertEquals(split, committed.undo().redo().committed)
        assertFalse(committed.undo().apply(initial.copy(transitions = emptyList())).canRedo)
    }

    @Test fun previewCommitCancelAndHistoryStorageAreStable() {
        val initial = VideoEditRecipe(clips = listOf(clip()))
        val changed = initial.copy(clips = listOf(clip().copy(muted = true)))
        val history = VideoEditHistory.start(initial).preview(changed)
        assertFalse(history.isDirty)
        assertEquals(initial, history.cancelPending().current)
        val saved = history.commit()
        assertTrue(saved.isDirty)
        assertTrue(saved.canUndo)
        val restored = VideoRecipeJson.decodeHistory(VideoRecipeJson.encodeHistory(saved))
        assertEquals(saved, restored)
        assertEquals(initial, saved.revertAll().committed)
        assertEquals(changed, saved.revertAll().undo().committed)
    }

    @Test fun renderPlanMapsClipEffectsAndOutputJobs() {
        val recipe = twoClipProject()
        val plan = recipe.toRenderPlan()
        assertEquals(12_000_000L, plan.durationUs)
        assertEquals(2, plan.clips.size)
        assertEquals(8_000_000L, plan.clips[0].timelineEndUs)
        assertEquals(2, plan.clips[0].speeds.size)
        assertEquals(recipe.clips[0].crop, plan.clips[0].crop)
        assertEquals(recipe.clips[0].filter, plan.clips[0].filter)
        assertTrue(plan.preserveSourceDynamicRange)
        assertEquals(VideoRenderOutput.StillFrame(3_000_000),
            recipe.toRenderPlan(VideoRenderOutput.StillFrame(3_000_000)).output)
        assertEquals(VideoRenderOutput.Gif(0, 4_000_000, 15),
            recipe.toRenderPlan(VideoRenderOutput.Gif(0, 4_000_000, 15)).output)
    }

    @Test fun outputJobsRejectGapsInvalidFramesAndInvalidGifTiming() {
        val project = VideoEditRecipe(clips = listOf(
            clip(outUs = 2_000_000), clip("b", 4_000_000, 0, 2_000_000),
        ))
        expectFailure<IllegalArgumentException> {
            project.toRenderPlan(VideoRenderOutput.StillFrame(3_000_000))
        }
        expectFailure<IllegalArgumentException> {
            project.toRenderPlan(VideoRenderOutput.Gif(1_000_000, 5_000_000, 25))
        }
        expectFailure<IllegalArgumentException> {
            project.toRenderPlan(VideoRenderOutput.Gif(0, 2_000_000, 0))
        }
        assertEquals(6_000_000L, project.durationUs)
    }

    @Test fun invalidOverlaysAudioAndTransitionsFailClosed() {
        val source = twoClipProject()
        assertTrue(source.copy(transitions = listOf(
            VideoTransition("a", "b", VideoTransitionKind.DISSOLVE, 5_000_000),
        )).validate().isNotEmpty())
        assertTrue(source.copy(overlays = listOf(
            source.overlays[0].copy(endUs = 13_000_000),
        )).validate().isNotEmpty())
        assertTrue(source.copy(audioBeds = listOf(
            source.audioBeds[0].copy(volume = Double.NaN),
        )).validate().isNotEmpty())
        assertTrue(source.copy(clips = listOf(
            source.clips[0].copy(crop = NormRect(0.8, 0.2, 0.1, 0.9)),
        )).validate().isNotEmpty())
    }
}
