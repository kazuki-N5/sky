package com.example.myapplication.tutor

import kotlin.math.abs

data class DiagramValidation(val valid: Boolean, val errors: List<String>)

object DiagramSpecValidator {
  private val idPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,15}")
  private val styles = setOf("normal", "muted", "highlight", "target")

  fun validateAnalysis(analysis: TutorAnalysis): DiagramValidation {
    val errors = mutableListOf<String>()
    if (analysis.problemFingerprint.length !in 1..80) errors += "invalid problem fingerprint"
    if (analysis.problemText.length !in 1..600) errors += "invalid problem text"
    if (analysis.target.length !in 1..160) errors += "invalid target"
    if (analysis.answer.length !in 1..80) errors += "invalid answer"
    if (analysis.confidence !in 0.0..1.0) errors += "invalid analysis confidence"
    if (analysis.hints.size !in 1..4) errors += "hint count must be 1..4"

    analysis.hints.forEachIndexed { index, hint ->
      if (hint.speech.length !in 1..160) errors += "hint[$index] speech is invalid"
      if (hint.caption.length !in 1..80) errors += "hint[$index] caption is invalid"
      if (containsMarkup(hint.speech) || containsMarkup(hint.caption)) {
        errors += "hint[$index] contains markup"
      }
      if (leaksAnswer(analysis.answer, hint.speech + hint.caption)) {
        errors += "hint[$index] leaks the answer"
      }
      validateDiagram(hint.diagram).errors.forEach { errors += "hint[$index]: $it" }
    }

    return DiagramValidation(errors.isEmpty(), errors)
  }

  fun validateDiagram(spec: DiagramSpec): DiagramValidation {
    val errors = mutableListOf<String>()
    if (spec.points.size !in 2..32) errors += "point count must be 2..32"
    if (spec.segments.size > 64) errors += "too many segments"
    if (spec.polygons.size > 16) errors += "too many polygons"
    if (spec.circles.size > 8) errors += "too many circles"
    if (spec.angles.size > 24) errors += "too many angles"
    if (spec.texts.size > 20) errors += "too many texts"

    val ids = mutableSetOf<String>()
    spec.points.forEach {
      if (!idPattern.matches(it.id)) errors += "invalid point id ${it.id}"
      if (!ids.add(it.id)) errors += "duplicate point id ${it.id}"
      if (!inRange(it.x) || !inRange(it.y)) errors += "point ${it.id} is out of range"
      validateText(it.label, 12, "point ${it.id} label", errors)
    }

    val segmentIds = mutableSetOf<String>()
    spec.segments.forEach {
      if (!idPattern.matches(it.id)) errors += "invalid segment id ${it.id}"
      if (!segmentIds.add(it.id)) errors += "duplicate segment id ${it.id}"
      if (it.from !in ids || it.to !in ids || it.from == it.to) {
        errors += "segment ${it.id} has invalid point references"
      }
      validateStyle(it.style, "segment ${it.id}", errors)
      validateText(it.label, 16, "segment ${it.id} label", errors)
      if (it.ticks !in 0..3) errors += "segment ${it.id} ticks must be 0..3"
      if (it.parallelMarks !in 0..2) errors += "segment ${it.id} parallel marks must be 0..2"
    }

    spec.polygons.forEachIndexed { index, polygon ->
      if (polygon.points.size !in 3..12 || polygon.points.any { it !in ids }) {
        errors += "polygon[$index] has invalid point references"
      }
      validateStyle(polygon.style, "polygon[$index]", errors)
    }

    spec.circles.forEachIndexed { index, circle ->
      if (circle.center !in ids) errors += "circle[$index] has an invalid center"
      if (!circle.radius.isFinite() || circle.radius !in 1.0..500.0) {
        errors += "circle[$index] has an invalid radius"
      }
      validateStyle(circle.style, "circle[$index]", errors)
      validateText(circle.label, 16, "circle[$index] label", errors)
    }

    spec.angles.forEachIndexed { index, angle ->
      if (
          angle.from !in ids ||
              angle.vertex !in ids ||
              angle.to !in ids ||
              setOf(angle.from, angle.vertex, angle.to).size != 3
      ) {
        errors += "angle[$index] has invalid point references"
      }
      validateStyle(angle.style, "angle[$index]", errors)
      validateText(angle.label, 16, "angle[$index] label", errors)
    }

    spec.texts.forEachIndexed { index, text ->
      if (!inRange(text.x) || !inRange(text.y)) errors += "text[$index] is out of range"
      validateStyle(text.style, "text[$index]", errors)
      validateText(text.text, 60, "text[$index]", errors, allowEmpty = false)
    }

    return DiagramValidation(errors.isEmpty(), errors)
  }

  private fun validateStyle(style: String, owner: String, errors: MutableList<String>) {
    if (style !in styles) errors += "$owner has invalid style"
  }

  private fun validateText(
      text: String,
      maxLength: Int,
      owner: String,
      errors: MutableList<String>,
      allowEmpty: Boolean = true,
  ) {
    if ((!allowEmpty && text.isEmpty()) || text.length > maxLength || containsMarkup(text)) {
      errors += "$owner is invalid"
    }
  }

  private fun inRange(value: Double): Boolean =
      value.isFinite() && value >= 0.0 && value <= 1000.0 && abs(value) < Double.MAX_VALUE

  private fun containsMarkup(value: String): Boolean = '<' in value || '>' in value

  private fun leaksAnswer(answer: String, hint: String): Boolean {
    val normalizedAnswer = normalize(answer)
    if (normalizedAnswer.length < 2) return false
    return normalize(hint).contains(normalizedAnswer)
  }

  private fun normalize(value: String): String =
      value.lowercase().replace(Regex("[\\s,。．]"), "")
}
