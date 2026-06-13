// Minimal client for the Gemini Live API (BidiGenerateContent) over WebSocket.
// Audio in = 16kHz PCM16, audio out = 24kHz PCM16; camera frames sent as JPEG.
// Protocol refs: https://ai.google.dev/api/live , https://ai.google.dev/gemini-api/docs/live

package com.example.myapplication.gemini

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class GeminiLiveClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,
    private val listener: Listener,
) {
  interface Listener {
    fun onReady()
    fun onAudio(pcm24k: ByteArray)
    fun onInputTranscript(text: String)
    fun onOutputTranscript(text: String)
    fun onTurnComplete()
    fun onInterrupted()
    fun onClosed(reason: String)
    fun onError(message: String)
  }

  companion object {
    private const val TAG = "GeminiLive"
    // Live API model — verify/update as Google releases new ones.
    const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
    private const val WS_BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage."
    private const val DEFAULT_SYSTEM_INSTRUCTION =
        "あなたはスマートグラスのカメラ映像越しにユーザーの周囲が見えている、親切で簡潔な音声アシスタントです。" +
            "ユーザーは日本語で話します。入力も出力も必ず日本語として扱い、常に日本語で短く自然な話し言葉で応答してください。"
  }

  private val client =
      OkHttpClient.Builder()
          .readTimeout(0, TimeUnit.MILLISECONDS) // keep the socket open
          .pingInterval(20, TimeUnit.SECONDS)
          .build()

  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var ready = false
  @Volatile private var userInitiatedClose = false

  fun connect() {
    // The provided key (incl. the AQ.-prefixed hackathon key) authenticates as a standard API key.
    val url = "${WS_BASE}v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
    Log.d(TAG, "connecting v1beta ?key token=${apiKey.take(5)}… model=$model")
    userInitiatedClose = false
    webSocket = client.newWebSocket(Request.Builder().url(url).build(), socketListener)
  }

  fun close() {
    userInitiatedClose = true
    ready = false
    webSocket?.close(1000, "client closing")
    webSocket = null
  }

  fun sendAudio(pcm16k: ByteArray) {
    if (!ready) return
    val blob =
        JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", Base64.encodeToString(pcm16k, Base64.NO_WRAP))
    webSocket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", blob)).toString())
  }

  fun sendVideoJpeg(jpeg: ByteArray) {
    if (!ready) return
    val blob =
        JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
    webSocket?.send(JSONObject().put("realtimeInput", JSONObject().put("video", blob)).toString())
  }

  private fun sendSetup() {
    val setup =
        JSONObject().apply {
          put("model", "models/$model")
          put(
              "generationConfig",
              JSONObject()
                  .put("responseModalities", JSONArray().put("AUDIO"))
                  .put("speechConfig", JSONObject().put("languageCode", "ja-JP")),
          )
          put(
              "systemInstruction",
              JSONObject()
                  .put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
          )
          put("inputAudioTranscription", JSONObject())
          put("outputAudioTranscription", JSONObject())
        }
    webSocket?.send(JSONObject().put("setup", setup).toString())
  }

  private val socketListener =
      object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
          Log.d(TAG, "WebSocket open; sending setup")
          sendSetup()
        }

        override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(ws: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
          Log.w(TAG, "WebSocket closing: code=$code reason=$reason")
          ready = false
          ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
          Log.w(TAG, "WebSocket closed: code=$code reason=$reason")
          ready = false
          if (userInitiatedClose) {
            listener.onClosed(reason.ifBlank { "closed ($code)" })
          } else {
            listener.onError(
                "Server closed (code $code)" +
                    if (reason.isNotBlank()) ": $reason"
                    else " — likely invalid model name or token. Check Logcat tag GeminiLive.")
          }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
          ready = false
          Log.e(TAG, "WebSocket failure", t)
          val detail = response?.let { " (HTTP ${it.code})" } ?: ""
          listener.onError((t.message ?: "WebSocket failure") + detail)
        }
      }

  private fun handleMessage(text: String) {
    Log.d(TAG, "recv: ${if (text.length > 400) text.substring(0, 400) + "…" else text}")
    val json =
        try {
          JSONObject(text)
        } catch (e: Exception) {
          Log.w(TAG, "Non-JSON message: $text")
          return
        }

    if (json.has("setupComplete")) {
      ready = true
      listener.onReady()
      return
    }

    val serverContent =
        json.optJSONObject("serverContent")
            ?: run {
              json.optJSONObject("error")?.let { listener.onError(it.optString("message", "server error")) }
              return
            }

    if (serverContent.optBoolean("interrupted", false)) listener.onInterrupted()

    serverContent.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
        ?.let { listener.onInputTranscript(it) }
    serverContent.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
        ?.let { listener.onOutputTranscript(it) }

    serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
      for (i in 0 until parts.length()) {
        val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
        if (inline.optString("mimeType").startsWith("audio/pcm")) {
          val data = inline.optString("data")
          if (data.isNotEmpty()) listener.onAudio(Base64.decode(data, Base64.NO_WRAP))
        }
      }
    }

    if (serverContent.optBoolean("turnComplete", false)) listener.onTurnComplete()
  }
}
