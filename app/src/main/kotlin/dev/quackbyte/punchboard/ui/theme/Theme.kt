package dev.quackbyte.punchboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.quackbyte.punchboard.R

/**
 * The desktop's "time card" palette: warm card-stock paper and near-black
 * ink in light mode, a deep ink desk in dark mode, the brand cyan from the
 * icon and the signal orange of a running clock.
 */
@Immutable
data class PbColors(
    val isDark: Boolean,
    val background: Color,
    val foreground: Color,
    val card: Color,
    val popover: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val destructive: Color,
    val border: Color,
    val input: Color,
    val dot: Color,
    /** The punch clock is always "ink", in both themes. */
    val ink: Color,
    val inkForeground: Color,
    val inkMuted: Color,
    val inkBorder: Color,
    val brand: Color,
    val onBrand: Color,
    val signal: Color,
    val onSignal: Color,
    val vacation: Color,
    val sick: Color,
    val extra: Color,
    /** Readable text in each mark's colour on the card. */
    val vacationText: Color,
    val sickText: Color,
    val extraText: Color,
)

private val Brand = Color(0xFF20D3EE)
private val Signal = Color(0xFFFF7033)
private val Vacation = Color(0xFFF59F0A)
private val Sick = Color(0xFFEA3E5B)
private val Extra = Color(0xFF8951EC)

val LightPb = PbColors(
    isDark = false,
    background = Color(0xFFF1EFE9),
    foreground = Color(0xFF15181E),
    card = Color(0xFFFCFBF8),
    popover = Color(0xFFFDFDFC),
    primary = Color(0xFF0889A6),
    onPrimary = Color.White,
    secondary = Color(0xFFE7E2DA),
    muted = Color(0xFFE9E4DD),
    mutedForeground = Color(0xFF616670),
    accent = Color(0xFFE5E0D7),
    destructive = Color(0xFFDB2430),
    border = Color(0xFFD8D3CA),
    input = Color(0xFFCDC6BC),
    dot = Color(0xFFC6BFB3),
    ink = Color(0xFF12151C),
    inkForeground = Color(0xFFF4F1EC),
    inkMuted = Color(0xFF959BA7),
    inkBorder = Color(0xFF2A2E37),
    brand = Brand,
    onBrand = Color(0xFF052029),
    signal = Signal,
    onSignal = Color(0xFF2E1105),
    vacation = Vacation,
    sick = Sick,
    extra = Extra,
    vacationText = Color(0xFF9B5608),
    sickText = Color(0xFFB62039),
    extraText = Color(0xFF602EB8),
)

val DarkPb = PbColors(
    isDark = true,
    background = Color(0xFF0C0E12),
    foreground = Color(0xFFF1EEE9),
    card = Color(0xFF14171C),
    popover = Color(0xFF171A21),
    primary = Brand,
    onPrimary = Color(0xFF052029),
    secondary = Color(0xFF21242C),
    muted = Color(0xFF1F2229),
    mutedForeground = Color(0xFF9095A2),
    accent = Color(0xFF23272F),
    destructive = Color(0xFFDD3C47),
    border = Color(0xFF262931),
    input = Color(0xFF31353F),
    dot = Color(0xFF24272E),
    ink = Color(0xFF08090C),
    inkForeground = Color(0xFFF4F1EC),
    inkMuted = Color(0xFF959BA7),
    inkBorder = Color(0xFF23272F),
    brand = Brand,
    onBrand = Color(0xFF052029),
    signal = Signal,
    onSignal = Color(0xFF2E1105),
    vacation = Vacation,
    sick = Sick,
    extra = Extra,
    vacationText = Vacation,
    sickText = Sick,
    extraText = Color(0xFFA687F5),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Sans = FontFamily(
    variable(R.font.bricolage_grotesque, 400),
    variable(R.font.bricolage_grotesque, 500),
    variable(R.font.bricolage_grotesque, 600),
    variable(R.font.bricolage_grotesque, 700),
    variable(R.font.bricolage_grotesque, 800),
)

val Mono = FontFamily(
    variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 500),
    variable(R.font.jetbrains_mono, 600),
    variable(R.font.jetbrains_mono, 700),
)

/** Text styles the screens share. */
object PbType {
    /** Small mono uppercase label — the "stamp" on a time card. */
    val stamp = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.14.em)
    val mono = TextStyle(fontFamily = Mono, fontSize = 13.sp)
    val monoSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp)
    val body = TextStyle(fontFamily = Sans, fontSize = 15.sp)
    val bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp)
    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.01).em)
}

val LocalPb = staticCompositionLocalOf { DarkPb }

enum class ThemeMode { System, Light, Dark }

@Composable
fun PunchboardTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val pb = if (dark) DarkPb else LightPb
    val scheme = if (dark) {
        darkColorScheme(
            primary = pb.primary,
            onPrimary = pb.onPrimary,
            secondary = pb.primary,
            background = pb.background,
            onBackground = pb.foreground,
            surface = pb.card,
            onSurface = pb.foreground,
            surfaceVariant = pb.muted,
            onSurfaceVariant = pb.mutedForeground,
            surfaceContainer = pb.popover,
            surfaceContainerHigh = pb.popover,
            surfaceContainerHighest = pb.secondary,
            surfaceContainerLow = pb.card,
            outline = pb.input,
            outlineVariant = pb.border,
            error = pb.destructive,
            inverseSurface = pb.foreground,
            inverseOnSurface = pb.background,
            inversePrimary = pb.primary,
        )
    } else {
        lightColorScheme(
            primary = pb.primary,
            onPrimary = pb.onPrimary,
            secondary = pb.primary,
            background = pb.background,
            onBackground = pb.foreground,
            surface = pb.card,
            onSurface = pb.foreground,
            surfaceVariant = pb.muted,
            onSurfaceVariant = pb.mutedForeground,
            surfaceContainer = pb.popover,
            surfaceContainerHigh = pb.popover,
            surfaceContainerHighest = pb.secondary,
            surfaceContainerLow = pb.card,
            outline = pb.input,
            outlineVariant = pb.border,
            error = pb.destructive,
            inverseSurface = pb.foreground,
            inverseOnSurface = pb.background,
            inversePrimary = Brand,
        )
    }
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Sans),
        displayMedium = base.displayMedium.copy(fontFamily = Sans),
        displaySmall = base.displaySmall.copy(fontFamily = Sans),
        headlineLarge = base.headlineLarge.copy(fontFamily = Sans),
        headlineMedium = base.headlineMedium.copy(fontFamily = Sans),
        headlineSmall = base.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Sans),
        bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
        bodySmall = base.bodySmall.copy(fontFamily = Sans),
        labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = Sans),
        labelSmall = base.labelSmall.copy(fontFamily = Sans),
    )
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalPb provides pb) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
