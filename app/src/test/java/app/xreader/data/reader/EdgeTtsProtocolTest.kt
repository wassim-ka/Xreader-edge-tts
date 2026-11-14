package app.xreader.data.reader

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsProtocolTest {

    @Test
    fun `secMsGec is stable inside a five minute window and upper-case sha256`() {
        val a = EdgeTtsProtocol.secMsGec(1_700_000_000_000L)
        val b = EdgeTtsProtocol.secMsGec(1_700_000_000_000L + 60_000L)
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun `xml special characters are escaped`() {
        assertEquals(
            "a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;",
            EdgeTtsProtocol.escapeXml("a & b <c> \"d\" 'e'")
        )
    }

    @Test
    fun `rate maps to a clamped prosody percentage`() {
        assertEquals("+0%", EdgeTtsProtocol.rateToProsody(1f))
        assertEquals("+50%", EdgeTtsProtocol.rateToProsody(1.5f))
        assertEquals("-25%", EdgeTtsProtocol.rateToProsody(0.75f))
        assertEquals("+100%", EdgeTtsProtocol.rateToProsody(5f))
        assertEquals("-50%", EdgeTtsProtocol.rateToProsody(0.1f))
    }

    @Test
    fun `voice follows the script of the text`() {
        assertEquals("zh-CN-XiaoxiaoNeural", EdgeTtsProtocol.voiceFor("他走进了房间。", Locale.US).second)
        assertEquals("ar-SA-ZariyahNeural", EdgeTtsProtocol.voiceFor("مرحبا بكم في الرواية", Locale.US).second)
        assertEquals("en-US-AriaNeural", EdgeTtsProtocol.voiceFor("He walked in.", Locale.US).second)
        assertEquals("fr-FR-DeniseNeural", EdgeTtsProtocol.voiceFor("He walked in.", Locale.FRANCE).second)
    }

    @Test
    fun `audio payload is read after the binary header and other frames are ignored`() {
        val header = "X-RequestId:1\r\nPath:audio\r\n".toByteArray()
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) +
            header + byteArrayOf(1, 2, 3)
        assertEquals(listOf<Byte>(1, 2, 3), EdgeTtsProtocol.audioPayload(frame)!!.toList())

        val other = "Path:turn.start".toByteArray()
        val otherFrame = byteArrayOf((other.size shr 8).toByte(), other.size.toByte()) + other
        assertNull(EdgeTtsProtocol.audioPayload(otherFrame))
        assertNotNull(EdgeTtsProtocol.buildSsml("Hi", "en-US", "en-US-AriaNeural", 1f))
    }
}
