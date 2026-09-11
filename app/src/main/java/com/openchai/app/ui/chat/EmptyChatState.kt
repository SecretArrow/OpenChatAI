package com.openchai.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val SUGGESTIONS = listOf(
    "Create a modern login page",
    "Build a Node.js REST API",
    "Fix a bug in my project"
)

/** Tampilan saat percakapan masih kosong: brand, tagline, dan chip saran prompt. */
@Composable
fun EmptyChatState(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(modifier = Modifier.padding(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(text = "Open Chat AI", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your AI coding agent — ask anything, attach files, " +
                "and watch it work on your project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        SUGGESTIONS.forEach { suggestion ->
            SuggestionChip(
                onClick = { onSuggestion(suggestion) },
                label = { Text(suggestion) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}
