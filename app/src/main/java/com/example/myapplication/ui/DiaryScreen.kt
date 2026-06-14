// 日記 (Diary): from 23:00, a button summarizes today's 記録 via Gemini and saves/shows the diary.

package com.example.myapplication.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.journal.JournalViewModel

@Composable
fun DiaryScreen(viewModel: JournalViewModel, modifier: Modifier = Modifier) {
  val data by viewModel.data.collectAsStateWithLifecycle()
  val diaryUi by viewModel.diaryUi.collectAsStateWithLifecycle()
  val today = viewModel.today()
  val todayDiary = data[today]?.diary
  val positivity = data[today]?.positivity
  val canGenerate = viewModel.canGenerateNow()

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("今日の日記", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        today,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    if (todayDiary != null) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            todayDiary,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
      }
      positivity?.let {
        Spacer(Modifier.height(12.dp))
        SentimentBar(it, modifier = Modifier.fillMaxWidth())
      }
      Spacer(Modifier.height(16.dp))
    } else {
      Text("まだ今日の日記はありません。", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(16.dp))
    }

    Button(
        onClick = { viewModel.generateTodayDiary() },
        enabled = canGenerate && !diaryUi.generating,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
          when {
            diaryUi.generating -> "生成中…"
            todayDiary != null -> "日記を再生成"
            else -> "今日の日記を生成"
          })
    }

    if (!canGenerate) {
      Spacer(Modifier.height(8.dp))
      Text(
          "※ 23時以降に生成できます",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (diaryUi.generating) {
      Spacer(Modifier.height(16.dp))
      CircularProgressIndicator()
    }
    diaryUi.error?.let {
      Spacer(Modifier.height(12.dp))
      Text(it, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodyMedium)
    }
  }
}
