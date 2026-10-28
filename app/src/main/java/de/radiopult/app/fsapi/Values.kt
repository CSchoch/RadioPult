package de.radiopult.app.fsapi

import androidx.annotation.StringRes
import de.radiopult.app.R
import java.util.Locale

/** Umrechnung der Rohwerte, die das Radio liefert, in lesbaren Text. */
object Values {

    /** Die IP-Adresse kommt als vorzeichenlose 32-Bit-Zahl, z. B. 3232235976 = 192.168.1.200. */
    fun ipAddress(raw: String?): String? {
        val n = raw?.trim()?.toLongOrNull()?.takeIf { it in 1..0xFFFFFFFFL } ?: return null
        return (3 downTo 0).joinToString(".") { ((n shr (8 * it)) and 0xFF).toString() }
    }

    private val languageNames = mapOf(
        "english" to "English",
        "danish" to "Dansk",
        "dutch" to "Nederlands",
        "finnish" to "Suomi",
        "french" to "Français",
        "german" to "Deutsch",
        "italian" to "Italiano",
        "norwegian" to "Norsk",
        "polish" to "Polski",
        "portuguese" to "Português",
        "spanish" to "Español",
        "swedish" to "Svenska",
        "turkish" to "Türkçe",
        "czech" to "Čeština",
        "slovak" to "Slovenčina",
        "hungarian" to "Magyar",
        "greek" to "Ελληνικά",
        "russian" to "Русский",
    )

    /** Manche Radios nennen die Sprachen englisch („German“) – angezeigt wird der eigene Name („Deutsch“). */
    fun languageName(label: String): String = languageNames[label.trim().lowercase()] ?: label.trim()

    /** Anzeigename der Zeitquelle; null, wenn der Rohwert selbst angezeigt wird (z. B. „DAB“). */
    @StringRes
    fun clockSourceRes(label: String): Int? = when (label.trim().uppercase()) {
        "FM" -> R.string.clock_source_fm
        "NETWORK", "INTERNET", "NET" -> R.string.clock_source_internet
        "NO_UPDATE", "NONE", "MANUAL" -> R.string.clock_source_manual
        else -> null
    }

    /** Ob die Uhrzeit bei dieser Zeitquelle automatisch gestellt wird. */
    fun clockSourceAutomatic(label: String): Boolean =
        label.trim().uppercase() !in setOf("NO_UPDATE", "NONE", "MANUAL")

    /** Schlummer-Restzeit in Minuten (aufgerundet). */
    fun sleepMinutes(seconds: Int): Int = (seconds + 59) / 60

    /** Umkehrung von [ipAddress]: „192.168.1.200“ → 3232235976; null bei ungültiger Eingabe. */
    fun ipNumber(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { it.toIntOrNull()?.takeIf { b -> b in 0..255 } ?: return null }
        return bytes.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
    }

    /** UKW-Frequenz in kHz als „98,40 MHz“ (Trennzeichen nach Sprache). */
    fun frequency(khz: Int): String = String.format(Locale.getDefault(), "%.2f MHz", khz / 1000.0)

    /** Spielzeit in Millisekunden als „3:07“ bzw. „1:02:03“. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Abstand zu UTC in Sekunden als „UTC+01:00“. */
    fun utcOffset(seconds: Int): String {
        val sign = if (seconds < 0) "−" else "+"
        val abs = kotlin.math.abs(seconds)
        return "UTC$sign%02d:%02d".format(abs / 3600, (abs % 3600) / 60)
    }

    /** Datum und Uhrzeit, wie das Radio sie erwartet: „YYYYMMDD“ und „HHMMSS“. */
    fun clockDate(year: Int, month: Int, day: Int): String = String.format(Locale.ROOT, "%04d%02d%02d", year, month, day)
    fun clockTime(hour: Int, minute: Int, second: Int): String = String.format(Locale.ROOT, "%02d%02d%02d", hour, minute, second)
}

/**
 * Was die laufende Quelle kann (netRemote.play.caps, ein Bit je Fähigkeit wie bei UNDOK:
 * 0 Pause, 1 Stopp, 2 weiter, 3 zurück, 4/5 spulen, 6 Zufall, 7 Wiederholen, 8 Springen, …, 16 Titel wiederholen).
 */
@JvmInline
value class PlayCaps(val bits: Long) {
    private fun has(bit: Int) = bits and (1L shl bit) != 0L

    val skipNext get() = has(2)
    val skipPrevious get() = has(3)
    val shuffle get() = has(6)
    val repeat get() = has(7)
    val seek get() = has(8)
    val repeatOne get() = has(16)
}
