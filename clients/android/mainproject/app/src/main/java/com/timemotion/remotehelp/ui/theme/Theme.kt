package com.timemotion.remotehelp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = LakePrimary,
    onPrimary = SandBackground,
    secondary = WarmAccent,
    onSecondary = SandBackground,
    tertiary = PaleBlue,
    background = SandBackground,
    onBackground = InkBlue,
    surface = MistSurface,
    onSurface = InkBlue
)

@Composable
fun RemotehelpTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
