package com.openchatai.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchai.core.model.AgentStep
import com.openchai.core.model.StepState

private val StepDoneGreen = Color(0xFF3FB950)

/**
 * Kartu aktivitas agent di chat: daftar langkah berjalan/selesai/gagal.
 * Header menampilkan "🤖 Working on your project…" hanya saat ada step RUNNING && isLive.
 */
@Composable
fun AgentActivityCard(steps: List<AgentStep>, isLive: Boolean, modifier: Modifier = Modifier) {
    val hasRunning = steps.any { it.state == StepState.RUNNING }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasRunning && isLive) "🤖 Working on your project…" else "Agent activity",
                style = MaterialTheme.typography.titleMedium
            )
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (step.state) {
                        StepState.DONE -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Done",
                            tint = StepDoneGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        StepState.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        StepState.FAILED -> Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = step.label, style = MaterialTheme.typography.bodyMedium)
                        step.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
