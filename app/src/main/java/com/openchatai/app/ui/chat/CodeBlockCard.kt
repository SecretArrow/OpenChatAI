package com.openchatai.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Palet kode lama (literal GitHub-dark) dihapus — kartu code block kini memakai
 * peran warna colorScheme Material Design 3 murni:
 *  - body  → surfaceContainerLowest (tonal paling rendah = "kertas kode")
 *  - header→ surfaceContainerHigh, label onSurfaceVariant
 *  - teks kode → onSurface (monospace)
 *  - garis tepi → outlineVariant
 * Sintaks-highlight (jika suatu saat diperlukan) tetap boleh memakai palet
 * kode terpisah sesuai kontrak — saat ini belum ada highlighter.
 */

/**
 * Kartu code block Material 3: header bar (language + Copy IconButton) di atas
 * surfaceContainerHigh dan body monospace di surfaceContainerLowest dengan
 * border outlineVariant. Long code dibatasi tingginya; baris panjang discroll
 * horizontal.
 */
@Composable
fun CodeBlockCard(language: String, code: String, modifier: Modifier = Modifier) {
    val fontScale = LocalChatFontScale.current
    val clipboard = LocalClipboardManager.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            // Header bar: label bahasa + tombol salin (ikon ContentCopy).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = (13f * fontScale).sp,
                lineHeight = (18f * fontScale).sp,
                color = MaterialTheme.colorScheme.onSurface,
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
