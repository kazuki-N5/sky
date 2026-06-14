// Text generation via the Gemini REST generateContent API (used to summarize a day into a diary).

package com.example.myapplication.journal

import com.example.myapplication.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object GeminiTextClient {
  // Model for diary summarization (per user's request).
  private const val MODEL = "gemini-3-flash-preview"

  private val client =
      OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

  /** Blocking call — run on a background dispatcher. Returns generated text or throws. */
  fun generate(prompt: String): String {
    val key = BuildConfig.GEMINI_API_KEY
    require(key.isNotBlank()) { "Gemini API key not set (local.properties: gemini_api_key)" }

    val url =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$key"
    val payload =
        JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))),
            )
            .toString()
    val request =
        Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

    client.newCall(request).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) {
        throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
      }
      val json = JSONObject(body)
      val candidates =
          json.optJSONArray("candidates")
              ?: throw RuntimeException("No candidates in response: ${body.take(200)}")
      if (candidates.length() == 0) throw RuntimeException("Empty candidates")
      val parts =
          candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
              ?: throw RuntimeException("No content parts")
      val sb = StringBuilder()
      for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
      val text = sb.toString().trim()
      if (text.isEmpty()) throw RuntimeException("Empty text in response")
      return text
    }
  }

  /**
   * Positivity 0-100 of the diary, obtained via Gemini FUNCTION CALLING (the model must call
   * set_sentiment with `positivity`). Negativity is derived as 100 - positivity by the caller.
   */
  fun analyzeSentiment(diary: String): Int {
    val key = BuildConfig.GEMINI_API_KEY
    require(key.isNotBlank()) { "Gemini API key not set" }
    val url =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$key"

    val parameters =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "positivity",
                        JSONObject()
                            .put("type", "INTEGER")
                            .put(
                                "description",
                                "その日のポジティブ度。0=とてもネガティブ、100=とてもポジティブ。",
                            ),
                    ),
            )
            .put("required", JSONArray().put("positivity"))
    val functionDeclaration =
        JSONObject()
            .put("name", "set_sentiment")
            .put(
                "description",
                "その日の日記のポジティブ度(0-100)を記録する。ネガティブ度は100から引いた値になる。",
            )
            .put("parameters", parameters)
    val tools =
        JSONArray()
            .put(JSONObject().put("functionDeclarations", JSONArray().put(functionDeclaration)))
    val toolConfig =
        JSONObject()
            .put(
                "functionCallingConfig",
                JSONObject()
                    .put("mode", "ANY")
                    .put("allowedFunctionNames", JSONArray().put("set_sentiment")),
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
                                    .put(
                                        JSONObject()
                                            .put(
                                                "text",
                                                "次の日記を読み、その日のポジティブ度を判定して set_sentiment を呼び出してください。\n\n日記:\n$diary",
                                            )),
                            )),
            )
            .put("tools", tools)
            .put("toolConfig", toolConfig)
            .toString()

    val request =
        Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

    client.newCall(request).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
      val parts =
          JSONObject(body)
              .optJSONArray("candidates")
              ?.optJSONObject(0)
              ?.optJSONObject("content")
              ?.optJSONArray("parts")
              ?: throw RuntimeException("No parts in response: ${body.take(200)}")
      for (i in 0 until parts.length()) {
        val args = parts.optJSONObject(i)?.optJSONObject("functionCall")?.optJSONObject("args")
        if (args != null && args.has("positivity")) {
          return args.optInt("positivity", 50).coerceIn(0, 100)
        }
      }
      throw RuntimeException("No set_sentiment call: ${body.take(300)}")
    }
  }
}
