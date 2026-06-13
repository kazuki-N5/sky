package com.example.myapplication

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import com.example.myapplication.journal.JournalRepo
import com.example.myapplication.ui.RootScaffold
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.wearables.WearablesViewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  companion object {
    // Android permissions required for the DAT SDK to function.
    val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, CAMERA, INTERNET)
  }

  val viewModel: WearablesViewModel by viewModels()

  private val permissionCheckLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
        viewModel.onPermissionsResult(permissionsResult) {
          // Initialize the DAT SDK once permissions are granted — REQUIRED before any Wearables API.
          Wearables.initialize(this)
        }
      }

  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  // Requesting wearable device (glasses) permissions via the Meta AI app.
  private val permissionsResultLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(permissionStatus)
        permissionContinuation = null
      }

  // Sequential wearable permission request (Mutex prevents concurrent requests).
  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return permissionMutex.withLock {
      suspendCancellableCoroutine { continuation ->
        permissionContinuation = continuation
        continuation.invokeOnCancellation { permissionContinuation = null }
        permissionsResultLauncher.launch(permission)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    JournalRepo.init(this)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        RootScaffold(
            wearablesViewModel = viewModel,
            onRequestWearablesPermission = ::requestWearablesPermission,
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    // Ensure the app has the necessary Android permissions first.
    permissionCheckLauncher.launch(PERMISSIONS)
  }
}
