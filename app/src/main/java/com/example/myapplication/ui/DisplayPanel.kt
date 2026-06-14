// Entry button + status for showing content on Ray-Ban Display glasses. Lives on the LIVE tab's
// connected screen and reuses the existing registration.

package com.example.myapplication.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.display.DisplayStatus
import com.example.myapplication.display.DisplayViewModel

@Composable
fun DisplayPanel(viewModel: DisplayViewModel = viewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val active =
      ui.status == DisplayStatus.CONNECTING ||
          ui.status == DisplayStatus.PREPARING ||
          ui.status == DisplayStatus.READY

  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Button(
        onClick = { if (active) viewModel.stop() else viewModel.startAndShow() },
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (active) "メガネ表示を停止" else "👓 メガネに表示")
    }
    ui.message?.let {
      Spacer(Modifier.height(4.dp))
      Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color =
              if (ui.status == DisplayStatus.ERROR) Color(0xFFD32F2F)
              else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
