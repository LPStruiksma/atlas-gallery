package nl.atlas.gallery.featureorganization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** #365: one replace-only selection result; an old timer never clears a newer one. */
class SelectionResultMessagesTest {
    private var clock = 1_000L
    private val messages = SelectionResultMessages { clock }

    @Test fun secondResultReplacesTheFirstWithoutStacking() {
        messages.show("Trash canceled · your photos are unchanged", SelectionResultDuration.SHORT)
        val first = messages.current!!
        messages.show("Moved selected items to Android Trash")
        val second = messages.current!!
        assertEquals("Moved selected items to Android Trash", second.text)
        assertEquals(SelectionResultDuration.LONG, second.duration)
        assertNotEquals(first.generation, second.generation)

        // The first message's timer fires late: the second stays.
        messages.dismiss(first.generation)
        assertEquals(second, messages.current)
        messages.dismiss(second.generation)
        assertNull(messages.current)
    }

    @Test fun aResultShownWhileHiddenDoesNotLingerLater() {
        messages.show("Move canceled · your photos are unchanged", SelectionResultDuration.SHORT)
        val shown = messages.current!!
        assertEquals(4_000L, messages.remainingMillis(shown, 4_000L))
        clock += 1_500L
        assertEquals(2_500L, messages.remainingMillis(shown, 4_000L))
        clock += 60_000L
        assertEquals(0L, messages.remainingMillis(shown, 4_000L))
    }

    @Test fun savingAndRestoringPreservesExactTextAndElapsedLifetime() {
        val text = "Moved 37 items to Summer holiday Friesland 2026 · 3 couldn't be moved · 5 already in destination"
        messages.show(text)
        val firstGeneration = messages.current!!.generation
        clock += 2_250L
        val saver = SelectionResultMessages.Saver
        val snapshot = with(saver) { with(object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }) { save(messages) } }!!
        assertEquals(text, snapshot[0])
        assertEquals(1_000L, snapshot[3])
        // Restoration's system clock is the actual monotonic clock: its saved
        // timestamp is unchanged, never restarted at restore.
        val restored = SelectionResultMessages.Saver.restore(snapshot)!!
        assertEquals(text, restored.current!!.text)
        assertEquals(firstGeneration, restored.current!!.generation)
        restored.show("A newer result")
        restored.dismiss(firstGeneration)
        assertEquals("A newer result", restored.current!!.text)
    }

    @Test fun expiryIsNotRefreshedByASecondSnapshot() {
        messages.show("Copy complete", SelectionResultDuration.SHORT)
        clock += 2_000L
        val saver = SelectionResultMessages.Saver
        val snapshot = with(saver) { save(messages) }!!
        assertEquals(1_000L, snapshot[3])
        // A second snapshot of the same active result keeps its original
        // monotonic shown-at instant rather than allocating a new deadline.
        clock += 1_500L
        val again = with(saver) { save(messages) }!!
        assertEquals(1_000L, again[3])
        clock += 600L
        assertEquals(0L, messages.remainingMillis(messages.current!!, 4_000L))
    }

    @Test fun blankTextIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { messages.show("  ") }
        assertNull(messages.current)
    }
}
