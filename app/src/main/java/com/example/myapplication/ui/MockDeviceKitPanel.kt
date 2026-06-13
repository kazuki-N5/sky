// MockDeviceKit panel — simulate Ray-Ban Meta glasses without hardware.
// Enable -> Pair -> Power/Unfold/Don -> choose a camera source (phone camera, video, or image).

package com.example.myapplication.ui

import android.Manifest
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.mockdevicekit.MockDeviceInfo
import com.example.myapplication.mockdevicekit.MockDeviceKitViewModel
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

@Composable
fun MockDeviceKitPanel(
    modifier: Modifier = Modifier,
    viewModel: MockDeviceKitViewModel =
        viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  Column(
      modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          "Mock Device Kit",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
      )
      Text("${uiState.pairedDevices.size} paired", color = MaterialTheme.colorScheme.primary)
    }
    Text(
        "Simulate Ray-Ban Meta glasses without hardware.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider()

    if (uiState.isEnabled) {
      Button(
          onClick = { viewModel.disable() },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Disable MockDeviceKit")
      }
      Button(
          onClick = { viewModel.pairRaybanMeta() },
          enabled = uiState.pairedDevices.size < 3,
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Pair RayBan Meta")
      }
    } else {
      Button(onClick = { viewModel.enable() }, modifier = Modifier.fillMaxWidth()) {
        Text("Enable MockDeviceKit")
      }
    }

    uiState.pairedDevices.forEach { device -> MockDeviceCard(device = device, viewModel = viewModel) }
  }
}

@Composable
private fun MockDeviceCard(device: MockDeviceInfo, viewModel: MockDeviceKitViewModel) {
  val videoPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setCameraFeed(device, it) }
      }
  val imagePicker =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setCapturedImage(device, it) }
      }
  var pendingFacing by remember { mutableStateOf<CameraFacing?>(null) }
  val cameraPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingFacing?.let { viewModel.setCameraFeed(device, it) }
        pendingFacing = null
      }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            device.deviceName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { viewModel.unpairDevice(device) }) { Text("Unpair") }
      }

      ToggleRow("Power", device.isPoweredOn) { on ->
        if (on) viewModel.powerOn(device) else viewModel.powerOff(device)
      }
      ToggleRow("Donned", device.isDonned) { on ->
        if (on) viewModel.don(device) else viewModel.doff(device)
      }
      ToggleRow("Unfolded", device.isUnfolded) { on ->
        if (on) viewModel.unfold(device) else viewModel.fold(device)
      }

      CameraSourceDropdown(
          device = device,
          onFront = {
            pendingFacing = CameraFacing.FRONT
            cameraPermission.launch(Manifest.permission.CAMERA)
          },
          onBack = {
            pendingFacing = CameraFacing.BACK
            cameraPermission.launch(Manifest.permission.CAMERA)
          },
          onVideo = { videoPicker.launch("video/*") },
      )

      OutlinedButton(
          onClick = { imagePicker.launch("image/*") },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(if (device.hasCapturedImage) "Capture image set ✓" else "Select capture image")
      }
    }
  }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun CameraSourceDropdown(
    device: MockDeviceInfo,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onVideo: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val label =
      when {
        device.cameraSource == CameraFacing.FRONT -> "Front phone camera"
        device.cameraSource == CameraFacing.BACK -> "Back phone camera"
        device.hasCameraFeed -> "Video file"
        else -> "None"
      }
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Text("Camera source: $label", modifier = Modifier.weight(1f))
      Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text("Front phone camera") },
          onClick = {
            onFront()
            expanded = false
          },
      )
      DropdownMenuItem(
          text = { Text("Back phone camera") },
          onClick = {
            onBack()
            expanded = false
          },
      )
      DropdownMenuItem(
          text = { Text("Video file…") },
          onClick = {
            onVideo()
            expanded = false
          },
      )
    }
  }
}
