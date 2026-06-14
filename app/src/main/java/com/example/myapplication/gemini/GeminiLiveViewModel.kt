// Orchestrates a Gemini Live session: WebSocket client + mic capture + speaker playback,
// plus throttled camera-frame forwarding. Audio I/O is on the phone (first version).

package com.example.myapplication.gemini

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.BuildConfig
import com.example.myapplication.journal.JournalRepo
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GeminiStatus {
  IDLE,
  CONNECTING,
  LIVE,
  ERROR,
}

data class GeminiUiState(
    val status: GeminiStatus = GeminiStatus.IDLE,
    val userText: String = "",
    val assistantText: String = "",
    val error: String? = null,
    val speaking: Boolean = false,
)

class GeminiLiveViewModel(app: Application) : AndroidViewModel(app), GeminiLiveClient.Listener {

  private val _uiState = MutableStateFlow(GeminiUiState())
  val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

  private var client: GeminiLiveClient? = null
  private val mic = MicRecorder { chunk -> client?.sendAudio(chunk) }
  private val speaker = SpeakerPlayer()
  private var relay: RelayPublisher? = null
  private var relayJob: Job? = null

  @Volatile private var lastFrameMs = 0L
  @Volatile private var sendingFrame = false
  @Volatile private var turnFresh = true

  private val isActive: Boolean
    get() = _uiState.value.status == GeminiStatus.LIVE || _uiState.value.status == GeminiStatus.CONNECTING

  fun start() {
    if (isActive) return
    val key = BuildConfig.GEMINI_API_KEY
    if (key.isBlank()) {
      _uiState.update {
        it.copy(
            status = GeminiStatus.ERROR,
            error = "Gemini API key not set. Add gemini_api_key to local.properties and rebuild.")
      }
      return
    }
    _uiState.update {
      it.copy(status = GeminiStatus.CONNECTING, error = null, userText = "", assistantText = "")
    }
    speaker.start()
    // Publish speaking/text to the glasses-avatar relay (mirrors the assistant on the glasses screen).
    relay = RelayPublisher(BuildConfig.RELAY_WS_URL).also { it.connect() }
    relayJob =
        viewModelScope.launch {
          _uiState
              .map { it.speaking to it.assistantText }
              .distinctUntilChanged()
              .collect { (sp, txt) -> relay?.publish(sp, txt) }
        }
    client = GeminiLiveClient(apiKey = key, listener = this).also { it.connect() }
  }

  fun stop() {
    relayJob?.cancel()
    relayJob = null
    relay?.close()
    relay = null
    mic.stop()
    speaker.stop()
    client?.close()
    client = null
    _uiState.update { it.copy(status = GeminiStatus.IDLE) }
  }

  /** Offer the latest camera frame; throttled to ~1 fps and JPEG-encoded off the main thread. */
  fun offerFrame(bitmap: Bitmap) {
    if (_uiState.value.status != GeminiStatus.LIVE) return
    val now = SystemClock.elapsedRealtime()
    if (sendingFrame || now - lastFrameMs < FRAME_INTERVAL_MS) return
    if (bitmap.isRecycled) return
    sendingFrame = true
    lastFrameMs = now
    val safe =
        try {
          bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
          sendingFrame = false
          return
        }
    viewModelScope.launch(Dispatchers.Default) {
      try {
        val jpeg =
            ByteArrayOutputStream().use { bos ->
              safe.compress(Bitmap.CompressFormat.JPEG, 70, bos)
              bos.toByteArray()
            }
        client?.sendVideoJpeg(jpeg)
      } catch (_: Exception) {} finally {
        safe.recycle()
        sendingFrame = false
      }
    }
  }

  // --- GeminiLiveClient.Listener (callbacks arrive on the WebSocket thread) ---

  override fun onReady() {
    _uiState.update { it.copy(status = GeminiStatus.LIVE) }
    mic.start()
  }

  override fun onAudio(pcm24k: ByteArray) {
    if (!_uiState.value.speaking) _uiState.update { it.copy(speaking = true) }
    speaker.enqueue(pcm24k)
  }

  override fun onInputTranscript(text: String) {
    if (turnFresh) {
      turnFresh = false
      _uiState.update { it.copy(userText = text, assistantText = "") }
    } else {
      _uiState.update { it.copy(userText = it.userText + text) }
    }
  }

  override fun onOutputTranscript(text: String) {
    _uiState.update { it.copy(assistantText = it.assistantText + text) }
  }

  override fun onTurnComplete() {
    turnFresh = true
    _uiState.update { it.copy(speaking = false) }
    val state = _uiState.value
    val u = state.userText.trim()
    val a = state.assistantText.trim()
    if (u.isNotEmpty() || a.isNotEmpty()) {
      val line = buildString {
        if (u.isNotEmpty()) append("🗣️ ").append(u)
        if (a.isNotEmpty()) {
          if (isNotEmpty()) append("　")
          append("🤖 ").append(a)
        }
      }
      JournalRepo.addRecord(line)
    }
  }

  override fun onInterrupted() {
    _uiState.update { it.copy(speaking = false) }
    speaker.flush()
  }

  override fun onClosed(reason: String) {
    mic.stop()
    _uiState.update { if (it.status == GeminiStatus.ERROR) it else it.copy(status = GeminiStatus.IDLE) }
  }

  override fun onError(message: String) {
    mic.stop()
    speaker.stop()
    _uiState.update { it.copy(status = GeminiStatus.ERROR, error = message) }
  }

  override fun onCleared() {
    super.onCleared()
    stop()
  }

  private companion object {
    const val FRAME_INTERVAL_MS = 1000L
  }
}
