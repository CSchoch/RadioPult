package de.radiopult.app.fsapi

import androidx.annotation.StringRes
import de.radiopult.app.R

/** Umrechnung der Rohwerte, die das Radio liefert, in lesbaren Text. */
object Values {

    /** Die IP-Adresse kommt als vorzeichenlose 32-Bit-Zahl, z. B. 3232235976 = 192.168.2.200. */
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
}
