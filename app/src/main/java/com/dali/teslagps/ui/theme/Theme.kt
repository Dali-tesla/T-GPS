package com.dali.teslagps.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// 검은색 중심의 다크 테마. 강조색은 청록 계열 네온(전기차/GPS 느낌).
val Background = Color(0xFF0A0A0D)
val Surface = Color(0xFF151519)
val SurfaceVariant = Color(0xFF1F1F26)
val SurfaceContainer = Color(0xFF1A1A20)

val Accent = Color(0xFF00E5C7)       // 네온 민트/청록 — 주요 액션
val AccentDim = Color(0xFF0B3B36)    // 강조색의 어두운 배경 버전(컨테이너)
val Danger = Color(0xFFFF5470)       // 경고/에러 — 6m 초과, 알림 배너

val OnDark = Color(0xFFEDEDF2)
val OnDarkMuted = Color(0xFF9A9AA5)

val TeslaGpsDarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201C),
    primaryContainer = AccentDim,
    onPrimaryContainer = Accent,

    secondary = Color(0xFF7C8CFF),
    onSecondary = Color(0xFF0A0A0D),

    tertiary = Danger,
    onTertiary = Color(0xFF1A0008),
    tertiaryContainer = Color(0xFF3A0E17),
    onTertiaryContainer = Danger,

    error = Danger,
    onError = Color(0xFF1A0008),

    background = Background,
    onBackground = OnDark,

    surface = Surface,
    onSurface = OnDark,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnDarkMuted,

    outline = Color(0xFF3A3A42),
)
