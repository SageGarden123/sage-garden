import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SageGreen = Color(0xFF3A5A40)
val SageGreenDark = Color(0xFF233821)
val SageCream = Color(0xFFE3DDCF)

private val sageColorScheme = darkColorScheme(
    primary = SageGreen,
    secondary = SageGreenDark,
    surface = Color(0xFF1B1F1C),
    background = Color(0xFF151815)
)

@Composable
fun SageGardenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = sageColorScheme, content = content)
}
