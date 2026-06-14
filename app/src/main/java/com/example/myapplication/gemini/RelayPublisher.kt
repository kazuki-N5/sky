// Publishes {speaking, text} to the WebSocket relay so the glasses screen mirrors the assistant.

package com.example.myapplication.gemini

import android.util.Log
import com.example.myapplication.tutor.GeometryHint
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class RelayPublisher(private val url: String) {

  private companion object {
    const val TAG = "RelayPublisher"
  }

  private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  private val reconnectExecutor: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor()

  @Volatile private var ws: WebSocket? = null
  @Volatile private var open = false
  @Volatile private var connecting = false
  @Volatile private var userClosed = false
  @Volatile private var reconnectDelaySeconds = 1L
  @Volatile private var lastAssistant: String? = null
  @Volatile private var lastTutor: String? = null
  private val reconnectScheduled = AtomicBoolean(false)

  fun connect() {
    if (url.isBlank()) {
      Log.d(TAG, "relay_ws_url not set — relay publishing disabled")
      return
    }
    userClosed = false
    openSocket()
  }

  @Synchronized
  private fun openSocket() {
    if (userClosed || open || connecting) return
    connecting = true
    ws =
        client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting = false
                open = true
                reconnectDelaySeconds = 1L
                Log.d(TAG, "relay connected: $url")
                lastAssistant?.let { webSocket.send(it) }
                lastTutor?.let { webSocket.send(it) }
              }

              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connecting = false
                open = false
                scheduleReconnect()
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connecting = false
                open = false
                Log.w(TAG, "relay ws failure: ${t.message}")
                scheduleReconnect()
              }
            },
        )
  }

  private fun scheduleReconnect() {
    if (userClosed || reconnectExecutor.isShutdown) return
    if (!reconnectScheduled.compareAndSet(false, true)) return
    val delay = reconnectDelaySeconds
    reconnectDelaySeconds = (reconnectDelaySeconds * 2).coerceAtMost(30L)
    reconnectExecutor.schedule(
        {
          reconnectScheduled.set(false)
          openSocket()
        },
        delay,
        TimeUnit.SECONDS,
    )
  }

  fun publishAssistant(speaking: Boolean, text: String) {
    val message =
        JSONObject()
            .put("version", 2)
            .put("type", "assistant.state")
            // Keep legacy fields for older web clients.
            .put("speaking", speaking)
            .put("text", text)
            .put(
                "payload",
                JSONObject().put("speaking", speaking).put("text", text),
            )
            .toString()
    lastAssistant = message
    send(message)
  }

  fun publishTutorStatus(message: String) {
    rememberAndSendTutor(
        JSONObject()
            .put("version", 2)
            .put("type", "tutor.status")
            .put("payload", JSONObject().put("message", message))
            .toString())
  }

  fun publishTutorHint(
      fingerprint: String,
      index: Int,
      total: Int,
      hint: GeometryHint,
  ) {
    rememberAndSendTutor(
        JSONObject()
            .put("version", 2)
            .put("type", "tutor.hint")
            .put(
                "payload",
                JSONObject()
                    .put("problemFingerprint", fingerprint)
                    .put("step", index + 1)
                    .put("totalSteps", total)
                    .put("caption", hint.caption)
                    .put("speech", hint.speech)
                    .put("diagram", hint.diagram.toJson()),
            )
            .toString())
  }

  fun publishTutorAnswer(answer: String) {
    rememberAndSendTutor(
        JSONObject()
            .put("version", 2)
            .put("type", "tutor.answer")
            .put("payload", JSONObject().put("answer", answer))
            .toString())
  }

  fun clearTutor() {
    val message =
        JSONObject().put("version", 2).put("type", "tutor.clear").put("payload", JSONObject())
            .toString()
    lastTutor = message
    send(message)
  }

  private fun rememberAndSendTutor(message: String) {
    lastTutor = message
    send(message)
  }

  private fun send(message: String) {
    if (open) ws?.send(message)
  }

  fun close() {
    userClosed = true
    open = false
    connecting = false
    ws?.close(1000, "bye")
    ws = null
    reconnectExecutor.shutdownNow()
  }
}
