package de.radiopult.app.fsapi

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
        assertEquals("Internet", Values.clockSourceName("NETWORK"))
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
}
