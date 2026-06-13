// Adapted from the Meta Wearables DAT "CameraAccess" sample.

package com.example.myapplication.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamState

data class StreamUiState(
    val streamState: StreamState = StreamState.STOPPED,
    val videoFrame: Bitmap? = null,
    val videoFrameCount: Int = 0,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
)
