// Renders content on Meta Ray-Ban Display glasses (mwdat-display). Own session, separate from camera.
// Flow: createSession(display-capable) -> start -> STARTED -> addDisplay -> DisplayState.STARTED -> sendContent.

package com.example.myapplication.display

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DisplayStatus {
  IDLE,
  CONNECTING,
  PREPARING,
  READY,
  ERROR,
}

data class DisplayUi(
    val status: DisplayStatus = DisplayStatus.IDLE,
    val message: String? = null,
)

class DisplayViewModel(app: Application) : AndroidViewModel(app) {

  private companion object {
    const val TAG = "DisplayVM"
  }

  private val _ui = MutableStateFlow(DisplayUi())
  val ui: StateFlow<DisplayUi> = _ui.asStateFlow()

  private var session: DeviceSession? = null
  private var display: Display? = null
  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var displayStateJob: Job? = null

  private val isBusy: Boolean
    get() =
        _ui.value.status == DisplayStatus.CONNECTING ||
            _ui.value.status == DisplayStatus.PREPARING ||
            _ui.value.status == DisplayStatus.READY

  fun startAndShow() {
    if (isBusy) return
    if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
      _ui.value =
          DisplayUi(DisplayStatus.ERROR, "先に LIVE タブの「Connect my glasses」で登録してください")
      return
    }
    _ui.value = DisplayUi(DisplayStatus.CONNECTING, "ディスプレイ対応の端末に接続中…")

    Wearables.createSession(AutoDeviceSelector(filter = { it.isDisplayCapable() }))
        .onSuccess { created ->
          session = created
          sessionErrorJob =
              viewModelScope.launch {
                created.errors.collect { error ->
                  _ui.value = DisplayUi(DisplayStatus.ERROR, error.description)
                }
              }
          sessionStateJob =
              viewModelScope.launch {
                created.state.collect { state ->
                  if (state == DeviceSessionState.STARTED && display == null) {
                    attachDisplay(created)
                  }
                }
              }
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "createSession failed: ${error.description}")
          _ui.value =
              DisplayUi(DisplayStatus.ERROR, "接続失敗: ${error.description}（Display対応端末が必要）")
        }
  }

  private fun attachDisplay(activeSession: DeviceSession) {
    _ui.value = DisplayUi(DisplayStatus.PREPARING, "ディスプレイ準備中…")
    activeSession
        .addDisplay()
        .onSuccess { newDisplay ->
          display = newDisplay
          displayStateJob =
              viewModelScope.launch {
                newDisplay.state.collect { state ->
                  if (state == DisplayState.STARTED) {
                    _ui.value = DisplayUi(DisplayStatus.READY, "メガネに表示中")
                    sendHelloCard(newDisplay)
                  }
                }
              }
        }
        .onFailure { error, _ ->
          _ui.value = DisplayUi(DisplayStatus.ERROR, "ディスプレイ取得失敗: ${error.description}")
        }
  }

  private fun sendHelloCard(target: Display) {
    viewModelScope.launch {
      target
          .sendContent {
            flexBox(gap = 12, padding = 24, background = FlexBoxBackground.CARD) {
              text("こんにちは 👓", style = TextStyle.HEADING)
              text(
                  "MyApplication からの表示テストです",
                  style = TextStyle.BODY,
                  color = TextColor.SECONDARY,
              )
              button(
                  label = "OK",
                  style = ButtonStyle.PRIMARY,
                  iconName = IconName.CHECKMARK,
                  onClick = { sendHelloCard(target) },
              )
            }
          }
          .onFailure { error, _ ->
            _ui.value = DisplayUi(DisplayStatus.ERROR, "送信失敗: ${error.description}")
          }
    }
  }

  fun stop() {
    sessionStateJob?.cancel()
    sessionErrorJob?.cancel()
    displayStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob = null
    displayStateJob = null
    session?.removeDisplay()
    session?.stop()
    session = null
    display = null
    _ui.value = DisplayUi(DisplayStatus.IDLE)
  }

  override fun onCleared() {
    super.onCleared()
    stop()
  }
}
