import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.File

// Same palettes as the Android app (ui/theme/Color.kt there) so the two feel like one product:
// warm neutral surfaces shared by every palette, only the accent family changes.

enum class ThemeMode(val label: String) { SYSTEM("Match my computer"), LIGHT("Light"), DARK("Dark") }
enum class AppPalette(val label: String) { SAGE("Sage"), TERRACOTTA("Terracotta"), OCEAN("Ocean"), LAVENDER("Lavender") }

/** Desktop look preferences, persisted beside the other desktop settings. */
object Appearance {
    private fun file() = File(System.getProperty("user.home"), "SageGardenDesktop/appearance.json")
    private fun read() = runCatching { JSONObject(file().readText()) }.getOrDefault(JSONObject())

    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == read().optString("themeMode") } ?: ThemeMode.SYSTEM)
        private set
    var palette by mutableStateOf(AppPalette.entries.firstOrNull { it.name == read().optString("palette") } ?: AppPalette.SAGE)
        private set

    fun update(mode: ThemeMode = themeMode, palette: AppPalette = this.palette) {
        themeMode = mode; this.palette = palette
        file().parentFile?.mkdirs()
        file().writeText(JSONObject().put("themeMode", mode.name).put("palette", palette.name).toString(2))
    }
}

private val lightNeutrals = lightColorScheme(
    background = Color(0xFFFBFAF6), onBackground = Color(0xFF1B1C18),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF1B1C18),
    surfaceVariant = Color(0xFFE3DDCF), onSurfaceVariant = Color(0xFF4A4739),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F4EE),
    surfaceContainer = Color(0xFFF1EEE6), surfaceContainerHigh = Color(0xFFEBE8DF), surfaceContainerHighest = Color(0xFFE5E2D9),
    outline = Color(0xFF7A7667), outlineVariant = Color(0xFFCBC6B5),
    error = Color(0xFFB23B3B), onError = Color.White, errorContainer = Color(0xFFFBE9E7), onErrorContainer = Color(0xFF5F1412),
)
private val darkNeutrals = darkColorScheme(
    background = Color(0xFF121410), onBackground = Color(0xFFE3E3DC),
    surface = Color(0xFF121410), onSurface = Color(0xFFE3E3DC),
    surfaceVariant = Color(0xFF45463A), onSurfaceVariant = Color(0xFFC7C7B8),
    surfaceContainerLowest = Color(0xFF0D0F0B), surfaceContainerLow = Color(0xFF1A1C18),
    surfaceContainer = Color(0xFF1E201C), surfaceContainerHigh = Color(0xFF282B26), surfaceContainerHighest = Color(0xFF333531),
    outline = Color(0xFF919283), outlineVariant = Color(0xFF45463A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private fun ColorScheme.accent(primary: List<Color>, tertiary: List<Color>, dark: Boolean) = copy(
    primary = primary[0], onPrimary = primary[1], primaryContainer = primary[2], onPrimaryContainer = primary[3],
    secondary = if (dark) Color(0xFFCFC5AF) else Color(0xFF6B5E45), onSecondary = if (dark) Color(0xFF362F1F) else Color.White,
    secondaryContainer = if (dark) Color(0xFF4D4533) else Color(0xFFE3DDCF), onSecondaryContainer = if (dark) Color(0xFFEDE2CB) else Color(0xFF2B2418),
    tertiary = tertiary[0], onTertiary = tertiary[1], tertiaryContainer = tertiary[2], onTertiaryContainer = tertiary[3],
)

private val sageLight = listOf(Color(0xFF3A5A40), Color.White, Color(0xFFD4E8D1), Color(0xFF233821))
private val sageDark = listOf(Color(0xFFA5D0A8), Color(0xFF0E3818), Color(0xFF2A4A30), Color(0xFFC8E8C9))
private val emberLight = listOf(Color(0xFFA8481F), Color.White, Color(0xFFFFDBCE), Color(0xFF380D00))
private val emberDark = listOf(Color(0xFFFFB59A), Color(0xFF5A1C00), Color(0xFF7A3014), Color(0xFFFFDBCE))

fun paletteScheme(palette: AppPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkNeutrals else lightNeutrals
    val sage = if (dark) sageDark else sageLight
    val ember = if (dark) emberDark else emberLight
    return when (palette) {
        AppPalette.SAGE -> base.accent(sage, ember, dark)
        AppPalette.TERRACOTTA -> base.accent(
            if (dark) listOf(Color(0xFFFFB693), Color(0xFF571E00), Color(0xFF7A2F0C), Color(0xFFFFDBCC))
            else listOf(Color(0xFF9A4521), Color.White, Color(0xFFFFDBCC), Color(0xFF3A0B00)), sage, dark)
        AppPalette.OCEAN -> base.accent(
            if (dark) listOf(Color(0xFF8ECFF0), Color(0xFF003549), Color(0xFF0B4D66), Color(0xFFC4E7FF))
            else listOf(Color(0xFF2C6A85), Color.White, Color(0xFFC4E7FF), Color(0xFF001E2C)), ember, dark)
        AppPalette.LAVENDER -> base.accent(
            if (dark) listOf(Color(0xFFD6BBFF), Color(0xFF3B2659), Color(0xFF523E75), Color(0xFFEEDCFF))
            else listOf(Color(0xFF6B568F), Color.White, Color(0xFFEEDCFF), Color(0xFF251140)), sage, dark)
    }
}

/** Colours Material has no slot for — the due-status trio (always paired with words, never colour alone). */
data class StatusColors(val urgent: Color, val soon: Color, val good: Color)
val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color(0xFFB23B3B), Color(0xFF8A5A00), Color(0xFF2E7D32)) }

@Composable
fun SageGardenTheme(content: @Composable () -> Unit) {
    val dark = when (Appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val status = if (dark) StatusColors(Color(0xFFFFB4AB), Color(0xFFFFB951), Color(0xFF8BD18F))
    else StatusColors(Color(0xFFB23B3B), Color(0xFF8A5A00), Color(0xFF2E7D32))
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = paletteScheme(Appearance.palette, dark), content = content)
    }
}
