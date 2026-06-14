// AI avatar: loops a muted video — talking.mp4 while Gemini speaks, nottalking.mp4 when idle.

package com.example.myapplication.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.myapplication.R

@OptIn(UnstableApi::class)
@Composable
fun AvatarVideo(speaking: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current

  val player = remember {
    ExoPlayer.Builder(context).build().apply {
      repeatMode = Player.REPEAT_MODE_ONE // loop the current clip
      volume = 0f // muted — Gemini's TTS is the audio
      playWhenReady = true
    }
  }

  // Swap the clip whenever the speaking state changes.
  LaunchedEffect(speaking) {
    val res = if (speaking) R.raw.talking else R.raw.nottalking
    player.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(res)))
    player.prepare()
    player.play()
  }

  DisposableEffect(Unit) { onDispose { player.release() } }

  AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          useController = false
          this.player = player
          resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
          setShutterBackgroundColor(Color.TRANSPARENT)
        }
      },
      modifier = modifier,
  )
}
