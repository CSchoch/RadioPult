package de.radiopult.app.fsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

class RadioSetupTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    private val public = keyPair.public as RSAPublicKey

    private fun decrypt(hex: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        return String(cipher.doFinal(hexToBytes(hex)!!), Charsets.UTF_8)
    }

    @Test
    fun encryptsWithRadioKeyFormat() {
        // so liefert das Radio den Schlüssel: "Exponent:Modulus" in Hex
        val radioKey = "%06X:%X".format(public.publicExponent, public.modulus)
        val encrypted = RadioSetup.encrypt(radioKey, "geheim-Passwört 123")
        assertEquals(256, encrypted.length) // 1024 Bit = 128 Byte = 256 Hex-Zeichen
        assertEquals("geheim-Passwört 123", decrypt(encrypted))
    }

    @Test
    fun acceptsModulusFirst() {
        val key = "%x:%x".format(public.modulus, public.publicExponent)
        assertEquals("pw12345678", decrypt(RadioSetup.encrypt(key, "pw12345678")))
    }

    @Test
    fun rejectsInvalidKey() {
        assertThrows(FsApiException::class.java) { RadioSetup.encrypt("", "x") }
        assertThrows(FsApiException::class.java) { RadioSetup.encrypt("010001:XYZ", "x") }
    }

    @Test
    fun decodesHexSsid() {
        val item = FsItem(
            key = 0,
            fields = mapOf("ssid" to "4D65696E20574C414E00", "label" to "4D65696E"),
            types = mapOf("ssid" to "array", "label" to "c8_array"),
        )
        assertEquals("Mein WLAN", item.text("ssid"))
        assertEquals("4D65696E", item.text("label")) // Text bleibt Text
    }

    @Test
    fun keepsNonHexArrayAsIs() {
        val item = FsItem(0, mapOf("ssid" to "Heimnetz"), mapOf("ssid" to "array"))
        assertEquals("Heimnetz", item.text("ssid"))
    }
}
