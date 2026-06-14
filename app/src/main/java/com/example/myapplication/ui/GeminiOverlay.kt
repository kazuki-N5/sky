// Overlay shown on top of the camera stream: a "Talk to Gemini" toggle, status, and live transcript.
// While live, it forwards camera frames (~1 fps) to Gemini and uses the phone mic/speaker for audio.

package com.example.myapplication.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.gemini.GeminiLiveViewModel
import com.example.myapplication.gemini.GeminiStatus
import com.example.myapplication.stream.StreamViewModel
import kotlinx.coroutines.delay

@Composable
fun BoxScope.GeminiOverlay(
    streamViewModel: StreamViewModel,
    geminiViewModel: GeminiLiveViewModel = viewModel(),
) {
  val ui by geminiViewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val isOn = ui.status == GeminiStatus.LIVE || ui.status == GeminiStatus.CONNECTING

  val micPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) geminiViewModel.start()
      }

  // Stop Gemini when this screen leaves composition (e.g. streaming stops).
  DisposableEffect(Unit) { onDispose { geminiViewModel.stop() } }

  // Forward camera frames to Gemini at ~1 fps while live.
  LaunchedEffect(ui.status) {
    if (ui.status == GeminiStatus.LIVE) {
      while (true) {
        streamViewModel.uiState.value.videoFrame?.let { geminiViewModel.offerFrame(it) }
        delay(1000)
      }
    }
  }

  // AI avatar temporarily disabled (nothing is shown for now). The Bloom 3D WebView
  // rendered WebGL fine when opaque + LAYER_TYPE_HARDWARE, but stayed invisible with a
  // transparent background over the camera. To re-enable, restore here:
  //   if (ui.status == GeminiStatus.LIVE || ui.status == GeminiStatus.CONNECTING)
  //     BloomAvatar(speaking = ui.speaking,
  //         modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
  //             .padding(16.dp).size(170.dp))

  Column(
      modifier =
          Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      val (label, color) =
          when (ui.status) {
            GeminiStatus.IDLE -> "Gemini: off" to Color.LightGray
            GeminiStatus.CONNECTING -> "Gemini: connecting…" to Color(0xFFFFB300)
            GeminiStatus.LIVE -> "Gemini: live 🎙️" to Color(0xFF61BC63)
            GeminiStatus.ERROR -> "Gemini: error" to Color(0xFFFF5252)
          }
      Text(
          label,
          color = color,
          fontWeight = FontWeight.Bold,
          modifier =
              Modifier.clip(RoundedCornerShape(12.dp))
                  .background(Color.Black.copy(alpha = 0.55f))
                  .padding(horizontal = 12.dp, vertical = 6.dp),
      )
      Button(
          onClick = {
            if (isOn) {
              geminiViewModel.stop()
            } else if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) {
              geminiViewModel.start()
            } else {
              micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
          },
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = if (isOn) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary),
      ) {
        Text(if (isOn) "Stop Gemini" else "Talk to Gemini")
      }
    }

    ui.error?.let {
      Text(
          it,
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
          modifier =
              Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(Color(0xCCB00020))
                  .padding(8.dp),
      )
    }

    if (ui.userText.isNotBlank() || ui.assistantText.isNotBlank()) {
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(Color.Black.copy(alpha = 0.45f))
                  .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        if (ui.userText.isNotBlank()) {
          Text(
              "🗣️ ${ui.userText}",
              color = Color.White,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 2,
          )
        }
        if (ui.assistantText.isNotBlank()) {
          Text(
              "🤖 ${ui.assistantText}",
              color = Color(0xFF9ECBFF),
              style = MaterialTheme.typography.bodySmall,
              maxLines = 3,
          )
        }
      }
    }
  }
}
