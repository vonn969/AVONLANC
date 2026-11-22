package com.avonlan.launcher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

enum class AvonlanThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

@Composable
fun AvonlanTheme(
    mode: AvonlanThemeMode,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        AvonlanThemeMode.SYSTEM -> isSystemInDarkTheme()
        AvonlanThemeMode.DARK -> true
        AvonlanThemeMode.LIGHT -> false
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
