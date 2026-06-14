// Adapted from the Meta Wearables DAT "CameraAccess" sample.
// Demonstrates the DAT camera streaming APIs: session + stream lifecycle, video frames, capture.

package com.example.myapplication.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.R
import com.example.myapplication.journal.JournalRepo
import com.example.myapplication.wearables.WearablesViewModel
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "MyApp:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamState.CLOSED)
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  @SuppressLint("MissingGuardedByAnnotation") private var sessionErrorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null
  private var previousDeviceSessionState: DeviceSessionState? = null

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionErrorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null
    previousDeviceSessionState = null

    // Frames are presented based on timestamp, not arrival time
    val queue =
        PresentationQueue(
            bufferDelayMs = 30L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                _uiState.update {
                  it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
                }
              }
            },
        )
    presentationQueue = queue
    queue.start()
    if (session == null) {
      previousDeviceSessionState = null
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            sessionErrorJob = viewModelScope.launch {
              createdSession.errors.collect { error -> handleSessionError(error) }
            }
            session?.start()
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to create session: ${error.description}")
            handleSessionError(error)
          }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        val prevState = previousDeviceSessionState
        previousDeviceSessionState = currentState

        if (currentState == DeviceSessionState.STARTED) {
          wearablesViewModel.setDatAppUpdateRequired(false)
          if (prevState == DeviceSessionState.PAUSED && stream != null) {
            // PAUSED → STARTED: device-initiated resume (tap gesture). SDK resumes internally.
            return@collect
          }

          videoJob?.cancel()
          stateJob?.cancel()
          errorJob?.cancel()
          stream?.stop()
          stream = null
          session
              ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15))
              ?.onSuccess { addedStream ->
                stream = addedStream
                videoJob = viewModelScope.launch {
                  stream?.videoStream?.collect { handleVideoFrame(it) }
                }
                stateJob = viewModelScope.launch {
                  stream?.state?.collect { streamState ->
                    val prevStreamState = _uiState.value.streamState
                    _uiState.update { it.copy(streamState = streamState) }

                    val wasActive = prevStreamState !in SESSION_TERMINAL_STATES
                    val isTerminated = streamState in SESSION_TERMINAL_STATES
                    if (wasActive && isTerminated) {
                      stopStream()
                      wearablesViewModel.navigateToDeviceSelection()
                    }
                  }
                }
                errorJob = viewModelScope.launch {
                  stream?.errorStream?.collect { error ->
                    if (error == StreamError.STREAM_ERROR) {
                      // Non-critical error, stream continues
                      return@collect
                    }
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                    wearablesViewModel.setRecentError(error.description)
                  }
                }
                stream?.start()
              }
              ?.onFailure { error, _ ->
                Log.e(TAG, "Failed to add stream to session: ${error.description}")
              }
        } else if (currentState == DeviceSessionState.PAUSED) {
          // Tap gesture paused the session — keep the stream alive for resume.
          Log.d(TAG, "Session paused (tap gesture) — keeping stream alive for resume")
        }
      }
    }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    presentationQueue?.stop()
    presentationQueue = null
    _uiState.update { INITIAL_STATE }
    stream?.stop()
    stream = null
    session?.stop()
    session = null
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    val alreadyShowingUpdateRequired =
        wearablesViewModel.uiState.value.isFirmwareUpdateRequired ||
            wearablesViewModel.uiState.value.isDatAppUpdateRequired

    if (
        error == DeviceSessionError.SESSION_ENDED_BY_DEVICE &&
            shouldTreatSessionEndedAsDatAppUpdateRequired()
    ) {
      wearablesViewModel.setDatAppUpdateRequired(true)
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.update_required_dat_app_message)
      )
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (alreadyShowingUpdateRequired && error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
    }
    wearablesViewModel.setRecentError(error.description)
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  private fun shouldTreatSessionEndedAsDatAppUpdateRequired(): Boolean {
    val sessionNeverStarted =
        previousDeviceSessionState != DeviceSessionState.STARTED &&
            previousDeviceSessionState != DeviceSessionState.PAUSED
    return sessionNeverStarted
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      return
    }

    if (uiState.value.streamState == StreamState.STREAMING) {
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(TAG, "Cannot capture photo: stream not active (state=${uiState.value.streamState})")
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
    val imagePath = JournalRepo.saveImage(capturedPhoto)
    JournalRepo.addRecord("📷 写真を撮影しました", imagePath = imagePath)
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    session?.stop()
    session = null
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
