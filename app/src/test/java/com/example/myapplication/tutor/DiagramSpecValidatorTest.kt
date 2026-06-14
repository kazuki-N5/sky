package com.example.myapplication.tutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramSpecValidatorTest {
  @Test
  fun acceptsValidGeometryHint() {
    val validation = DiagramSpecValidator.validateAnalysis(validAnalysis())
    assertTrue(validation.errors.joinToString(), validation.valid)
  }

  @Test
  fun rejectsUndefinedPointReference() {
    val analysis = validAnalysis()
    val invalid =
        analysis.copy(
            hints =
                listOf(
                    analysis.hints.first().copy(
                        diagram =
                            analysis.hints.first().diagram.copy(
                                segments =
                                    listOf(
                                        DiagramSegment(
                                            id = "missing",
                                            from = "A",
                                            to = "Z",
                                            style = "normal",
                                            label = "",
                                            ticks = 0,
                                            parallelMarks = 0,
                                            dashed = false,
                                        ))))))
    assertFalse(DiagramSpecValidator.validateAnalysis(invalid).valid)
  }

  @Test
  fun rejectsAnswerLeakInHint() {
    val analysis = validAnalysis()
    val invalid =
        analysis.copy(
            hints = listOf(analysis.hints.first().copy(speech = "答えは72°です")))
    assertFalse(DiagramSpecValidator.validateAnalysis(invalid).valid)
  }

  private fun validAnalysis(): TutorAnalysis {
    val diagram =
        DiagramSpec(
            points =
                listOf(
                    DiagramPoint("A", 500.0, 100.0, "A"),
                    DiagramPoint("B", 150.0, 850.0, "B"),
                    DiagramPoint("C", 850.0, 850.0, "C"),
                ),
            segments =
                listOf(
                    DiagramSegment("AB", "A", "B", "normal", "", 1, 0, false),
                    DiagramSegment("AC", "A", "C", "normal", "", 1, 0, false),
                    DiagramSegment("BC", "B", "C", "normal", "", 0, 0, false),
                ),
            polygons = emptyList(),
            circles = emptyList(),
            angles =
                listOf(
                    DiagramAngle("A", "C", "B", "highlight", "36°", false),
                ),
            texts = listOf(DiagramText(500.0, 930.0, "AB = AC", "highlight")),
        )
    return TutorAnalysis(
        problemFingerprint = "isosceles-angle",
        problemText = "AB = ACの三角形",
        target = "角BAC",
        answer = "72°",
        confidence = 0.95,
        hints =
            listOf(
                GeometryHint(
                    speech = "等しい辺から二等辺三角形を探そう",
                    caption = "AB = AC に注目",
                    diagram = diagram,
                )),
    )
  }
}
