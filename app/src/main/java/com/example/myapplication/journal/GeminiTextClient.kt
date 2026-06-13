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
  private const val MODEL = "gemini-3.1-flash"

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
}
