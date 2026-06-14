package com.example.myapplication.tutor

import org.json.JSONArray
import org.json.JSONObject

enum class TutorPhase {
  IDLE,
  WATCHING,
  CLASSIFYING,
  OFFERING,
  ANALYZING,
  HINT_VISIBLE,
  COOLDOWN,
  ERROR,
}

data class TutorUiState(
    val phase: TutorPhase = TutorPhase.IDLE,
    val stableSeconds: Int = 0,
    val message: String? = null,
    val problemFingerprint: String? = null,
    val currentHintIndex: Int = 0,
    val hintCount: Int = 0,
    val error: String? = null,
)

data class GeometryClassification(
    val isGeometryProblem: Boolean,
    val completeProblemVisible: Boolean,
    val svgSupported: Boolean,
    val userIsWriting: Boolean,
    val problemFingerprint: String,
    val summary: String,
    val confidence: Double,
) {
  companion object {
    fun fromJson(json: JSONObject) =
        GeometryClassification(
            isGeometryProblem = json.optBoolean("isGeometryProblem"),
            completeProblemVisible = json.optBoolean("completeProblemVisible"),
            svgSupported = json.optBoolean("svgSupported"),
            userIsWriting = json.optBoolean("userIsWriting"),
            problemFingerprint = json.optString("problemFingerprint"),
            summary = json.optString("summary"),
            confidence = json.optDouble("confidence"),
        )
  }
}

data class DiagramPoint(
    val id: String,
    val x: Double,
    val y: Double,
    val label: String,
)

data class DiagramSegment(
    val id: String,
    val from: String,
    val to: String,
    val style: String,
    val label: String,
    val ticks: Int,
    val parallelMarks: Int,
    val dashed: Boolean,
)

data class DiagramPolygon(
    val points: List<String>,
    val style: String,
)

data class DiagramCircle(
    val center: String,
    val radius: Double,
    val style: String,
    val label: String,
)

data class DiagramAngle(
    val from: String,
    val vertex: String,
    val to: String,
    val style: String,
    val label: String,
    val rightAngle: Boolean,
)

data class DiagramText(
    val x: Double,
    val y: Double,
    val text: String,
    val style: String,
)

data class DiagramSpec(
    val points: List<DiagramPoint>,
    val segments: List<DiagramSegment>,
    val polygons: List<DiagramPolygon>,
    val circles: List<DiagramCircle>,
    val angles: List<DiagramAngle>,
    val texts: List<DiagramText>,
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put(
              "points",
              JSONArray().apply {
                points.forEach {
                  put(
                      JSONObject()
                          .put("id", it.id)
                          .put("x", it.x)
                          .put("y", it.y)
                          .put("label", it.label))
                }
              },
          )
          .put(
              "segments",
              JSONArray().apply {
                segments.forEach {
                  put(
                      JSONObject()
                          .put("id", it.id)
                          .put("from", it.from)
                          .put("to", it.to)
                          .put("style", it.style)
                          .put("label", it.label)
                          .put("ticks", it.ticks)
                          .put("parallelMarks", it.parallelMarks)
                          .put("dashed", it.dashed))
                }
              },
          )
          .put(
              "polygons",
              JSONArray().apply {
                polygons.forEach {
                  put(
                      JSONObject()
                          .put("points", JSONArray(it.points))
                          .put("style", it.style))
                }
              },
          )
          .put(
              "circles",
              JSONArray().apply {
                circles.forEach {
                  put(
                      JSONObject()
                          .put("center", it.center)
                          .put("radius", it.radius)
                          .put("style", it.style)
                          .put("label", it.label))
                }
              },
          )
          .put(
              "angles",
              JSONArray().apply {
                angles.forEach {
                  put(
                      JSONObject()
                          .put("from", it.from)
                          .put("vertex", it.vertex)
                          .put("to", it.to)
                          .put("style", it.style)
                          .put("label", it.label)
                          .put("rightAngle", it.rightAngle))
                }
              },
          )
          .put(
              "texts",
              JSONArray().apply {
                texts.forEach {
                  put(
                      JSONObject()
                          .put("x", it.x)
                          .put("y", it.y)
                          .put("text", it.text)
                          .put("style", it.style))
                }
              },
          )

  companion object {
    fun fromJson(json: JSONObject): DiagramSpec =
        DiagramSpec(
            points =
                json.arrayObjects("points").map {
                  DiagramPoint(
                      id = it.optString("id"),
                      x = it.optDouble("x"),
                      y = it.optDouble("y"),
                      label = it.optString("label"),
                  )
                },
            segments =
                json.arrayObjects("segments").map {
                  DiagramSegment(
                      id = it.optString("id"),
                      from = it.optString("from"),
                      to = it.optString("to"),
                      style = it.optString("style"),
                      label = it.optString("label"),
                      ticks = it.optInt("ticks"),
                      parallelMarks = it.optInt("parallelMarks"),
                      dashed = it.optBoolean("dashed"),
                  )
                },
            polygons =
                json.arrayObjects("polygons").map {
                  DiagramPolygon(
                      points = it.optJSONArray("points").stringValues(),
                      style = it.optString("style"),
                  )
                },
            circles =
                json.arrayObjects("circles").map {
                  DiagramCircle(
                      center = it.optString("center"),
                      radius = it.optDouble("radius"),
                      style = it.optString("style"),
                      label = it.optString("label"),
                  )
                },
            angles =
                json.arrayObjects("angles").map {
                  DiagramAngle(
                      from = it.optString("from"),
                      vertex = it.optString("vertex"),
                      to = it.optString("to"),
                      style = it.optString("style"),
                      label = it.optString("label"),
                      rightAngle = it.optBoolean("rightAngle"),
                  )
                },
            texts =
                json.arrayObjects("texts").map {
                  DiagramText(
                      x = it.optDouble("x"),
                      y = it.optDouble("y"),
                      text = it.optString("text"),
                      style = it.optString("style"),
                  )
                },
        )
  }
}

data class GeometryHint(
    val speech: String,
    val caption: String,
    val diagram: DiagramSpec,
) {
  fun toJson(): JSONObject =
      JSONObject().put("speech", speech).put("caption", caption).put("diagram", diagram.toJson())

  companion object {
    fun fromJson(json: JSONObject) =
        GeometryHint(
            speech = json.optString("speech"),
            caption = json.optString("caption"),
            diagram = DiagramSpec.fromJson(json.optJSONObject("diagram") ?: JSONObject()),
        )
  }
}

data class TutorAnalysis(
    val problemFingerprint: String,
    val problemText: String,
    val target: String,
    val answer: String,
    val confidence: Double,
    val hints: List<GeometryHint>,
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("problemFingerprint", problemFingerprint)
          .put("problemText", problemText)
          .put("target", target)
          .put("answer", answer)
          .put("confidence", confidence)
          .put("hints", JSONArray().apply { hints.forEach { put(it.toJson()) } })

  companion object {
    fun fromJson(json: JSONObject) =
        TutorAnalysis(
            problemFingerprint = json.optString("problemFingerprint"),
            problemText = json.optString("problemText"),
            target = json.optString("target"),
            answer = json.optString("answer"),
            confidence = json.optDouble("confidence"),
            hints = json.arrayObjects("hints").map(GeometryHint::fromJson),
        )
  }
}

data class AnalysisVerification(
    val valid: Boolean,
    val confidence: Double,
    val reason: String,
) {
  companion object {
    fun fromJson(json: JSONObject) =
        AnalysisVerification(
            valid = json.optBoolean("valid"),
            confidence = json.optDouble("confidence"),
            reason = json.optString("reason"),
        )
  }
}

private fun JSONObject.arrayObjects(name: String): List<JSONObject> {
  val array = optJSONArray(name) ?: JSONArray()
  return buildList {
    for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
  }
}

private fun JSONArray?.stringValues(): List<String> {
  val array = this ?: return emptyList()
  return buildList {
    for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotEmpty)?.let(::add)
  }
}
