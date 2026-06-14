package com.example.myapplication.tutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneStabilityDetectorTest {

  @Test
  fun stableSceneKeepsAnchorFingerprint() {
    val detector = SceneStabilityDetector()
    val original = pageWithVerticalLine(8)

    val first = detector.observeSample(original, 0L)
    val second = detector.observeSample(original.map { (it - 2).coerceAtLeast(0) }.toIntArray(), 1000L)

    assertTrue(first.changed)
    assertFalse(second.changed)
    assertEquals(first.fingerprint, second.fingerprint)
    assertEquals(1000L, second.stableForMs)
  }

  @Test
  fun gradualCameraChangeIsComparedWithStableAnchor() {
    val detector = SceneStabilityDetector()
    val original = IntArray(32 * 32) { 240 }
    detector.observeSample(original, 0L)

    var observation = detector.observeSample(IntArray(32 * 32) { 230 }, 1000L)
    assertFalse(observation.changed)

    observation = detector.observeSample(IntArray(32 * 32) { 220 }, 2000L)
    assertTrue(observation.changed)
    assertEquals(0L, observation.stableForMs)
  }

  @Test
  fun differentDiagramOnWhitePaperStartsNewScene() {
    val detector = SceneStabilityDetector()
    val first = detector.observeSample(pageWithVerticalLine(8), 0L)
    val second = detector.observeSample(pageWithVerticalLine(24), 1000L)

    assertTrue(second.changed)
    assertEquals(0L, second.stableForMs)
    assertNotEquals(first.fingerprint, second.fingerprint)
  }

  private fun pageWithVerticalLine(startColumn: Int): IntArray =
      IntArray(32 * 32) { index ->
        val column = index % 32
        if (column in startColumn until startColumn + 4) 20 else 245
      }
}
