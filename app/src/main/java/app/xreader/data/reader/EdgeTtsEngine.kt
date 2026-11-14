package app.xreader.data.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Pure helpers for the Microsoft Edge "read aloud" protocol, kept free
 * of Android types so they can be unit-tested on the JVM.
 */
object EdgeTtsProtocol {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    const val WS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    /** Sec-MS-GEC token: SHA-256 of Windows-file-time ticks (rounded down
     * to 5 minutes) followed by the trusted client token, upper-case hex. */
    fun secMsGec(nowMillis: Long): String {
        var seconds = nowMillis / 1000 + WIN_EPOCH_SECONDS
        seconds -= seconds % 300
        val ticks = seconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun escapeXml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                // XML 1.0 forbids most control characters.
                ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** App speech rate (1.0 = normal) -> SSML prosody rate like "+25%". */
    fun rateToProsody(rate: Float): String {
        val pct = ((rate - 1f) * 100f).toInt().coerceIn(-50, 100)
        return (if (pct >= 0) "+" else "") + pct + "%"
    }

    /** Picks a neural voice from the script of the text itself, so a
     * Chinese, Arabic or English chapter each get a matching voice. */
    fun voiceFor(text: String, deviceLocale: Locale): Pair<String, String> {
        var cjk = 0
        var arabic = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> cjk++
                c in 0x0600..0x06FF -> arabic++
                ch.isLetter() -> latin++
            }
        }
        return when {
            cjk > 0 && cjk >= arabic && cjk * 3 >= latin -> "zh-CN" to "zh-CN-XiaoxiaoNeural"
            arabic > 0 && arabic * 3 >= latin -> "ar-SA" to "ar-SA-ZariyahNeural"
            else -> when (deviceLocale.language) {
                "fr" -> "fr-FR" to "fr-FR-DeniseNeural"
                "es" -> "es-ES" to "es-ES-ElviraNeural"
                "de" -> "de-DE" to "de-DE-KatjaNeural"
                else -> "en-US" to "en-US-AriaNeural"
            }
        }
    }

    fun buildSsml(text: String, lang: String, voice: String, rate: Float): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='${rateToProsody(rate)}' volume='+0%'>" +
            escapeXml(text) +
            "</prosody></voice></speak>"

    fun timestamp(nowMillis: Long): String {
        val fmt = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(nowMillis))
    }

    fun speechConfigMessage(nowMillis: Long): String =
        "X-Timestamp:${timestamp(nowMillis)}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

    fun ssmlMessage(requestId: String, ssml: String, nowMillis: Long): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp(nowMillis)}Z\r\n" +
            "Path:ssml\r\n\r\n" + ssml

    /** Audio payload of a binary frame: 2-byte big-endian header length,
     * the header text, then the bytes. Returns null for non-audio frames. */
    fun audioPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLen = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val start = 2 + headerLen
        if (start > frame.size) return null
        val header = String(frame, 2, headerLen, Charsets.UTF_8)
        if (!header.contains("Path:audio")) return null
        return frame.copyOfRange(start, frame.size)
    }
}

/**
 * Microsoft Edge online neural voices ("Read Aloud") over the same
 * WebSocket the Edge browser uses. One sentence per request: the audio
 * is collected into a cache file and played with [MediaPlayer].
 *
 * If the service can't be reached the sentence is handed to [fallback]
 * (the device engine) so read-aloud never goes silent, and Edge is
 * retried after a short cool-down.
 */
class EdgeTtsEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val fallbackFactory: () -> TtsEngine
) : TtsEngine {

    private val appContext = context.applicationContext
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var listener: TtsEngine.Listener? = null
    private var rate = 1f
    private var generation = 0
    private var socket: WebSocket? = null
    private var player: MediaPlayer? = null
    private var edgeDownUntil = 0L
    private var fallback: TtsEngine? = null
    private var fallbackActive = false

    // Edge needs no warm-up, so the engine is always ready.
    override val isReady: Boolean get() = true

    override fun setListener(listener: TtsEngine.Listener?) {
        this.listener = listener
        fallback?.setListener(listener)
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        fallback?.setRate(rate)
    }

    override fun speak(utteranceId: String, text: String) {
        stopPlayback()
        val sentence = text
        val gen = ++generation
        val now = System.currentTimeMillis()
        if (now < edgeDownUntil) {
            speakWithFallback(utteranceId, sentence)
            return
        }
        fallbackActive = false
        val (lang, voice) = EdgeTtsProtocol.voiceFor(text, Locale.getDefault())
        val ssml = EdgeTtsProtocol.buildSsml(text, lang, voice, rate)
        val audio = ByteArrayOutputStream()
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val url = EdgeTtsProtocol.WS_URL +
            "?TrustedClientToken=${EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=$requestId" +
            "&Sec-MS-GEC=${EdgeTtsProtocol.secMsGec(now)}" +
            "&Sec-MS-GEC-Version=1-${EdgeTtsProtocol.CHROMIUM_FULL_VERSION}"
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
            )
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase())
            .build()

        var finished = false
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(EdgeTtsProtocol.speechConfigMessage(System.currentTimeMillis()))
                webSocket.send(
                    EdgeTtsProtocol.ssmlMessage(requestId, ssml, System.currentTimeMillis())
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end") && !finished) {
                    finished = true
                    webSocket.close(1000, null)
                    val bytes = audio.toByteArray()
                    scope.launch {
                        if (gen != generation) return@launch
                        if (bytes.isEmpty()) failOver(utteranceId, sentence)
                        else play(gen, utteranceId, bytes)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                EdgeTtsProtocol.audioPayload(bytes.toByteArray())?.let { audio.write(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished) return
                finished = true
                scope.launch {
                    if (gen == generation) failOver(utteranceId, sentence)
                }
            }
        })
    }

    private fun failOver(utteranceId: String, sentence: String) {
        edgeDownUntil = System.currentTimeMillis() + EDGE_RETRY_COOLDOWN_MS
        speakWithFallback(utteranceId, sentence)
    }

    private fun speakWithFallback(utteranceId: String, text: String) {
        val engine = fallback ?: fallbackFactory().also {
            it.setListener(listener)
            it.setRate(rate)
            fallback = it
        }
        fallbackActive = true
        engine.speak(utteranceId, text)
    }

    private fun play(gen: Int, utteranceId: String, mp3: ByteArray) {
        val file = File(appContext.cacheDir, "edge-tts-$gen.mp3")
        try {
            file.writeBytes(mp3)
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                if (gen == generation) {
                    listener?.onStart(utteranceId)
                    it.start()
                }
            }
            mp.setOnCompletionListener {
                file.delete()
                if (gen == generation) listener?.onDone(utteranceId)
            }
            mp.setOnErrorListener { _, _, _ ->
                file.delete()
                if (gen == generation) listener?.onError(utteranceId)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            file.delete()
            listener?.onError(utteranceId)
        }
    }

    private fun stopPlayback() {
        socket?.cancel()
        socket = null
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
    }

    override fun stop() {
        generation++
        stopPlayback()
        if (fallbackActive) fallback?.stop()
    }

    override fun shutdown() {
        stop()
        fallback?.shutdown()
        fallback = null
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val EDGE_RETRY_COOLDOWN_MS = 60_000L
    }
}
