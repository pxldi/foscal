package app.foscal.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import app.foscal.core.ui.R

// Both faces ship as variable fonts, so each weight is a variation instance of the same file
// rather than a separate resource. FontVariation applies on API 26+, which matches our minSdk.
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: FontWeight) = Font(
    resId = R.font.bricolage_grotesque_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun hanken(weight: FontWeight) = Font(
    resId = R.font.hanken_grotesque_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * Bricolage Grotesque — the characterful display face. Used sparingly for the app's "voice":
 * date numerals, the month header, screen titles, and the event-detail title.
 */
val BricolageFamily = FontFamily(
    bricolage(FontWeight.Normal),
    bricolage(FontWeight.Medium),
    bricolage(FontWeight.SemiBold),
    bricolage(FontWeight.Bold),
    bricolage(FontWeight.ExtraBold),
)

/** Hanken Grotesque — the workhorse UI/body face. */
val HankenFamily = FontFamily(
    hanken(FontWeight.Normal),
    hanken(FontWeight.Medium),
    hanken(FontWeight.SemiBold),
    hanken(FontWeight.Bold),
    hanken(FontWeight.ExtraBold),
)

/**
 * Display + headline styles carry the Bricolage voice; title/body/label default to Hanken.
 * Individual composables opt into [BricolageFamily] where a numeral or title should stand out.
 */
val FoscalTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = BricolageFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
        displayMedium = displayMedium.copy(fontFamily = BricolageFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
        displaySmall = displaySmall.copy(fontFamily = BricolageFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
        headlineLarge = headlineLarge.copy(fontFamily = BricolageFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
        headlineMedium = headlineMedium.copy(fontFamily = BricolageFamily, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = BricolageFamily, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = HankenFamily, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = HankenFamily),
        titleSmall = titleSmall.copy(fontFamily = HankenFamily),
        bodyLarge = bodyLarge.copy(fontFamily = HankenFamily),
        bodyMedium = bodyMedium.copy(fontFamily = HankenFamily),
        bodySmall = bodySmall.copy(fontFamily = HankenFamily),
        labelLarge = labelLarge.copy(fontFamily = HankenFamily, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = HankenFamily, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = HankenFamily, fontWeight = FontWeight.SemiBold),
    )
}
