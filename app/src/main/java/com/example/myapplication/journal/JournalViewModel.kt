// Drives the History (履歴) and Diary (日記) tabs: selected date, records/diary data, diary generation.

package com.example.myapplication.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiaryUiState(
    val generating: Boolean = false,
    val error: String? = null,
)

class JournalViewModel(app: Application) : AndroidViewModel(app) {

  init {
    JournalRepo.init(app)
  }

  val data: StateFlow<Map<String, DayEntry>> = JournalRepo.data

  private val _selectedDate = MutableStateFlow(LocalDate.now().toString())
  val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

  private val _diaryUi = MutableStateFlow(DiaryUiState())
  val diaryUi: StateFlow<DiaryUiState> = _diaryUi.asStateFlow()

  fun today(): String = JournalRepo.today()

  fun selectDate(date: String) {
    _selectedDate.value = date
  }

  /** The diary button is only enabled from 23:00 onward (per spec). */
  fun canGenerateNow(): Boolean = LocalDateTime.now().hour >= 23

  fun generateTodayDiary() {
    if (_diaryUi.value.generating) return
    val date = JournalRepo.today()
    val records = JournalRepo.entry(date).records
    if (records.isEmpty()) {
      _diaryUi.value = DiaryUiState(error = "今日の記録がまだありません")
      return
    }
    _diaryUi.value = DiaryUiState(generating = true)
    viewModelScope.launch {
      try {
        val joined = records.joinToString("\n") { "${it.time}  ${it.text}" }
        val prompt =
            "以下はある人の今日一日の記録です。これをもとに、その日を振り返る日記を日本語で、" +
                "あたたかく簡潔に（200〜400字程度の文章で、箇条書きにしない）書いてください。\n\n" +
                "今日の記録:\n$joined"
        val summary = withContext(Dispatchers.IO) { GeminiTextClient.generate(prompt) }
        JournalRepo.setDiary(summary, date)
        _diaryUi.value = DiaryUiState()
      } catch (e: Exception) {
        _diaryUi.value = DiaryUiState(error = e.message ?: "生成に失敗しました")
      }
    }
  }
}
