package de.radiopult.app.fsapi

import de.radiopult.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuesTest {

    @Test
    fun convertsIpAddress() {
        assertEquals("192.168.1.56", Values.ipAddress("3232235832"))
        assertEquals("192.168.2.198", Values.ipAddress(" 3232236230 "))
        assertNull(Values.ipAddress("0"))
        assertNull(Values.ipAddress("abc"))
        assertNull(Values.ipAddress(null))
    }

    @Test
    fun showsNativeLanguageNames() {
        assertEquals("Deutsch", Values.languageName("German"))
        assertEquals("Français", Values.languageName("french "))
        assertEquals("Deutsch", Values.languageName("Deutsch"))
    }

    @Test
    fun namesClockSources() {
        assertEquals(R.string.clock_source_internet, Values.clockSourceRes("NETWORK"))
        assertNull(Values.clockSourceRes("DAB"))
        assertTrue(Values.clockSourceAutomatic("DAB"))
        assertFalse(Values.clockSourceAutomatic("NO_UPDATE"))
    }

    @Test
    fun roundsSleepUp() {
        assertEquals(0, Values.sleepMinutes(0))
        assertEquals(1, Values.sleepMinutes(1))
        assertEquals(15, Values.sleepMinutes(900))
        assertEquals(16, Values.sleepMinutes(901))
    }

    @Test
    fun convertsIpTextToNumber() {
        assertEquals(3232235976L, Values.ipNumber("192.168.1.200"))
        assertEquals("192.168.2.200", Values.ipAddress(Values.ipNumber("192.168.2.200").toString()))
        assertNull(Values.ipNumber("192.168.2"))
        assertNull(Values.ipNumber("192.168.2.256"))
    }

    @Test
    fun formatsTimes() {
        assertEquals("3:07", Values.duration(187_000))
        assertEquals("1:02:03", Values.duration(3_723_000))
        assertEquals("UTC+01:00", Values.utcOffset(3600))
        assertEquals("UTC+05:30", Values.utcOffset(19_800))
        assertEquals("UTC−03:00", Values.utcOffset(-10_800))
        assertEquals("20261028", Values.clockDate(2026, 10, 28))
        assertEquals("070509", Values.clockTime(7, 5, 9))
    }

    @Test
    fun readsPlayCaps() {
        val caps = PlayCaps((1L shl 2) or (1L shl 6) or (1L shl 16))
        assertTrue(caps.skipNext)
        assertFalse(caps.skipPrevious)
        assertTrue(caps.shuffle)
        assertFalse(caps.repeat)
        assertTrue(caps.repeatOne)
    }
}
