package com.openchatai.app.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CodeBackground = Color(0xFF0D1117)
private val CodeForeground = Color(0xFFE6EDF3)
private val CodeMuted = Color(0xFF8B949E)

/**
 * Kartu code block ala GitHub: header kecil (language + Copy) dan body monospace.
 * Long code dibatasi tingginya; baris panjang discroll horizontal.
 */
@Composable
fun CodeBlockCard(language: String, code: String, modifier: Modifier = Modifier) {
    val fontScale = LocalChatFontScale.current
    val clipboard = LocalClipboardManager.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CodeBackground
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    color = CodeMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Text(text = "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = (13f * fontScale).sp,
                lineHeight = (18f * fontScale).sp,
                color = CodeForeground,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
