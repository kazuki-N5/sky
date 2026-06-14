package com.example.myapplication.tutor

import android.util.Base64
import com.example.myapplication.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GeometryTutorClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
) {
  private val client =
      OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(90, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .callTimeout(120, TimeUnit.SECONDS)
          .build()

  fun classify(jpeg: ByteArray): GeometryClassification {
    val prompt =
        """
        この画像をルーティング判定してください。問題は解かないでください。
        次をすべて満たす場合だけ svgSupported=true にします。
        - 紙や画面に、問題文と図がそろった2次元の算数・数学の図形問題が見える
        - 点、線分、多角形、円、角度、辺長、平行、垂直、等辺、中点の組合せで再描画できる
        - 実寸測定、立体図形、関数グラフ、複雑な曲線、手書き途中ではない
        problemFingerprintは見えている問題を識別する短い安定した英数字文字列にしてください。
        """.trimIndent()
    return GeometryClassification.fromJson(
        generateJson(CLASSIFIER_MODEL, jpeg, prompt, classificationSchema(), 2048))
  }

  fun analyze(jpeg: ByteArray): TutorAnalysis {
    val prompt =
        """
        あなたは日本語の算数・数学の図形家庭教師です。画像中の問題を正確に読み、内部で解いてください。
        その後、答えを最初から漏らさない3段階のヒントを作成してください。

        各ヒントには、その段階で必要な情報だけを含む完全なDiagramSpecを付けます。
        DiagramSpecの座標は左上(0,0)、右下(1000,1000)です。
        図は元問題と数学的に同値にし、問題にない数値を「与えられた条件」として追加しないでください。
        点名、角度、辺長、面積、等辺印、平行印、直角印を必要に応じて使ってください。
        styleはnormal、muted、highlight、targetのいずれかです。
        segmentのticksは等辺印の本数、parallelMarksは平行印の本数です。
        ヒント本文とcaptionに最終回答そのものを含めないでください。
        生のSVG、HTML、Markdownは絶対に出力しないでください。
        """.trimIndent()
    return TutorAnalysis.fromJson(
        generateJson(SOLVER_MODEL, jpeg, prompt, analysisSchema(), 8192))
  }

  fun verify(jpeg: ByteArray, analysis: TutorAnalysis): AnalysisVerification {
    val prompt =
        """
        画像の元問題と、以下の解析JSONを照合してください。
        問題文、与条件、最終回答、各ヒントの図形関係、点名、角度、辺長が正しい場合だけvalid=trueです。
        ヒントに最終回答が漏れている場合、画像にない条件が追加されている場合、図が数学的に矛盾する場合はfalseです。

        解析JSON:
        ${analysis.toJson()}
        """.trimIndent()
    return AnalysisVerification.fromJson(
        generateJson(SOLVER_MODEL, jpeg, prompt, verificationSchema(), 2048))
  }

  private fun generateJson(
      model: String,
      jpeg: ByteArray,
      prompt: String,
      schema: JSONObject,
      maxOutputTokens: Int,
  ): JSONObject {
    require(apiKey.isNotBlank()) { "Gemini API key is not configured" }
    val imagePart =
        JSONObject()
            .put(
                "inlineData",
                JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
            )
    val payload =
        JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "parts",
                                JSONArray()
                                    .put(imagePart)
                                    .put(JSONObject().put("text", prompt)),
                            )),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("maxOutputTokens", maxOutputTokens)
                    .put("responseMimeType", "application/json")
                    .put("responseJsonSchema", schema),
            )
            .toString()

    val request =
        Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    client.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw RuntimeException("Gemini HTTP ${response.code}: ${body.take(500)}")
      }
      val parts =
          JSONObject(body)
              .optJSONArray("candidates")
              ?.optJSONObject(0)
              ?.optJSONObject("content")
              ?.optJSONArray("parts")
              ?: throw RuntimeException("Gemini returned no content")
      val text = buildString {
        for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
      }.trim()
      if (text.isEmpty()) throw RuntimeException("Gemini returned empty JSON")
      return JSONObject(text)
    }
  }

  private fun classificationSchema(): JSONObject =
      objectSchema(
          "isGeometryProblem" to booleanSchema(),
          "completeProblemVisible" to booleanSchema(),
          "svgSupported" to booleanSchema(),
          "userIsWriting" to booleanSchema(),
          "problemFingerprint" to stringSchema(1, 80),
          "summary" to stringSchema(0, 160),
          "confidence" to numberSchema(),
      )

  private fun analysisSchema(): JSONObject {
    val point =
        objectSchema(
            "id" to stringSchema(1, 16),
            "x" to coordinateSchema(),
            "y" to coordinateSchema(),
            "label" to stringSchema(0, 12),
        )
    val segment =
        objectSchema(
            "id" to stringSchema(1, 16),
            "from" to stringSchema(1, 16),
            "to" to stringSchema(1, 16),
            "style" to styleSchema(),
            "label" to stringSchema(0, 16),
            "ticks" to integerSchema(0, 3),
            "parallelMarks" to integerSchema(0, 2),
            "dashed" to booleanSchema(),
        )
    val polygon =
        objectSchema(
            "points" to arraySchema(stringSchema(1, 16), 3, 12),
            "style" to styleSchema(),
        )
    val circle =
        objectSchema(
            "center" to stringSchema(1, 16),
            "radius" to numberSchema(1.0, 500.0),
            "style" to styleSchema(),
            "label" to stringSchema(0, 16),
        )
    val angle =
        objectSchema(
            "from" to stringSchema(1, 16),
            "vertex" to stringSchema(1, 16),
            "to" to stringSchema(1, 16),
            "style" to styleSchema(),
            "label" to stringSchema(0, 16),
            "rightAngle" to booleanSchema(),
        )
    val text =
        objectSchema(
            "x" to coordinateSchema(),
            "y" to coordinateSchema(),
            "text" to stringSchema(1, 60),
            "style" to styleSchema(),
        )
    val diagram =
        objectSchema(
            "points" to arraySchema(point, 2, 32),
            "segments" to arraySchema(segment, 0, 64),
            "polygons" to arraySchema(polygon, 0, 16),
            "circles" to arraySchema(circle, 0, 8),
            "angles" to arraySchema(angle, 0, 24),
            "texts" to arraySchema(text, 0, 20),
        )
    val hint =
        objectSchema(
            "speech" to stringSchema(1, 160),
            "caption" to stringSchema(1, 80),
            "diagram" to diagram,
        )
    return objectSchema(
        "problemFingerprint" to stringSchema(1, 80),
        "problemText" to stringSchema(1, 600),
        "target" to stringSchema(1, 160),
        "answer" to stringSchema(1, 80),
        "confidence" to numberSchema(),
        "hints" to arraySchema(hint, 1, 4),
    )
  }

  private fun verificationSchema(): JSONObject =
      objectSchema(
          "valid" to booleanSchema(),
          "confidence" to numberSchema(),
          "reason" to stringSchema(0, 240),
      )

  private fun objectSchema(vararg properties: Pair<String, JSONObject>): JSONObject =
      JSONObject()
          .put("type", "object")
          .put("additionalProperties", false)
          .put(
              "properties",
              JSONObject().apply { properties.forEach { (name, schema) -> put(name, schema) } },
          )
          .put("required", JSONArray(properties.map { it.first }))

  private fun arraySchema(
      items: JSONObject,
      minItems: Int,
      maxItems: Int,
  ): JSONObject =
      JSONObject()
          .put("type", "array")
          .put("items", items)
          .put("minItems", minItems)
          .put("maxItems", maxItems)

  private fun stringSchema(minLength: Int, maxLength: Int): JSONObject =
      JSONObject()
          .put("type", "string")
          .put("minLength", minLength)
          .put("maxLength", maxLength)

  private fun booleanSchema(): JSONObject = JSONObject().put("type", "boolean")

  private fun coordinateSchema(): JSONObject = numberSchema(0.0, 1000.0)

  private fun numberSchema(minimum: Double = 0.0, maximum: Double = 1.0): JSONObject =
      JSONObject().put("type", "number").put("minimum", minimum).put("maximum", maximum)

  private fun integerSchema(minimum: Int, maximum: Int): JSONObject =
      JSONObject().put("type", "integer").put("minimum", minimum).put("maximum", maximum)

  private fun styleSchema(): JSONObject =
      JSONObject()
          .put("type", "string")
          .put("enum", JSONArray(listOf("normal", "muted", "highlight", "target")))

  private companion object {
    const val CLASSIFIER_MODEL = "gemini-3.1-flash-lite"
    const val SOLVER_MODEL = "gemini-3.5-flash"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}
