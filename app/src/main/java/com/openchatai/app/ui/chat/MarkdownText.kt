package com.openchatai.app.ui.chat

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

/**
 * Skala font chat (settings.chatFontScale) diteruskan ke CodeBlockCard via CompositionLocal,
 * karena signature kontrak CodeBlockCard tidak menyertakan parameter fontScale.
 */
val LocalChatFontScale = staticCompositionLocalOf { 1f }

private val FENCED_CODE = Regex("```(\\w*)\\n([\\s\\S]*?)```")

private sealed class MarkdownPart {
    data class Text(val text: String) : MarkdownPart()
    data class Code(val language: String, val code: String) : MarkdownPart()
}

/**
 * Render konten markdown chat. Konten dipisah berdasarkan fenced code block:
 * bagian teks dirender Markwon di AndroidView(TextView), bagian code → CodeBlockCard.
 */
@Composable
fun MarkdownText(content: String, fontScale: Float = 1f, modifier: Modifier = Modifier) {
    val parts = remember(content) { splitMarkdown(content) }
    CompositionLocalProvider(LocalChatFontScale provides fontScale) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEach { part ->
                when (part) {
                    is MarkdownPart.Text -> MarkdownParagraph(part.text, fontScale)
                    is MarkdownPart.Code -> CodeBlockCard(
                        language = part.language,
                        code = part.code,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(text: String, fontScale: Float) {
    val context = LocalContext.current
    // Warna & ukuran mengikuti token M3: onSurface + fontSize bodyLarge (16sp)
    // dikali skala font chat dari settings.
    val textColor = MaterialTheme.colorScheme.onSurface
    val bodyFontSize = MaterialTheme.typography.bodyLarge.fontSize.value * fontScale
    val markwon = remember(context) { Markwon.create(context) }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = android.graphics.Color.TRANSPARENT
                setTextColor(textColor.toArgb())
                textSize = bodyFontSize
                setLineSpacing(0f, 1.2f)
            }
        },
        update = { view ->
            view.setTextColor(textColor.toArgb())
            view.textSize = bodyFontSize
            markwon.setMarkdown(view, text)
        }
    )
}

private fun splitMarkdown(content: String): List<MarkdownPart> {
    if (content.isBlank()) return emptyList()
    // Saat streaming, fence pembuka bisa belum ditutup — tutup sementara agar tetap
    // dirender sebagai code block, bukan teks mentah berisi backticks.
    val openFences = content.split("```").size - 1
    val normalized = if (openFences % 2 == 1) "$content\n```" else content

    val parts = mutableListOf<MarkdownPart>()
    var lastIndex = 0
    for (match in FENCED_CODE.findAll(normalized)) {
        if (match.range.first > lastIndex) {
            parts += MarkdownPart.Text(normalized.substring(lastIndex, match.range.first))
        }
        parts += MarkdownPart.Code(match.groupValues[1], match.groupValues[2].removeSuffix("\n"))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < normalized.length) {
        parts += MarkdownPart.Text(normalized.substring(lastIndex))
    }
    return parts.mapNotNull { part ->
        when (part) {
            is MarkdownPart.Text -> {
                val trimmed = part.text.trim('\n', '\r')
                if (trimmed.isBlank()) null else MarkdownPart.Text(trimmed)
            }
            is MarkdownPart.Code -> part
        }
    }
}
