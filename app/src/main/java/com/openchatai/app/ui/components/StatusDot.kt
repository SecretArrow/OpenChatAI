package com.openchatai.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openchatai.app.ui.theme.AppMotion

/**
 * Titik status kecil (connected / unavailable / unknown).
 *
 * Kontrak publik TIDAK berubah (dipakai banyak layar):
 * [color] wajib, [size] default 8.dp, [modifier] default Modifier.
 *
 * Sentuhan Material 3: dot berdenyut halus (breathing) memakai durasi & easing
 * token [AppMotion] agar indikator terasa hidup — amplitudo kecil supaya tidak
 * mengganggu keterbacaan baris di sekitarnya.
 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    // Transisi tak berujung untuk denyut skala halus (0.9× ↔ 1.1×).
    val pulseTransition = rememberInfiniteTransition(label = "StatusDotPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = AppMotion.standardTween(AppMotion.MEDIUM_DURATION),
            repeatMode = RepeatMode.Reverse
        ),
        label = "StatusDotPulseScale"
    )
    Box(
        modifier = modifier
            .size(size)
            // scale setelah size: hanya efek gambar, tidak mengubah layout.
            .scale(pulse)
            .clip(CircleShape)
            .background(color)
    )
}
