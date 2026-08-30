package `in`.yashd.bitchat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SignalSoft,
    onPrimary = DarkPaper,
    primaryContainer = ColorCompat.darkPrimaryContainer,
    onPrimaryContainer = DarkInk,
    secondary = DarkMutedInk,
    tertiary = AmberSoft,
    background = DarkPaper,
    onBackground = DarkInk,
    surface = DarkPanel,
    onSurface = DarkInk,
    surfaceVariant = DarkPanel,
    onSurfaceVariant = DarkMutedInk,
    outline = DarkMutedInk,
    outlineVariant = DarkLine
)

private val LightColorScheme = lightColorScheme(
    primary = Signal,
    onPrimary = Panel,
    primaryContainer = SignalSoft,
    onPrimaryContainer = Ink,
    secondary = MutedInk,
    tertiary = Amber,
    background = Paper,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = MutedInk,
    outline = MutedInk,
    outlineVariant = Line
)

private object ColorCompat {
    val darkPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1C3E38)
}

@Composable
fun BitchatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
