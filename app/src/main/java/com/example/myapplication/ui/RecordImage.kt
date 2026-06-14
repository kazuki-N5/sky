// Shows a saved record photo from a file path. Decodes a downsampled bitmap off the
// main thread (no extra image library needed).

package com.example.myapplication.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RecordImage(path: String, modifier: Modifier = Modifier) {
  val image by
      produceState<ImageBitmap?>(initialValue = null, path) {
        value =
            withContext(Dispatchers.IO) {
              runCatching {
                    // Measure first, then decode downsampled to ~1080px wide to keep it light.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val opts =
                        BitmapFactory.Options().apply {
                          inSampleSize = max(1, bounds.outWidth / 1080)
                        }
                    BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
                  }
                  .getOrNull()
            }
      }

  image?.let {
    Image(
        bitmap = it,
        contentDescription = "撮影した画像",
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
  }
}
