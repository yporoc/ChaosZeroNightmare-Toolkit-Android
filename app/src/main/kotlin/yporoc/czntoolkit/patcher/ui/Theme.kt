package yporoc.czntoolkit.patcher.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF006A60)
private val TealDark = Color(0xFF4FDAC7)

private val LightScheme = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A635D),
    tertiary = Color(0xFF50626E),
)

private val DarkScheme = darkColorScheme(
    primary = TealDark,
    secondary = Color(0xFFB2CCC3),
    tertiary = Color(0xFFB8CADD),
)

/** Material 3 主题：Android 12+ 动态取色，低版本回退到内置配色；深浅色自适应。 */
@Composable
fun CznTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
