package de.radiopult.app.fsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class ParseResponsesTest {

    private fun parse(xml: String) = parseResponses(KXmlParser().apply { setInput(StringReader(xml)) })

    @Test
    fun readsSingleValue() {
        val r = parse("<fsapiResponse><status>FS_OK</status><value><u8>12</u8></value></fsapiResponse>").single()
        assertEquals("12", r.text())
        assertNull(r.node)
    }

    @Test
    fun readsGetMultipleWithNodeNames() {
        val r = parse(
            """
            <fsapiGetMultipleResponse>
              <fsapiResponse><node>netRemote.sys.audio.volume</node><status>FS_OK</status><value><u8>7</u8></value></fsapiResponse>
              <fsapiResponse><node>netRemote.play.info.name</node><status>FS_OK</status><value><c8_array>Radio Bob</c8_array></value></fsapiResponse>
              <fsapiResponse><node>netRemote.play.shuffle</node><status>FS_NODE_DOES_NOT_EXIST</status></fsapiResponse>
            </fsapiGetMultipleResponse>
            """.trimIndent(),
        )
        assertEquals(listOf("netRemote.sys.audio.volume", "netRemote.play.info.name", "netRemote.play.shuffle"), r.map { it.node })
        assertEquals("7", r[0].text())
        assertEquals("Radio Bob", r[1].text())
        assertNull(r[2].text())
    }

    @Test
    fun lowercasesFieldNames() {
        val r = parse(
            """
            <fsapiResponse><status>FS_OK</status>
              <item key="3"><field name="name"><c8_array>Album</c8_array></field><field name="contextMenu"><u8>1</u8></field></item>
              <listend/>
            </fsapiResponse>
            """.trimIndent(),
        ).single()
        val item = r.items.single()
        assertEquals(3, item.key)
        assertEquals("1", item.fields["contextmenu"])
        assertTrue(r.listEnd)
    }
}
