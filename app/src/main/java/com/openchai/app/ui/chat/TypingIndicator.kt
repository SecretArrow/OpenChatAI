package com.openchai.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Tiga titik kecil dengan alpha beranimasi (stagger) — tanda agent sedang mengetik. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 450,
                        delayMillis = 150 * index,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typingDot$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}
