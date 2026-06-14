// AI avatar: the "Bloom" 3D companion (WebGL) rendered in a WebView.
// The HTML is bundled in assets/bloom-3d-companion/ and is fully self-contained (no network).
//
// State behavior mirrors the glasses web app (glasses-avatar/app.js) exactly:
//   speaking            -> "speaking"
//   speaking -> not     -> "happy" for 1100ms, then "idle"
//   not speaking        -> "idle"

package com.example.myapplication.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private const val TAG = "BloomWebView"
private const val BLOOM_URL =
    "file:///android_asset/bloom-3d-companion/Bloom3DModel.html?embedded=1"

// TEMP: minimal WebGL isolation test. Revert `loadUrl` to BLOOM_URL once confirmed.
private const val TEST_URL = "file:///android_asset/webgl-test.html"

// Give the page's <canvas> an explicit CSS-pixel size matching the WebView, so its
// height:100% (which is 0 in a top-level WebView) no longer collapses.
private fun fixCanvasSize(view: WebView) {
  val d = view.resources.displayMetrics.density
  val w = (view.width / d).toInt()
  val h = (view.height / d).toInt()
  if (w <= 0 || h <= 0) {
    Log.w(TAG, "fixCanvasSize skipped (not laid out: ${view.width}x${view.height})")
    return
  }
  view.evaluateJavascript(
      "(function(){var c=document.getElementById('scene');" +
          "if(c){c.style.width='${w}px';c.style.height='${h}px';" +
          "return c.clientWidth+'x'+c.clientHeight;}return 'no-canvas';})();") { r ->
        Log.w(TAG, "fixCanvasSize -> ${w}x${h}css, canvas now $r")
      }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BloomAvatar(speaking: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var ready by remember { mutableStateOf(false) }
  var wasSpeaking by remember { mutableStateOf(false) }

  val webView = remember {
    WebView(context).apply {
      setBackgroundColor(0) // 0 = transparent: the avatar floats over the camera stream
      setLayerType(View.LAYER_TYPE_HARDWARE, null) // ensure WebGL is GPU-composited
      overScrollMode = WebView.OVER_SCROLL_NEVER
      isVerticalScrollBarEnabled = false
      isHorizontalScrollBarEnabled = false
      settings.javaScriptEnabled = true
      webChromeClient =
          object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
              Log.w(
                  TAG,
                  "console[${cm.messageLevel()}] ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})")
              return true
            }
          }
      webViewClient =
          object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
              Log.w(TAG, "onPageFinished: $url")
              ready = true
              view ?: return
              fixCanvasSize(view)
              view.postDelayed({ fixCanvasSize(view) }, 80)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
              Log.e(TAG, "onReceivedError ${error?.errorCode} ${error?.description} url=${request?.url}")
            }
          }
      loadUrl(BLOOM_URL)
    }
  }

  fun setBloom(stateName: String) {
    webView.evaluateJavascript("window.setBloom3DState && window.setBloom3DState('$stateName');", null)
  }

  LaunchedEffect(speaking, ready) {
    if (!ready) return@LaunchedEffect
    val prev = wasSpeaking
    wasSpeaking = speaking
    when {
      speaking -> setBloom("speaking")
      prev -> {
        setBloom("happy")
        delay(1100)
        setBloom("idle")
      }
      else -> setBloom("idle")
    }
  }

  AndroidView(factory = { webView }, modifier = modifier)

  DisposableEffect(Unit) { onDispose { webView.destroy() } }
}
