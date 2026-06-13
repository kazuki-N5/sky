// Top-level UI for the DAT camera integration.
// State-based navigation: Home (connect) -> NonStream (device ready) -> Stream (live camera).
// A debug button opens the MockDeviceKit panel so the flow can be tested without glasses.

package com.example.myapplication.ui

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.stream.StreamViewModel
import com.example.myapplication.wearables.WearablesViewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesApp(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  LaunchedEffect(uiState.recentError) {
    uiState.recentError?.let { message ->
      snackbarHostState.showSnackbar(message)
      viewModel.clearRecentError()
    }
  }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
      when {
        uiState.isStreaming -> StreamScreen(wearablesViewModel = viewModel)
        uiState.isRegistered ->
            NonStreamScreen(
                viewModel = viewModel,
                onRequestWearablesPermission = onRequestWearablesPermission,
            )
        else -> HomeScreen(viewModel = viewModel)
      }

      SnackbarHost(
          hostState = snackbarHostState,
          modifier =
              Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
      )

      // Debug entry: simulate glasses with MockDeviceKit (no hardware required).
      if (!uiState.isStreaming) {
        FloatingActionButton(
            onClick = { viewModel.showDebugMenu() },
            modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
        ) {
          Icon(Icons.Default.BugReport, contentDescription = "Mock device menu")
        }
      }

      if (uiState.isDebugMenuVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideDebugMenu() },
            sheetState = sheetState,
        ) {
          MockDeviceKitPanel(modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

@Composable
private fun HomeScreen(viewModel: WearablesViewModel, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  Column(
      modifier = modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = "Ray-Ban Meta Camera",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text =
            "Connect your Meta glasses to stream the camera and capture photos. " +
                "No glasses? Tap the 🐞 button to simulate a device with MockDeviceKit.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = { (context as? Activity)?.let { viewModel.startRegistration(it) } },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text("Connect my glasses")
    }
  }
}

@Composable
private fun NonStreamScreen(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  Column(
      modifier = modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = if (uiState.hasActiveDevice) "Glasses connected" else "Waiting for a device…",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text =
            if (uiState.hasActiveDevice) "Ready to stream the camera feed."
            else "In the 🐞 panel: power on, unfold and don the mock glasses, then pick a camera source.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = { viewModel.navigateToStreaming(onRequestWearablesPermission) },
        enabled = uiState.hasActiveDevice,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text("Start streaming")
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { (context as? Activity)?.let { viewModel.startUnregistration(it) } }) {
      Text("Disconnect")
    }
  }
}

@Composable
private fun StreamScreen(wearablesViewModel: WearablesViewModel, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val streamViewModel: StreamViewModel =
      viewModel(
          factory =
              StreamViewModel.Factory(
                  application = context.applicationContext as Application,
                  wearablesViewModel = wearablesViewModel,
              ),
      )
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  Box(modifier = modifier.fillMaxSize()) {
    streamUiState.videoFrame?.let { frame ->
      // key() forces recomposition each frame even when the bitmap instance is reused.
      key(streamUiState.videoFrameCount) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Live camera stream",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (streamUiState.streamState == StreamState.STARTING) {
      CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }

    Row(
        modifier =
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(
          onClick = {
            streamViewModel.stopStream()
            wearablesViewModel.navigateToDeviceSelection()
          },
          modifier = Modifier.weight(1f),
      ) {
        Text("Stop")
      }
      Button(
          onClick = { streamViewModel.capturePhoto() },
          enabled = streamUiState.streamState == StreamState.STREAMING && !streamUiState.isCapturing,
          modifier = Modifier.weight(1f),
      ) {
        Text("Capture")
      }
    }

    GeminiOverlay(streamViewModel = streamViewModel)
  }

  val photo = streamUiState.capturedPhoto
  if (photo != null && streamUiState.isShareDialogVisible) {
    AlertDialog(
        onDismissRequest = { streamViewModel.hideShareDialog() },
        confirmButton = {
          TextButton(onClick = { streamViewModel.sharePhoto(photo) }) { Text("Share") }
        },
        dismissButton = {
          TextButton(onClick = { streamViewModel.hideShareDialog() }) { Text("Close") }
        },
        title = { Text("Captured photo") },
        text = {
          Image(
              bitmap = photo.asImageBitmap(),
              contentDescription = "Captured photo",
              modifier = Modifier.fillMaxWidth().height(240.dp),
              contentScale = ContentScale.Fit,
          )
        },
    )
  }
}
