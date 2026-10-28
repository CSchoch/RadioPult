package de.radiopult.app.localmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RendererTest {

    private val description = """
        <root><device><serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
          <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVTransport/control</controlURL></service>
        </serviceList></device></root>
    """.trimIndent()

    @Test
    fun findsAvTransportRelativeToDescription() {
        assertEquals(
            "http://192.168.1.30:8080/AVTransport/control",
            Renderer.controlUrl("http://192.168.1.30:8080/dd.xml", description).toString(),
        )
    }

    @Test
    fun prefersUrlBase() {
        val withBase = description.replace("<device>", "<URLBase>http://192.168.1.30:9000/</URLBase><device>")
        assertEquals(
            "http://192.168.1.30:9000/AVTransport/control",
            Renderer.controlUrl("http://192.168.1.30:8080/dd.xml", withBase).toString(),
        )
    }

    @Test
    fun noAvTransport() {
        assertNull(Renderer.controlUrl("http://x/dd.xml", "<root><service><serviceType>other</serviceType></service></root>"))
    }
}
