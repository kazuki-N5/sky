// Bottom-tab shell: 履歴 (History) | LIVE | 日記 (Diary). LIVE hosts the existing glasses/Gemini UI.

package com.example.myapplication.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.journal.JournalViewModel
import com.example.myapplication.wearables.WearablesViewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

@Composable
fun RootScaffold(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
) {
  var tab by rememberSaveable { mutableIntStateOf(1) } // 0=履歴, 1=LIVE, 2=日記
  val journalViewModel: JournalViewModel = viewModel()

  Scaffold(
      bottomBar = {
        NavigationBar {
          NavigationBarItem(
              selected = tab == 0,
              onClick = { tab = 0 },
              icon = { Icon(Icons.Default.DateRange, contentDescription = "履歴") },
              label = { Text("履歴") },
          )
          NavigationBarItem(
              selected = tab == 1,
              onClick = { tab = 1 },
              icon = { Icon(Icons.Default.PlayArrow, contentDescription = "LIVE") },
              label = { Text("LIVE") },
          )
          NavigationBarItem(
              selected = tab == 2,
              onClick = { tab = 2 },
              icon = { Icon(Icons.Default.Edit, contentDescription = "日記") },
              label = { Text("日記") },
          )
        }
      },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (tab) {
        0 -> HistoryScreen(journalViewModel)
        1 -> GlassesApp(wearablesViewModel, onRequestWearablesPermission)
        else -> DiaryScreen(journalViewModel)
      }
    }
  }
}
