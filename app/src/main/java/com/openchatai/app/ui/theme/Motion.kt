package com.openchatai.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

/*
 * Token gerak (motion) Material Design 3: durasi & kurva easing standar.
 * Semua animasi UI (transisi layar, muncul/hilang elemen, perubahan ukuran)
 * memakai token di sini agar gerakan konsisten dan terasa "M3".
 */
object AppMotion {
    // Kurva easing M3
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // Durasi M3 (ms)
    const val SHORT_DURATION = 150
    const val MEDIUM_DURATION = 300
    const val LONG_DURATION = 500

    /** Tween emphasized — untuk transisi elemen yang menonjol (sheet, FAB). */
    fun <T> emphasizedTween(durationMillis: Int = MEDIUM_DURATION) =
        tween<T>(durationMillis = durationMillis, easing = EmphasizedEasing)

    /** Tween standar — animasi umum (fade, warna, ukuran). */
    fun <T> standardTween(durationMillis: Int = MEDIUM_DURATION) =
        tween<T>(durationMillis = durationMillis, easing = StandardEasing)

    /** Tween decelerate — elemen masuk dari luar layar. */
    fun <T> decelerateTween(durationMillis: Int = MEDIUM_DURATION) =
        tween<T>(durationMillis = durationMillis, easing = EmphasizedDecelerateEasing)

    /** Tween linear — indikator progres tak tentu (indeterminate). */
    fun <T> linearTween(durationMillis: Int = MEDIUM_DURATION) =
        tween<T>(durationMillis = durationMillis, easing = LinearEasing)
}
