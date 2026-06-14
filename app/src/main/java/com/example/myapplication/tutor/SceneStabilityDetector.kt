package com.example.myapplication.tutor

import android.graphics.Bitmap
import kotlin.math.roundToInt

data class SceneObservation(
    val stableForMs: Long,
    val fingerprint: String,
    val changed: Boolean,
)

class SceneStabilityDetector(
    private val sampleWidth: Int = 32,
    private val sampleHeight: Int = 32,
    private val changeThreshold: Double = 0.10,
) {
  private var previous: IntArray? = null
  private var stableSinceMs: Long = 0L

  fun observe(bitmap: Bitmap, nowMs: Long): SceneObservation {
    val sample = sampleLuma(bitmap)
    val old = previous
    val changed =
        old == null ||
            old.size != sample.size ||
            old.indices.sumOf { kotlin.math.abs(old[it] - sample[it]) }.toDouble() /
                (sample.size * 255.0) > changeThreshold

    if (changed) stableSinceMs = nowMs
    previous = sample
    return SceneObservation(
        stableForMs = (nowMs - stableSinceMs).coerceAtLeast(0L),
        fingerprint = fingerprint(sample),
        changed = changed,
    )
  }

  fun reset() {
    previous = null
    stableSinceMs = 0L
  }

  private fun sampleLuma(bitmap: Bitmap): IntArray {
    val result = IntArray(sampleWidth * sampleHeight)
    for (row in 0 until sampleHeight) {
      val y = (((row + 0.5) * bitmap.height / sampleHeight).roundToInt())
          .coerceIn(0, bitmap.height - 1)
      for (column in 0 until sampleWidth) {
        val x = (((column + 0.5) * bitmap.width / sampleWidth).roundToInt())
            .coerceIn(0, bitmap.width - 1)
        val color = bitmap.getPixel(x, y)
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        result[row * sampleWidth + column] = (red * 299 + green * 587 + blue * 114) / 1000
      }
    }
    return result
  }

  private fun fingerprint(sample: IntArray): String {
    val fingerprintSamples = IntArray(64)
    for (row in 0 until 8) {
      for (column in 0 until 8) {
        val sourceRow = row * sampleHeight / 8
        val sourceColumn = column * sampleWidth / 8
        fingerprintSamples[row * 8 + column] = sample[sourceRow * sampleWidth + sourceColumn]
      }
    }
    val mean = fingerprintSamples.average()
    var hash = 0L
    for (i in fingerprintSamples.indices) {
      if (fingerprintSamples[i] >= mean) {
        hash = hash or (1L shl i)
      }
    }
    return hash.toULong().toString(16).padStart(16, '0')
  }
}
