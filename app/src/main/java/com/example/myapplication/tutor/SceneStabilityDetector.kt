package com.example.myapplication.tutor

import android.graphics.Bitmap

data class SceneObservation(
    val stableForMs: Long,
    val fingerprint: String,
    val changed: Boolean,
)

class SceneStabilityDetector(
    private val sampleWidth: Int = 32,
    private val sampleHeight: Int = 32,
    private val frameChangeThreshold: Double = 0.12,
    private val anchorChangeThreshold: Double = 0.07,
    private val fingerprintChangeThreshold: Int = 10,
) {
  private var previous: IntArray? = null
  private var anchor: IntArray? = null
  private var stableSinceMs: Long = 0L

  fun observe(bitmap: Bitmap, nowMs: Long): SceneObservation {
    return observeSample(sampleLuma(bitmap), nowMs)
  }

  internal fun observeSample(sample: IntArray, nowMs: Long): SceneObservation {
    require(sample.size == sampleWidth * sampleHeight) {
      "Expected ${sampleWidth * sampleHeight} luma samples, got ${sample.size}"
    }
    val old = previous
    val baseline = anchor
    val currentFingerprint = fingerprint(sample)
    val changed =
        old == null ||
            baseline == null ||
            normalizedDifference(old, sample) > frameChangeThreshold ||
            normalizedDifference(baseline, sample) > anchorChangeThreshold ||
            fingerprintDistance(fingerprint(baseline), currentFingerprint) >
                fingerprintChangeThreshold

    if (changed) {
      stableSinceMs = nowMs
      anchor = sample.copyOf()
    }
    previous = sample
    return SceneObservation(
        stableForMs = (nowMs - stableSinceMs).coerceAtLeast(0L),
        // The fingerprint identifies the stable scene, so it must not drift with each camera frame.
        fingerprint = fingerprint(anchor ?: sample),
        changed = changed,
    )
  }

  fun reset() {
    previous = null
    anchor = null
    stableSinceMs = 0L
  }

  private fun sampleLuma(bitmap: Bitmap): IntArray {
    val scaled =
        if (bitmap.width == sampleWidth && bitmap.height == sampleHeight) {
          bitmap
        } else {
          Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
    val pixels = IntArray(sampleWidth * sampleHeight)
    scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
    val result = IntArray(sampleWidth * sampleHeight)
    try {
      for (index in pixels.indices) {
        val color = pixels[index]
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        result[index] = (red * 299 + green * 587 + blue * 114) / 1000
      }
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }
    return result
  }

  private fun normalizedDifference(first: IntArray, second: IntArray): Double =
      first.indices.sumOf { kotlin.math.abs(first[it] - second[it]) }.toDouble() /
          (first.size * 255.0)

  private fun fingerprintDistance(first: String, second: String): Int =
      (first.toULong(16) xor second.toULong(16)).countOneBits()

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
