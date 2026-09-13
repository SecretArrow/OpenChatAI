package com.openchatai.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchai.core.agent.PermissionDecision
import com.openchai.core.agent.PermissionRequest

/**
 * Dialog izin gaya Claude Code — Material Design 3 penuh: AlertDialog dengan
 * slot icon tonal (lingkaran container: Info biasa / WarningAmber bila tool
 * berbahaya), detail monospace dalam Surface tonal, dan chip kategori tool.
 *
 * Muncul bila [PermissionRequest] pending di
 * [com.openchai.core.agent.PermissionBroker] dan tool membutuhkan keputusan
 * user (matrix [com.openchai.core.agent.PermissionRule] → Action.ASK).
 *
 * Tiga jawaban:
 *  - Deny                      → model menerima "ERROR: user denied …"
 *  - Allow once                → tool ini dieksekusi sekali
 *  - Always allow (this run)   → tool sama tidak bertanya lagi untuk session ini
 *
 * Menutup dialog (tap luar / back) = Deny — agent tidak boleh menggantung
 * menunggu jawaban tanpa keputusan.
 */
@Composable
fun PermissionDialog(
    request: PermissionRequest,
    onDecision: (PermissionDecision) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDecision(PermissionDecision.Deny) },
        // Ikon tonal M3: lingkaran berisi ikon, warna mengikuti tingkat bahaya.
        icon = {
            Surface(
                shape = CircleShape,
                color = if (request.isDangerous) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                Icon(
                    imageVector = if (request.isDangerous) {
                        Icons.Rounded.WarningAmber
                    } else {
                        Icons.Rounded.Info
                    },
                    contentDescription = null,
                    tint = if (request.isDangerous) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp)
                )
            }
        },
        title = {
            Column {
                Text("Permission required")
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Detail permintaan (command line / path / nama tool MCP) — monospace
                // dalam Surface tonal shapes.extraSmall (8dp, skala bentuk M3).
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = request.detail,
                        style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Chip kategori tool (READ/WRITE/DELETE/COMMAND/MCP).
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(text = request.category.name)
                        }
                    )
                    if (request.isDangerous) {
                        // Badge merah untuk permintaan berbahaya (pil error).
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Text(
                                text = "Dangerous",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecision(PermissionDecision.AllowSession(request.toolName)) }
            ) {
                Text("Always allow (this run)")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onDecision(PermissionDecision.Deny) }) {
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { onDecision(PermissionDecision.AllowOnce) }) {
                    Text("Allow once")
                }
            }
        }
    )
}
