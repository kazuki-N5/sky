// Publishes {speaking, text} to the WebSocket relay so the glasses screen mirrors the assistant.

package com.example.myapplication.gemini

import android.util.Log
import java.util.concurrent.TimeUnit
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

  @Volatile private var ws: WebSocket? = null
  @Volatile private var open = false
  @Volatile private var last: String? = null

  fun connect() {
    if (url.isBlank()) {
      Log.d(TAG, "relay_ws_url not set — relay publishing disabled")
      return
    }
    ws =
        client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                Log.d(TAG, "relay connected: $url")
                last?.let { webSocket.send(it) } // flush latest state on connect
              }

              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                Log.w(TAG, "relay ws failure: ${t.message}")
              }
            },
        )
  }

  fun publish(speaking: Boolean, text: String) {
    val payload = JSONObject().put("speaking", speaking).put("text", text).toString()
    last = payload
    if (open) ws?.send(payload)
  }

  fun close() {
    open = false
    ws?.close(1000, "bye")
    ws = null
  }
}
