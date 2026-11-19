package nl.atlas.gallery

import nl.atlas.gallery.featuregrid.OriginalProportionsAlbumJourneyTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

// #448 diagnostic only, removed before review: one process, keyboard test first.
@RunWith(Suite::class)
@Suite.SuiteClasses(UnknownFacesKeyboardInsetsTest::class, OriginalProportionsAlbumJourneyTest::class)
class Diag448AfterKeyboardSuite

// Control: the album test alone in one process.
@RunWith(Suite::class)
@Suite.SuiteClasses(OriginalProportionsAlbumJourneyTest::class)
class Diag448AlbumOnlySuite
