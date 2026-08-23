package com.ninepointnine.helper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val InstallerColorScheme = lightColorScheme(
    primary = InstallerColors.White,
    onPrimary = InstallerColors.PressedBlue,
    secondary = InstallerColors.AuxiliaryWhite,
    onSecondary = InstallerColors.PressedBlue,
    background = InstallerColors.PageBlue,
    onBackground = InstallerColors.White,
    surface = InstallerColors.WhiteSurface,
    onSurface = InstallerColors.White,
    surfaceVariant = InstallerColors.WhiteSurface,
    onSurfaceVariant = InstallerColors.AuxiliaryWhite,
    outline = InstallerColors.WhiteBorder,
)

private val InstallerTypography = Typography(
    titleLarge = TextStyle(
        fontSize = InstallerTextSizes.PageTitle,
        fontWeight = FontWeight.SemiBold,
        color = InstallerColors.White,
    ),
    headlineSmall = TextStyle(
        fontSize = InstallerTextSizes.SectionTitle,
        fontWeight = FontWeight.SemiBold,
        color = InstallerColors.White,
    ),
    bodyLarge = TextStyle(
        fontSize = InstallerTextSizes.Body,
        color = InstallerColors.White,
    ),
    bodyMedium = TextStyle(
        fontSize = InstallerTextSizes.Supporting,
        color = InstallerColors.AuxiliaryWhite,
    ),
    bodySmall = TextStyle(
        fontSize = InstallerTextSizes.Supporting,
        color = InstallerColors.AuxiliaryWhite,
    ),
)

private val InstallerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(InstallerDimensions.SurfaceRadius),
    medium = RoundedCornerShape(InstallerDimensions.SurfaceRadius),
    large = RoundedCornerShape(InstallerDimensions.SurfaceRadius),
    extraLarge = RoundedCornerShape(InstallerDimensions.SurfaceRadius),
)

@Composable
fun InstallerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InstallerColorScheme,
        typography = InstallerTypography,
        shapes = InstallerShapes,
        content = content,
    )
}
