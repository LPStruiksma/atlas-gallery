package nl.atlas.gallery.featurecuration

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** #663: Photo DNA and Declutter copy in plain words, plurals and phone-style sizes. */
class CleanupCopyTest {
    private val us = Locale.US

    @Test fun pluralsFollowTheCount() {
        assertEquals("Duplicates · 1 group", PhotoDnaCopy.duplicatesHeader(1))
        assertEquals("Duplicates · 2 groups", PhotoDnaCopy.duplicatesHeader(2))
        assertEquals("Duplicates · 0 groups", PhotoDnaCopy.duplicatesHeader(0))
        assertEquals("Similar photos · 1 pair", PhotoDnaCopy.similarHeader(1))
        assertEquals("Similar photos · 3 pairs", PhotoDnaCopy.similarHeader(3))
        assertEquals("Checked 1 file", PhotoDnaCopy.checked(1, 0))
        assertEquals("Checked 2 files · 5 more next time", PhotoDnaCopy.checked(2, 5))
        assertEquals("2 copies · up to 1 KB to free", PhotoDnaCopy.groupHeader(2, 323, us))
        assertEquals("1 copy · up to 0 KB to free", PhotoDnaCopy.groupHeader(1, 0, us))
    }

    @Test fun sizesReadLikeThePhoneShowsThem() {
        assertEquals("0 KB", cleanupSize(0, us))
        assertEquals("0 KB", cleanupSize(-5, us))
        assertEquals("1 KB", cleanupSize(1, us))
        assertEquals("1 KB", cleanupSize(323, us))
        assertEquals("1 KB", cleanupSize(1000, us))
        assertEquals("2 KB", cleanupSize(1001, us))
        assertEquals("999 KB", cleanupSize(999_000, us))
        assertEquals("1.0 MB", cleanupSize(999_500, us))
        assertEquals("4.2 MB", cleanupSize(4_200_000, us))
        assertEquals("10.0 MB", cleanupSize(10_000_000, us))
        assertEquals("323 MB", cleanupSize(323_400_000, us))
        assertEquals("1.0 GB", cleanupSize(999_700_000, us))
        assertEquals("2.5 GB", cleanupSize(2_500_000_000, us))
        assertEquals("1.2 TB", cleanupSize(1_200_000_000_000, us))
        assertEquals("4,2 MB", cleanupSize(4_200_000, Locale.GERMANY))
    }

    @Test fun noDeveloperWordsInTheCopy() {
        val all = listOf(PhotoDnaCopy.ENTRY_SUBTITLE,
            PhotoDnaCopy.KEEPER_USER, PhotoDnaCopy.KEEPER_PEOPLE,
            PhotoDnaCopy.KEEPER_FAVOURITE, PhotoDnaCopy.KEEPER_PROTECTED_FOLDER,
            PhotoDnaCopy.KEEPER_DEFAULT, PhotoDnaCopy.NO_ACCESS_BODY,
            PhotoDnaCopy.checked(4, 1), PhotoDnaCopy.groupHeader(3, 5_000_000, us))
        for (text in all) for (word in listOf("catalogue", "full-byte", "explicitly",
                "authorised", "MiB", " B ", "keeper", "recheck"))
            assertFalse("\"$word\" in \"$text\"", text.contains(word, ignoreCase = true))
    }
}
