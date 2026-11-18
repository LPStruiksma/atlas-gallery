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

    @Test fun blankTextIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { messages.show("  ") }
        assertNull(messages.current)
    }
}
