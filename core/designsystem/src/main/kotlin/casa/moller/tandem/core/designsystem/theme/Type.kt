package casa.moller.tandem.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import casa.moller.tandem.core.designsystem.R

/*
 * Bundled variable fonts (minSdk 34 >= 26, so variation settings always apply):
 * - Bricolage Grotesque: display and headline ("karaktärsfull, lite redaktionell").
 * - DM Sans: title, body and label.
 * One Font entry per weight that the type scale uses; each one sets the wght axis explicitly so
 * the platform never synthesises bold. Licenses: assets/licenses/ (SIL OFL 1.1).
 */

private fun bricolage(weight: FontWeight, opticalSize: Float) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // opsz 12..96: larger values tighten spacing and sharpen details for big headings.
        FontVariation.Setting("opsz", opticalSize),
    ),
)

private fun dmSans(weight: FontWeight) = Font(
    resId = R.font.dm_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // opsz 9..40; 14 is the text cut, tuned for 12-20 sp body copy.
        FontVariation.Setting("opsz", 14f),
    ),
)

/** Bricolage Grotesque, for display and headline styles. */
val BricolageGrotesque: FontFamily = FontFamily(
    bricolage(FontWeight.Medium, opticalSize = 48f),
    bricolage(FontWeight.SemiBold, opticalSize = 48f),
    bricolage(FontWeight.Bold, opticalSize = 48f),
    bricolage(FontWeight.ExtraBold, opticalSize = 48f),
)

/** DM Sans, for title, body and label styles. */
val DmSans: FontFamily = FontFamily(
    dmSans(FontWeight.Normal),
    dmSans(FontWeight.Medium),
    dmSans(FontWeight.SemiBold),
    dmSans(FontWeight.Bold),
)

private fun TextStyle.display(weight: FontWeight) = copy(fontFamily = BricolageGrotesque, fontWeight = weight)

private fun TextStyle.text(weight: FontWeight) = copy(fontFamily = DmSans, fontWeight = weight)

/**
 * Material 3 type scale with Tandem's fonts. Sizes follow the M3 baseline; headings use a
 * slightly tighter letter spacing because Bricolage is wide at large sizes. Emphasized styles
 * (M3 Expressive) are one weight step heavier.
 */
internal val TandemTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.display(FontWeight.Bold).copy(letterSpacing = (-0.02).em),
        displayMedium = base.displayMedium.display(FontWeight.Bold).copy(letterSpacing = (-0.02).em),
        displaySmall = base.displaySmall.display(FontWeight.Bold).copy(letterSpacing = (-0.01).em),
        headlineLarge = base.headlineLarge.display(FontWeight.Bold).copy(letterSpacing = (-0.01).em),
        headlineMedium = base.headlineMedium.display(FontWeight.Bold),
        headlineSmall = base.headlineSmall.display(FontWeight.SemiBold),
        titleLarge = base.titleLarge.text(FontWeight.SemiBold),
        titleMedium = base.titleMedium.text(FontWeight.SemiBold),
        titleSmall = base.titleSmall.text(FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.text(FontWeight.Normal),
        bodyMedium = base.bodyMedium.text(FontWeight.Normal),
        bodySmall = base.bodySmall.text(FontWeight.Normal),
        labelLarge = base.labelLarge.text(FontWeight.SemiBold),
        labelMedium = base.labelMedium.text(FontWeight.SemiBold),
        labelSmall = base.labelSmall.text(FontWeight.Medium),
        displayLargeEmphasized = base.displayLargeEmphasized.display(FontWeight.ExtraBold),
        displayMediumEmphasized = base.displayMediumEmphasized.display(FontWeight.ExtraBold),
        displaySmallEmphasized = base.displaySmallEmphasized.display(FontWeight.ExtraBold),
        headlineLargeEmphasized = base.headlineLargeEmphasized.display(FontWeight.ExtraBold),
        headlineMediumEmphasized = base.headlineMediumEmphasized.display(FontWeight.ExtraBold),
        headlineSmallEmphasized = base.headlineSmallEmphasized.display(FontWeight.Bold),
        titleLargeEmphasized = base.titleLargeEmphasized.text(FontWeight.Bold),
        titleMediumEmphasized = base.titleMediumEmphasized.text(FontWeight.Bold),
        titleSmallEmphasized = base.titleSmallEmphasized.text(FontWeight.Bold),
        bodyLargeEmphasized = base.bodyLargeEmphasized.text(FontWeight.Medium),
        bodyMediumEmphasized = base.bodyMediumEmphasized.text(FontWeight.Medium),
        bodySmallEmphasized = base.bodySmallEmphasized.text(FontWeight.Medium),
        labelLargeEmphasized = base.labelLargeEmphasized.text(FontWeight.Bold),
        labelMediumEmphasized = base.labelMediumEmphasized.text(FontWeight.Bold),
        labelSmallEmphasized = base.labelSmallEmphasized.text(FontWeight.SemiBold),
    )
}

/**
 * Styles outside the M3 scale. Read them with `TandemTheme.extendedTypography`.
 *
 * - [cookStep]: step text in cooking mode, 28 sp (design: 28-32 px, plan 6.4).
 * - [overline]: small upper-case labels like "STEG 2 AV 3" or "TORSDAG 24 OKTOBER".
 */
@Immutable
data class TandemExtendedTypography(val cookStep: TextStyle, val overline: TextStyle) {
    /**
     * The step text exactly as cook mode draws it: [cookStep] times the A−/A+ [scale]
     * (`CookTextScale`), and on [wide] windows (840 dp and up, plan 6.5 "extra stor text som
     * standard") 34 sp instead of 28 sp before scaling. Settings → Appearance previews the size
     * with the same style, so what the user picks is what cook mode shows.
     */
    fun cookStepScaled(scale: Float, wide: Boolean = false): TextStyle {
        val factor = scale * (if (wide) WIDE_COOK_STEP_FACTOR else 1f)
        return cookStep.copy(fontSize = cookStep.fontSize * factor, lineHeight = cookStep.lineHeight * factor)
    }

    private companion object {
        /** 34 sp instead of 28 sp. */
        const val WIDE_COOK_STEP_FACTOR = 34f / 28f
    }
}

internal val TandemExtendedTypographyDefault = TandemExtendedTypography(
    cookStep = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    ),
    overline = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.em,
    ),
)
