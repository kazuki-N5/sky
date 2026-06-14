// Orchestrates Gemini Live audio/video plus the geometry tutor state machine.

package com.example.myapplication.gemini

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.BuildConfig
import com.example.myapplication.journal.JournalRepo
import com.example.myapplication.tutor.DiagramSpecValidator
import com.example.myapplication.tutor.GeometryTutorClient
import com.example.myapplication.tutor.SceneStabilityDetector
import com.example.myapplication.tutor.TutorAnalysis
import com.example.myapplication.tutor.TutorPhase
import com.example.myapplication.tutor.TutorUiState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    val tutor: TutorUiState = TutorUiState(),
)

class GeminiLiveViewModel(app: Application) : AndroidViewModel(app), GeminiLiveClient.Listener {

  private val _uiState = MutableStateFlow(GeminiUiState())
  val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

  private var client: GeminiLiveClient? = null
  private val mic = MicRecorder { chunk -> client?.sendAudio(chunk) }
  private val speaker = SpeakerPlayer()
  private var relay: RelayPublisher? = null
  private var relayJob: Job? = null

  private val stabilityDetector = SceneStabilityDetector()
  private val tutorClient = GeometryTutorClient()
  private var classificationJob: Job? = null
  private var analysisJob: Job? = null
  private var analysisCaptureProvider: (suspend () -> Bitmap?)? = null
  private var tutorAnalysis: TutorAnalysis? = null

  @Volatile private var lastFrameMs = 0L
  @Volatile private var sendingFrame = false
  @Volatile private var turnFresh = true
  @Volatile private var skipCurrentTurnJournal = false
  @Volatile private var latestTutorJpeg: ByteArray? = null
  @Volatile private var latestSceneFingerprint: String? = null
  @Volatile private var lastClassifiedScene: String? = null
  @Volatile private var cooldownUntilMs = 0L

  private val isActive: Boolean
    get() =
        _uiState.value.status == GeminiStatus.LIVE ||
            _uiState.value.status == GeminiStatus.CONNECTING

  fun setAnalysisCaptureProvider(provider: (suspend () -> Bitmap?)?) {
    analysisCaptureProvider = provider
  }

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
    resetTutor()
    _uiState.update {
      it.copy(status = GeminiStatus.CONNECTING, error = null, userText = "", assistantText = "")
    }
    speaker.start()
    relay = RelayPublisher(BuildConfig.RELAY_WS_URL).also { it.connect() }
    relayJob =
        viewModelScope.launch {
          _uiState
              .map { it.speaking to it.assistantText }
              .distinctUntilChanged()
              .collect { (speaking, text) -> relay?.publishAssistant(speaking, text) }
        }
    client = GeminiLiveClient(apiKey = key, listener = this).also { it.connect() }
  }

  fun stop() {
    classificationJob?.cancel()
    analysisJob?.cancel()
    classificationJob = null
    analysisJob = null
    relayJob?.cancel()
    relayJob = null
    relay?.clearTutor()
    relay?.close()
    relay = null
    mic.stop()
    speaker.stop()
    client?.close()
    client = null
    resetTutor()
    _uiState.update { it.copy(status = GeminiStatus.IDLE, speaking = false) }
  }

  /** Called around once per second by the camera overlay. */
  fun offerFrame(bitmap: Bitmap) {
    if (_uiState.value.status != GeminiStatus.LIVE) return
    val now = SystemClock.elapsedRealtime()
    if (sendingFrame || now - lastFrameMs < FRAME_INTERVAL_MS || bitmap.isRecycled) return
    sendingFrame = true
    lastFrameMs = now
    val safe =
        runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: run {
              sendingFrame = false
              return
            }

    viewModelScope.launch(Dispatchers.Default) {
      try {
        val observation = stabilityDetector.observe(safe, now)
        latestSceneFingerprint = observation.fingerprint
        val jpeg = encodeJpeg(safe, LIVE_FRAME_MAX_EDGE, LIVE_JPEG_QUALITY)
        latestTutorJpeg = jpeg
        client?.sendVideoJpeg(jpeg)
        updateWatchingState(observation.stableForMs, observation.changed, now)

        val phase = _uiState.value.tutor.phase
        val canClassify =
            now >= cooldownUntilMs &&
                observation.stableForMs >= STABLE_SCENE_MS &&
                phase in setOf(TutorPhase.IDLE, TutorPhase.WATCHING) &&
                observation.fingerprint != lastClassifiedScene &&
                classificationJob?.isActive != true
        if (canClassify) startClassification(jpeg, observation.fingerprint)
      } finally {
        safe.recycle()
        sendingFrame = false
      }
    }
  }

  private fun updateWatchingState(stableForMs: Long, changed: Boolean, now: Long) {
    val current = _uiState.value.tutor
    if (changed) {
      lastClassifiedScene = null
      if (current.phase == TutorPhase.CLASSIFYING) {
        classificationJob?.cancel()
        classificationJob = null
      }
      if (current.phase == TutorPhase.ANALYZING) {
        analysisJob?.cancel()
        analysisJob = null
      }
      if (
          current.phase in
              setOf(
                  TutorPhase.OFFERING,
                  TutorPhase.ANALYZING,
                  TutorPhase.HINT_VISIBLE,
              )
      ) {
        tutorAnalysis = null
        relay?.clearTutor()
        _uiState.update { it.copy(tutor = TutorUiState(phase = TutorPhase.WATCHING)) }
      }
    }

    val phase = _uiState.value.tutor.phase
    if (
        now >= cooldownUntilMs &&
            phase in setOf(TutorPhase.IDLE, TutorPhase.WATCHING, TutorPhase.COOLDOWN, TutorPhase.ERROR)
    ) {
      _uiState.update {
        it.copy(
            tutor =
                TutorUiState(
                    phase = TutorPhase.WATCHING,
                    stableSeconds = (stableForMs / 1000L).toInt().coerceAtMost(15),
                    message = "図形問題を確認中",
                ))
      }
    }
  }

  private fun startClassification(jpeg: ByteArray, sceneFingerprint: String) {
    lastClassifiedScene = sceneFingerprint
    _uiState.update {
      it.copy(
          tutor =
              it.tutor.copy(
                  phase = TutorPhase.CLASSIFYING,
                  stableSeconds = 15,
                  message = "問題を判定中",
                  error = null,
              ))
    }
    classificationJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val classification = tutorClient.classify(jpeg)
            if (
                latestSceneFingerprint != sceneFingerprint ||
                    _uiState.value.tutor.phase != TutorPhase.CLASSIFYING
            ) {
              return@launch
            }
            val supported =
                classification.isGeometryProblem &&
                    classification.completeProblemVisible &&
                    classification.svgSupported &&
                    !classification.userIsWriting &&
                    classification.confidence >= CLASSIFICATION_CONFIDENCE
            if (supported) {
              _uiState.update {
                it.copy(
                    tutor =
                        TutorUiState(
                            phase = TutorPhase.OFFERING,
                            stableSeconds = 15,
                            message = "ヒントを提案中",
                            problemFingerprint = classification.problemFingerprint,
                        ))
              }
              relay?.publishTutorStatus("難しい？ヒントを出そうか？")
              sendControlText("MATH_OFFER_READY")
            } else {
              _uiState.update {
                it.copy(
                    tutor =
                        TutorUiState(
                            phase = TutorPhase.WATCHING,
                            stableSeconds = 15,
                            message = null,
                        ))
              }
            }
          } catch (_: CancellationException) {
          } catch (error: Exception) {
            _uiState.update {
              it.copy(
                  tutor =
                      TutorUiState(
                          phase = TutorPhase.WATCHING,
                          message = null,
                          error = error.message,
                      ))
            }
          }
        }
  }

  private fun startTutorAnalysis() {
    if (analysisJob?.isActive == true) return
    _uiState.update {
      it.copy(
          tutor =
              it.tutor.copy(
                  phase = TutorPhase.ANALYZING,
                  message = "問題を読み取っています",
                  error = null,
              ))
    }
    relay?.publishTutorStatus("問題を読み取っています…")
    sendControlText("TUTOR_SPEAK:問題を確認しているよ。少し待ってね。")

    analysisJob =
        viewModelScope.launch {
          try {
            val captured = analysisCaptureProvider?.invoke()
            val jpeg =
                if (captured != null) {
                  try {
                    withContext(Dispatchers.Default) {
                      encodeJpeg(captured, ANALYSIS_FRAME_MAX_EDGE, ANALYSIS_JPEG_QUALITY)
                    }
                  } finally {
                    if (!captured.isRecycled) captured.recycle()
                  }
                } else {
                  latestTutorJpeg
                } ?: throw RuntimeException("解析できるカメラ画像がありません")

            val result =
                withContext(Dispatchers.IO) {
                  val classification = tutorClient.classify(jpeg)
                  val supported =
                      classification.isGeometryProblem &&
                          classification.completeProblemVisible &&
                          classification.svgSupported &&
                          !classification.userIsWriting &&
                          classification.confidence >= CLASSIFICATION_CONFIDENCE
                  if (!supported) {
                    throw RuntimeException("対応できる2次元の図形問題を確認できません")
                  }
                  val candidate = tutorClient.analyze(jpeg)
                  val localValidation = DiagramSpecValidator.validateAnalysis(candidate)
                  if (!localValidation.valid) {
                    throw RuntimeException(
                        "ヒント図を検証できません: ${localValidation.errors.take(3).joinToString()}")
                  }
                  val verification = tutorClient.verify(jpeg, candidate)
                  if (!verification.valid || verification.confidence < VERIFICATION_CONFIDENCE) {
                    throw RuntimeException(
                        verification.reason.ifBlank { "問題とヒント図が一致しません" })
                  }
                  candidate
                }

            tutorAnalysis = result
            showHint(0)
          } catch (_: CancellationException) {
          } catch (error: Exception) {
            failTutor(error.message ?: "問題を解析できませんでした")
          }
        }
  }

  private fun showHint(index: Int) {
    val analysis = tutorAnalysis ?: return
    val safeIndex = index.coerceIn(0, analysis.hints.lastIndex)
    val hint = analysis.hints[safeIndex]
    _uiState.update {
      it.copy(
          tutor =
              TutorUiState(
                  phase = TutorPhase.HINT_VISIBLE,
                  message = hint.caption,
                  problemFingerprint = analysis.problemFingerprint,
                  currentHintIndex = safeIndex,
                  hintCount = analysis.hints.size,
              ))
    }
    relay?.publishTutorHint(
        fingerprint = analysis.problemFingerprint,
        index = safeIndex,
        total = analysis.hints.size,
        hint = hint,
    )
    sendControlText("MATH_MODE_ACTIVE\nTUTOR_SPEAK:${hint.speech}")
  }

  private fun showNextHint(): Boolean {
    val analysis = tutorAnalysis ?: return false
    val current = _uiState.value.tutor.currentHintIndex
    if (current >= analysis.hints.lastIndex) {
      sendControlText("MATH_MODE_ACTIVE\nTUTOR_SPEAK:これが最後のヒントだよ。どこまで分かった？")
      return false
    }
    showHint(current + 1)
    return true
  }

  private fun showAnswer(): Boolean {
    val analysis = tutorAnalysis ?: return false
    relay?.publishTutorAnswer(analysis.answer)
    sendControlText("MATH_MODE_ACTIVE\nTUTOR_SPEAK:答えは${analysis.answer}です。必要なら考え方も確認しよう。")
    return true
  }

  private fun failTutor(message: String) {
    cooldownUntilMs = SystemClock.elapsedRealtime() + ERROR_COOLDOWN_MS
    _uiState.update {
      it.copy(
          tutor =
              TutorUiState(
                  phase = TutorPhase.ERROR,
                  message = "図を読み取れませんでした",
                  error = message,
              ))
    }
    relay?.publishTutorStatus("図を正確に読み取れませんでした")
    sendControlText("TUTOR_SPEAK:図を正確に読み取れなかったよ。もう一度、紙を正面に見せてね。")
  }

  private fun enterCooldown(message: String) {
    analysisJob?.cancel()
    tutorAnalysis = null
    cooldownUntilMs = SystemClock.elapsedRealtime() + DECLINE_COOLDOWN_MS
    stabilityDetector.reset()
    relay?.clearTutor()
    _uiState.update {
      it.copy(tutor = TutorUiState(phase = TutorPhase.COOLDOWN, message = message))
    }
  }

  private fun sendControlText(text: String) {
    skipCurrentTurnJournal = true
    client?.sendText(text)
  }

  private fun resetTutor() {
    classificationJob?.cancel()
    analysisJob?.cancel()
    classificationJob = null
    analysisJob = null
    tutorAnalysis = null
    latestTutorJpeg = null
    latestSceneFingerprint = null
    lastClassifiedScene = null
    cooldownUntilMs = 0L
    stabilityDetector.reset()
    _uiState.update { it.copy(tutor = TutorUiState()) }
  }

  private fun encodeJpeg(bitmap: Bitmap, maxEdge: Int, quality: Int): ByteArray {
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled =
        if (longest > maxEdge) {
          val ratio = maxEdge.toDouble() / longest
          Bitmap.createScaledBitmap(
              bitmap,
              (bitmap.width * ratio).toInt().coerceAtLeast(1),
              (bitmap.height * ratio).toInt().coerceAtLeast(1),
              true,
          )
        } else {
          bitmap
        }
    return try {
      ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
        output.toByteArray()
      }
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }
  }

  // GeminiLiveClient.Listener callbacks arrive on the WebSocket thread.

  override fun onReady() {
    _uiState.update {
      it.copy(status = GeminiStatus.LIVE, tutor = TutorUiState(phase = TutorPhase.WATCHING))
    }
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
    val shouldJournal =
        !skipCurrentTurnJournal &&
            state.tutor.phase in
                setOf(
                    TutorPhase.IDLE,
                    TutorPhase.WATCHING,
                    TutorPhase.CLASSIFYING,
                    TutorPhase.COOLDOWN,
                )
    skipCurrentTurnJournal = false
    if (!shouldJournal) return

    val user = state.userText.trim()
    val assistant = state.assistantText.trim()
    if (user.isNotEmpty() || assistant.isNotEmpty()) {
      val line = buildString {
        if (user.isNotEmpty()) append("🗣️ ").append(user)
        if (assistant.isNotEmpty()) {
          if (isNotEmpty()) append("　")
          append("🤖 ").append(assistant)
        }
      }
      JournalRepo.addRecord(line)
    }
  }

  override fun onInterrupted() {
    _uiState.update { it.copy(speaking = false) }
    speaker.flush()
  }

  override fun onToolCall(id: String, name: String, args: JSONObject) {
    skipCurrentTurnJournal = true
    when (name) {
      "request_math_help",
      "accept_math_offer" -> {
        client?.sendToolResponse(id, name, JSONObject().put("result", "accepted"))
        viewModelScope.launch { startTutorAnalysis() }
      }
      "decline_math_offer" -> {
        client?.sendToolResponse(id, name, JSONObject().put("result", "declined"))
        viewModelScope.launch { enterCooldown("今回はヒントを表示しません") }
      }
      "next_math_hint" -> {
        val analysis = tutorAnalysis
        val current = _uiState.value.tutor.currentHintIndex
        val canAdvance = analysis != null && current < analysis.hints.lastIndex
        client?.sendToolResponse(
            id,
            name,
            JSONObject().put("result", if (canAdvance) "shown" else "last_hint"),
        )
        showNextHint()
      }
      "show_math_answer" -> {
        val canShow = tutorAnalysis != null
        client?.sendToolResponse(
            id,
            name,
            JSONObject().put("result", if (canShow) "shown" else "not_ready"),
        )
        if (canShow) showAnswer()
      }
      "close_math_tutor" -> {
        client?.sendToolResponse(id, name, JSONObject().put("result", "closed"))
        viewModelScope.launch { enterCooldown("算数チューターを終了しました") }
      }
      else -> {
        client?.sendToolResponse(id, name, JSONObject().put("result", "unsupported"))
      }
    }
  }

  override fun onToolCallCancelled(ids: List<String>) {
    // Tool responses are immediate. Long-running analysis has no external side effect to undo.
  }

  override fun onClosed(reason: String) {
    mic.stop()
    _uiState.update {
      if (it.status == GeminiStatus.ERROR) it else it.copy(status = GeminiStatus.IDLE)
    }
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
    const val STABLE_SCENE_MS = 15_000L
    const val DECLINE_COOLDOWN_MS = 180_000L
    const val ERROR_COOLDOWN_MS = 30_000L
    const val CLASSIFICATION_CONFIDENCE = 0.85
    const val VERIFICATION_CONFIDENCE = 0.80
    const val LIVE_FRAME_MAX_EDGE = 768
    const val ANALYSIS_FRAME_MAX_EDGE = 1600
    const val LIVE_JPEG_QUALITY = 70
    const val ANALYSIS_JPEG_QUALITY = 88
  }
}
