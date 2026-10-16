package de.radiopult.app

/**
 * Merkt sich, ob die Favoritenliste der aktuellen Quelle verlässlich geladen ist.
 *
 * Direkt nach einem Quellenwechsel liefert das Radio oft noch die Liste der alten Quelle,
 * eine unvollständige Liste oder gar keine. Deshalb gilt eine Liste erst als fertig, wenn sie
 * zweimal hintereinander gleich ankommt; bis dahin wird bei jeder Abfrage neu geladen.
 */
class PresetTracker(private val maxAttempts: Int = 5) {

    private var mode: Int? = null
    private var candidate: List<Preset>? = null
    private var previous: List<Preset>? = null // Liste der vorigen Quelle
    private var attempts = 0
    private var done = false

    /** Muss die Favoritenliste für [mode] (erneut) geladen werden? */
    fun needsLoad(mode: Int) = mode != this.mode || !done

    /** Noch keine brauchbare Liste für [mode]: dann die Liste im Radio neu aufbauen lassen. */
    fun needsReset(mode: Int) = mode != this.mode || candidate == null

    /**
     * Ergebnis eines Ladeversuchs für [mode]; [loaded] ist null, wenn das Radio die Liste nicht geliefert hat.
     * Gibt die bisher beste Liste zurück oder null, wenn es für diese Quelle noch keine gibt.
     */
    fun onLoaded(mode: Int, loaded: List<Preset>?): List<Preset>? {
        if (mode != this.mode) {
            if (this.mode != null) previous = candidate
            this.mode = mode
            candidate = null
            attempts = 0
            done = false
        }
        attempts++
        // noch die Favoriten der vorigen Quelle: das Radio hat noch nicht umgeschaltet
        val result = loaded?.takeUnless { candidate == null && it == previous && it.any { p -> !p.empty } }
        if (result != null && result == candidate) done = true
        if (result != null) candidate = result
        if (attempts >= maxAttempts) {
            done = true // z. B. Quellen ohne Favoriten: nicht endlos fragen
            // teilen sich die Quellen wirklich dieselben Favoriten, sie am Ende doch zeigen
            if (candidate == null) candidate = loaded
        }
        return candidate
    }

    /** Nach einer Änderung an den Favoriten neu laden (die Liste im Radio bleibt, wo sie ist). */
    fun invalidate() {
        attempts = 0
        done = false
    }

    /** Neues Radio: alles vergessen. */
    fun reset() {
        mode = null
        candidate = null
        previous = null
        attempts = 0
        done = false
    }
}
