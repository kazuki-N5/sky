// Adapted from the Meta Wearables DAT "CameraAccess" sample.
// Drives MockDeviceKit: enable/pair simulated glasses, device states, and mock camera feeds.

package com.example.myapplication.mockdevicekit

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MockDeviceKitViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "MockDeviceKitViewModel"
  }

  private val mockDeviceKit = MockDeviceKit.getInstance(application.applicationContext)

  private val _uiState = MutableStateFlow(MockDeviceKitUiState())
  val uiState: StateFlow<MockDeviceKitUiState> = _uiState.asStateFlow()

  fun enable() {
    mockDeviceKit.enable()
    _uiState.update { it.copy(isEnabled = true) }
  }

  fun disable() {
    mockDeviceKit.disable()
    _uiState.update { it.copy(isEnabled = false, pairedDevices = emptyList()) }
  }

  // Create a simulated Ray-Ban Meta glasses device
  fun pairRaybanMeta() {
    viewModelScope.launch {
      try {
        val mockDevice = mockDeviceKit.pairRaybanMeta()
        val deviceInfo =
            MockDeviceInfo(
                device = mockDevice,
                deviceId = UUID.randomUUID().toString(),
                deviceName = "RayBan Meta Glasses",
            )
        _uiState.update { currentState ->
          currentState.copy(pairedDevices = currentState.pairedDevices + deviceInfo)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to pair RayBan Meta device", e)
      }
    }
  }

  fun unpairDevice(deviceInfo: MockDeviceInfo) {
    viewModelScope.launch {
      try {
        mockDeviceKit.unpairDevice(deviceInfo.device)
        _uiState.update { currentState ->
          currentState.copy(pairedDevices = currentState.pairedDevices - deviceInfo)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to unpair device with ID: ${deviceInfo.deviceId}", e)
      }
    }
  }

  fun powerOn(deviceInfo: MockDeviceInfo) {
    executeMockDeviceOperation(deviceInfo, "Powering on", deviceInfo.copy(isPoweredOn = true)) {
        device ->
      device.powerOn()
    }
  }

  fun powerOff(deviceInfo: MockDeviceInfo) {
    executeMockDeviceOperation(
        deviceInfo,
        "Powering off",
        deviceInfo.copy(isPoweredOn = false, isDonned = false, isUnfolded = false),
    ) { device ->
      device.powerOff()
    }
  }

  fun don(deviceInfo: MockDeviceInfo) {
    executeMockDeviceOperation(
        deviceInfo,
        "Donning",
        deviceInfo.copy(isDonned = true, isUnfolded = true),
    ) { device ->
      device.don()
    }
  }

  fun doff(deviceInfo: MockDeviceInfo) {
    executeMockDeviceOperation(deviceInfo, "Doffing", deviceInfo.copy(isDonned = false)) { device ->
      device.doff()
    }
  }

  fun fold(deviceInfo: MockDeviceInfo) {
    executeMockDeviceOperation(
        deviceInfo,
        "Folding",
        deviceInfo.copy(isUnfolded = false, isDonned = false),
    ) { device ->
      device.fold()
    }
  }

  fun unfold(deviceInfo: MockDeviceInfo) {
    executeMockDeviceOperation(deviceInfo, "Unfolding", deviceInfo.copy(isUnfolded = true)) { device
      ->
      device.unfold()
    }
  }

  fun setCameraFeed(deviceInfo: MockDeviceInfo, uri: Uri) {
    viewModelScope.launch {
      try {
        // Sets video content for streaming (streamed when Stream.videoStream is active)
        deviceInfo.device.services.camera.setCameraFeed(uri)
        updateDeviceInfo(deviceInfo.copy(hasCameraFeed = true, cameraSource = null))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set camera feed for device: ${deviceInfo.deviceId}", e)
      }
    }
  }

  fun setCapturedImage(deviceInfo: MockDeviceInfo, uri: Uri) {
    viewModelScope.launch {
      try {
        // Sets photo returned when Stream.capturePhoto() is called
        deviceInfo.device.services.camera.setCapturedImage(uri)
        updateDeviceInfo(deviceInfo.copy(hasCapturedImage = true))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set captured image for device: ${deviceInfo.deviceId}", e)
      }
    }
  }

  fun setCameraFeed(deviceInfo: MockDeviceInfo, cameraFacing: CameraFacing) {
    viewModelScope.launch {
      try {
        // Streams from the phone's camera (mutually exclusive with setCameraFeed(Uri))
        deviceInfo.device.services.camera.setCameraFeed(cameraFacing)
        updateDeviceInfo(deviceInfo.copy(cameraSource = cameraFacing, hasCameraFeed = false))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set camera feed for device: ${deviceInfo.deviceId}", e)
      }
    }
  }

  private fun updateDeviceInfo(newDeviceInfo: MockDeviceInfo) {
    _uiState.update { currentState ->
      val updatedDevices =
          currentState.pairedDevices.map { device ->
            if (device.deviceId == newDeviceInfo.deviceId) {
              newDeviceInfo
            } else {
              device
            }
          }
      currentState.copy(pairedDevices = updatedDevices)
    }
  }

  private fun executeMockDeviceOperation(
      deviceInfo: MockDeviceInfo,
      operationName: String,
      updatedDeviceInfo: MockDeviceInfo,
      operation: (MockRaybanMeta) -> Unit,
  ) {
    viewModelScope.launch {
      try {
        operation(deviceInfo.device)
        updateDeviceInfo(updatedDeviceInfo)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to $operationName device with ID: ${deviceInfo.deviceId}", e)
      }
    }
  }
}
