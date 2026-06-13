// Local persistence for daily records (記録) and diaries (日記), keyed by yyyy-MM-dd.
// Backed by SharedPreferences as a single JSON blob; exposed as a StateFlow so all screens update.

package com.example.myapplication.journal

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecordItem(val time: String, val text: String)

data class DayEntry(val records: List<RecordItem> = emptyList(), val diary: String? = null)

object JournalRepo {
  private const val PREFS = "journal_prefs"
  private const val KEY = "journal_json"

  private var appContext: Context? = null
  private val _data = MutableStateFlow<Map<String, DayEntry>>(emptyMap())
  val data: StateFlow<Map<String, DayEntry>> = _data.asStateFlow()

  @Synchronized
  fun init(context: Context) {
    if (appContext != null) return
    appContext = context.applicationContext
    load()
  }

  fun today(): String = LocalDate.now().toString()

  fun entry(date: String): DayEntry = _data.value[date] ?: DayEntry()

  @Synchronized
  fun addRecord(text: String, date: String = today()) {
    val now = LocalTime.now()
    val time = "%02d:%02d".format(now.hour, now.minute)
    val map = _data.value.toMutableMap()
    val day = map[date] ?: DayEntry()
    map[date] = day.copy(records = day.records + RecordItem(time, text))
    _data.value = map
    save()
  }

  @Synchronized
  fun setDiary(text: String, date: String = today()) {
    val map = _data.value.toMutableMap()
    val day = map[date] ?: DayEntry()
    map[date] = day.copy(diary = text)
    _data.value = map
    save()
  }

  private fun load() {
    val ctx = appContext ?: return
    val json =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
    try {
      val root = JSONObject(json)
      val map = mutableMapOf<String, DayEntry>()
      root.keys().forEach { date ->
        val o = root.getJSONObject(date)
        val recs = mutableListOf<RecordItem>()
        val arr = o.optJSONArray("records") ?: JSONArray()
        for (i in 0 until arr.length()) {
          val r = arr.getJSONObject(i)
          recs.add(RecordItem(r.optString("time"), r.optString("text")))
        }
        val diary = if (o.isNull("diary")) null else o.optString("diary").ifEmpty { null }
        map[date] = DayEntry(recs, diary)
      }
      _data.value = map
    } catch (_: Exception) {}
  }

  private fun save() {
    val ctx = appContext ?: return
    val root = JSONObject()
    _data.value.forEach { (date, day) ->
      val o = JSONObject()
      val arr = JSONArray()
      day.records.forEach { arr.put(JSONObject().put("time", it.time).put("text", it.text)) }
      o.put("records", arr)
      day.diary?.let { o.put("diary", it) }
      root.put(date, o)
    }
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, root.toString())
        .apply()
  }
}
