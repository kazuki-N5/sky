// 履歴 (History): a calendar to pick a date, then that date's 記録 (records) and 日記 (diary).

package com.example.myapplication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.journal.DayEntry
import com.example.myapplication.journal.JournalViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: JournalViewModel, modifier: Modifier = Modifier) {
  val data by viewModel.data.collectAsStateWithLifecycle()
  val selected by viewModel.selectedDate.collectAsStateWithLifecycle()

  val todayMillis =
      remember { LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
  val datePickerState = rememberDatePickerState(initialSelectedDateMillis = todayMillis)

  LaunchedEffect(datePickerState.selectedDateMillis) {
    datePickerState.selectedDateMillis?.let { millis ->
      val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
      viewModel.selectDate(date)
    }
  }

  val entry: DayEntry = data[selected] ?: DayEntry()
  var expanded by remember { mutableStateOf("records") } // "records" | "diary" | ""

  Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    DatePicker(state = datePickerState, title = null, headline = null, showModeToggle = false)

    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          text = "$selected",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
      )
      Spacer(Modifier.height(8.dp))

      SectionCard(
          title = "記録 (${entry.records.size})",
          expanded = expanded == "records",
          onClick = { expanded = if (expanded == "records") "" else "records" },
      ) {
        if (entry.records.isEmpty()) {
          Text("記録はありません", style = MaterialTheme.typography.bodyMedium)
        } else {
          entry.records.forEach { r ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
              Text(
                  r.time,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(end = 10.dp),
              )
              Text(r.text)
            }
            HorizontalDivider()
          }
        }
      }

      SectionCard(
          title = "日記",
          expanded = expanded == "diary",
          onClick = { expanded = if (expanded == "diary") "" else "diary" },
      ) {
        Text(entry.diary ?: "まだ日記がありません（「日記」タブで生成できます）")
        entry.positivity?.let {
          Spacer(Modifier.height(12.dp))
          SentimentBar(it, modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

@Composable
private fun SectionCard(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = null,
        )
      }
      if (expanded) {
        Spacer(Modifier.height(8.dp))
        content()
      }
    }
  }
}
