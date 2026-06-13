// Adapted from the Meta Wearables DAT "CameraAccess" sample.
// State for simulated wearable devices (MockDeviceKit) used for testing without hardware.

package com.example.myapplication.mockdevicekit

import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

data class MockDeviceInfo(
    val device: MockRaybanMeta,
    val deviceId: String,
    val deviceName: String,
    val hasCameraFeed: Boolean = false,
    val hasCapturedImage: Boolean = false,
    val cameraSource: CameraFacing? = null,
    val isPoweredOn: Boolean = false,
    val isDonned: Boolean = false,
    val isUnfolded: Boolean = false,
)

data class MockDeviceKitUiState(
    val isEnabled: Boolean = false,
    val pairedDevices: List<MockDeviceInfo> = emptyList(),
)
