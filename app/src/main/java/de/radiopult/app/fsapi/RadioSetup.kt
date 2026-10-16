package de.radiopult.app.fsapi

import de.radiopult.app.R
import kotlinx.coroutines.delay
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** Ein WLAN, das das Radio sieht. [key] ist der Eintrag in der Suchliste, null bei einem versteckten Netz. */
data class WifiNetwork(val key: Int?, val ssid: String, val secured: Boolean, val signal: Int = 0)

/**
 * Ersteinrichtung eines Radios, das im Setup-Modus ist (eigenes WLAN „Setup …“).
 * Ablauf wie bei UNDOK: WLANs suchen lassen, Netz wählen, Passwort verschlüsselt
 * übertragen, Änderungen übernehmen – danach verlässt das Radio den Setup-Modus.
 */
class RadioSetup(private val client: FsApiClient) {

    suspend fun scanNetworks(): List<WifiNetwork> {
        client.set(Nodes.WLAN_SCAN, "1")
        // Die Suche dauert ein paar Sekunden; danach steht der Knoten wieder auf 0 (IDLE)
        for (i in 0 until 15) {
            delay(1000)
            val state = try {
                client.get(Nodes.WLAN_SCAN)?.trim()
            } catch (e: FsApiException) {
                throw e
            } catch (ignored: IOException) {
                null // manche Radios antworten während der Suche kurz nicht
            }
            if (state == "0") break
        }
        return client.list(Nodes.WLAN_SCAN_LIST)
            .mapNotNull { item ->
                val ssid = item.text("ssid")
                if (ssid.isBlank()) return@mapNotNull null
                WifiNetwork(
                    key = item.key,
                    ssid = ssid,
                    secured = item.fields["privacy"]?.trim() != "0",
                    signal = item.fields["rssi"]?.trim()?.toIntOrNull() ?: 0,
                )
            }
            .sortedByDescending { it.signal }
            .distinctBy { it.ssid }
    }

    suspend fun connectTo(network: WifiNetwork, passphrase: String) {
        if (network.key != null) {
            setOrFail(Nodes.WLAN_SELECT_AP, network.key.toString())
        } else {
            setOrFail(Nodes.WLAN_SET_SSID, network.ssid)
            // versteckte Netze: WPA2 mit AES ist heute praktisch immer richtig
            setOrFail(Nodes.WLAN_SET_AUTH_TYPE, if (network.secured) AUTH_WPA2 else AUTH_OPEN)
            setOrFail(Nodes.WLAN_SET_ENC_TYPE, if (network.secured) ENC_AES else ENC_NONE)
        }
        if (network.secured) setOrFail(Nodes.WLAN_SET_PASSPHRASE, encrypt(publicKey(), passphrase))
        try {
            setOrFail(Nodes.NET_COMMIT, "1")
        } catch (e: FsApiException) {
            throw e
        } catch (ignored: IOException) {
            // Das Radio wechselt sofort ins neue WLAN – die Antwort kommt dann oft nicht mehr an
        }
    }

    /** Das Radio erzeugt sein Schlüsselpaar nach dem Start; das kann etwas dauern. */
    private suspend fun publicKey(): String {
        for (i in 0 until 30) {
            if (client.get(Nodes.RSA_STATUS)?.trim() != "0") break
            delay(1000)
        }
        return client.get(Nodes.RSA_PUBLIC_KEY)?.trim().orEmpty()
    }

    private suspend fun setOrFail(node: String, value: String) {
        if (!client.set(node, value)) throw FsApiException(R.string.error_setting_rejected_node, node)
    }

    companion object {
        private const val AUTH_OPEN = "0"
        private const val AUTH_WPA2 = "3"
        private const val ENC_NONE = "0"
        private const val ENC_AES = "3"

        /**
         * Verschlüsselt das WLAN-Passwort mit dem öffentlichen Schlüssel des Radios, damit es
         * nicht im Klartext über das offene Setup-WLAN geht. Das Radio liefert den Schlüssel als
         * „Exponent:Modulus“ in Hex (z. B. „010001:CACD…“); verschlüsselt wird mit PKCS#1 v1.5,
         * das Ergebnis geht hex-kodiert zurück – wie bei der Weboberfläche des Radios.
         */
        fun encrypt(publicKey: String, text: String): String {
            val parts = publicKey.split(':').map { it.trim() }
            if (parts.size != 2 || parts.any { p -> p.isEmpty() || p.any { Character.digit(it, 16) < 0 } }) {
                throw FsApiException(R.string.error_invalid_key)
            }
            val (exponent, modulus) = parts.sortedBy { it.length } // der Exponent ist der kurze Teil
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(BigInteger(modulus, 16), BigInteger(exponent, 16)))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
