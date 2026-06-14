// Positive/negative sentiment bar. Takes positivity (0-100); negativity = 100 - positivity,
// so the two always sum to 100.

package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val PositiveColor = Color(0xFF4CAF50)
private val NegativeColor = Color(0xFFEF5350)

@Composable
fun SentimentBar(positivity: Int, modifier: Modifier = Modifier) {
  val pos = positivity.coerceIn(0, 100)
  val neg = 100 - pos

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
          "ポジティブ $pos%",
          color = PositiveColor,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.bodyMedium,
      )
      Text(
          "ネガティブ $neg%",
          color = NegativeColor,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.bodyMedium,
      )
    }
    Spacer(Modifier.height(6.dp))
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(NegativeColor),
    ) {
      // green = positive portion; remaining red track = negative portion.
      Box(modifier = Modifier.fillMaxWidth(pos / 100f).fillMaxHeight().background(PositiveColor))
    }
  }
}
