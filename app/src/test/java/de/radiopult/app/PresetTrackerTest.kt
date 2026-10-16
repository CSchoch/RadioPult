package de.radiopult.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTrackerTest {

    private val dab = listOf(Preset(0, "SWR3"), Preset(1, "DLF"), Preset(2, ""))
    private val web = listOf(Preset(0, "FluxFM"), Preset(1, "radioeins"), Preset(2, "byte.fm"))

    @Test
    fun listCountsOnlyWhenItArrivesTwiceTheSame() {
        val t = PresetTracker()
        assertTrue(t.needsLoad(1))
        assertEquals(dab, t.onLoaded(1, dab))
        assertTrue(t.needsLoad(1))
        assertEquals(dab, t.onLoaded(1, dab))
        assertFalse(t.needsLoad(1))
    }

    @Test
    fun incompleteListIsReplacedByTheFullOne() {
        val t = PresetTracker()
        assertEquals(web.take(1), t.onLoaded(1, web.take(1)))
        assertEquals(web, t.onLoaded(1, web))
        assertTrue(t.needsLoad(1))
        t.onLoaded(1, web)
        assertFalse(t.needsLoad(1))
    }

    @Test
    fun failedLoadKeepsTryingAndKeepsTheLastList() {
        val t = PresetTracker()
        assertNull(t.onLoaded(1, null))
        assertTrue(t.needsLoad(1))
        assertTrue(t.needsReset(1))
        assertEquals(web, t.onLoaded(1, web))
        assertFalse(t.needsReset(1))
        assertEquals(web, t.onLoaded(1, null))
        assertTrue(t.needsLoad(1))
    }

    @Test
    fun listOfPreviousSourceIsIgnoredAfterSwitching() {
        val t = PresetTracker()
        t.onLoaded(4, dab)
        t.onLoaded(4, dab)
        assertTrue(t.needsLoad(1))
        assertTrue(t.needsReset(1))
        // das Radio liefert nach dem Umschalten noch die DAB-Favoriten
        assertNull(t.onLoaded(1, dab))
        assertNull(t.onLoaded(1, dab))
        assertEquals(web, t.onLoaded(1, web))
        t.onLoaded(1, web)
        assertFalse(t.needsLoad(1))
    }

    @Test
    fun sharedFavoritesAreShownInTheEnd() {
        val t = PresetTracker(maxAttempts = 3)
        t.onLoaded(4, dab)
        t.onLoaded(4, dab)
        assertNull(t.onLoaded(1, dab))
        assertNull(t.onLoaded(1, dab))
        assertEquals(dab, t.onLoaded(1, dab))
        assertFalse(t.needsLoad(1))
    }

    @Test
    fun sourceWithoutFavoritesStopsAsking() {
        val t = PresetTracker(maxAttempts = 3)
        repeat(3) { t.onLoaded(7, null) }
        assertFalse(t.needsLoad(7))
    }

    @Test
    fun invalidateReloadsWithoutReset() {
        val t = PresetTracker()
        t.onLoaded(1, web)
        t.onLoaded(1, web)
        t.invalidate()
        assertTrue(t.needsLoad(1))
        assertFalse(t.needsReset(1))
        val changed = web.map { if (it.key == 2) it.copy(name = "") else it }
        assertEquals(changed, t.onLoaded(1, changed))
        t.onLoaded(1, changed)
        assertFalse(t.needsLoad(1))
    }
}
