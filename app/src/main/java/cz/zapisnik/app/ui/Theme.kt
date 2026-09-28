package cz.zapisnik.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1B2430), onPrimary = Color.White,
    secondary = Color(0xFFE3A815), onSecondary = Color(0xFF1B2430),
    secondaryContainer = Color(0xFFE3A815), onSecondaryContainer = Color(0xFF1B2430),
    background = Color(0xFFEEF1F4), onBackground = Color(0xFF1B2430),
    surface = Color(0xFFEEF1F4), onSurface = Color(0xFF1B2430),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF6F8FA), surfaceContainerHigh = Color(0xFFE3E8ED),
    surfaceVariant = Color(0xFFE3E8ED), onSurfaceVariant = Color(0xFF56616F),
    outline = Color(0xFFB9C2CC), outlineVariant = Color(0xFFD3DAE1),
    error = Color(0xFFC2412D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE8EDF2), onPrimary = Color(0xFF11161C),
    secondary = Color(0xFFF0B92E), onSecondary = Color(0xFF11161C),
    secondaryContainer = Color(0xFFF0B92E), onSecondaryContainer = Color(0xFF11161C),
    background = Color(0xFF11161C), onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF11161C), onSurface = Color(0xFFE8EDF2),
    surfaceContainerLowest = Color(0xFF1A2129), surfaceContainerLow = Color(0xFF1A2129),
    surfaceContainer = Color(0xFF1D252E), surfaceContainerHigh = Color(0xFF242D37),
    surfaceVariant = Color(0xFF242D37), onSurfaceVariant = Color(0xFF9AA6B3),
    outline = Color(0xFF4A5663), outlineVariant = Color(0xFF2E3843),
    error = Color(0xFFEF6B55),
)

private val CategoryLight = listOf(0xFF2F6FD6, 0xFFC2412D, 0xFF3E8A5A, 0xFF8A57C7, 0xFFD08A12, 0xFF1F8A94, 0xFFB5487E, 0xFF6B7684)
private val CategoryDark = listOf(0xFF6FA0F0, 0xFFEF7B66, 0xFF6CC08A, 0xFFB38BEA, 0xFFF0B24A, 0xFF4FC0C9, 0xFFE27DAE, 0xFF9AA6B3)

@Composable
fun categoryColor(index: Int?): Color {
    val list = if (isSystemInDarkTheme()) CategoryDark else CategoryLight
    val i = index ?: 7
    return Color(list[((i % list.size) + list.size) % list.size])
}

@Composable
fun ZapisnikTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
