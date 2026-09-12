package com.openchatai.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

private const val MAX_ATTACHMENT_BYTES = 50 * 1024

/**
 * Input bar chat modern: attach file (MIME teks), field multi-line, dan tombol Send/Stop.
 * Saat isGenerating tombol kanan berubah menjadi Close (Stop).
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val content = readTextAttachment(context, uri, MAX_ATTACHMENT_BYTES)
            if (content != null) {
                val name = queryDisplayName(context, uri)
                onTextChange(text + "\n\n```$name\n$content\n```\n")
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { pickFile.launch("text/*") }) {
                Icon(Icons.Filled.Add, contentDescription = "Attach file")
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Open Chat AI…") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (text.isNotBlank() && !isGenerating) onSend() }
                )
            )
            if (isGenerating) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop generating")
                }
            } else {
                IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** Baca file teks dari contentResolver, dibatasi maxBytes (hasil diberi catatan bila terpotong). */
private fun readTextAttachment(context: Context, uri: Uri, maxBytes: Int): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            var truncated = false
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (total + read > maxBytes) {
                    out.write(buffer, 0, maxBytes - total)
                    truncated = true
                    break
                }
                out.write(buffer, 0, read)
                total += read
            }
            val body = out.toString("UTF-8")
            if (truncated) "$body\n… (truncated at 50KB)" else body
        }
    } catch (_: Exception) {
        null
    }
}

/** Ambil nama tampilan file dari content provider; fallback ke lastPathSegment. */
private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment.txt"
    try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
    } catch (_: Exception) {
        // Pertahankan fallback name.
    }
    return name
}
