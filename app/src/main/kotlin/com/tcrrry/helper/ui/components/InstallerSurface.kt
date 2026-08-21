package com.tcrrry.helper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerDimensions
import com.tcrrry.helper.ui.theme.InstallerMotion

@Composable
fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = InstallerDimensions.ListItemMinHeight,
    containerColor: Color = InstallerColors.WhiteSurface,
    pressedColor: Color = InstallerColors.PressedBlue,
    borderColor: Color = InstallerColors.WhiteBorder,
    shape: Shape = RoundedCornerShape(InstallerDimensions.SurfaceRadius),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = if (pressed) InstallerMotion.press() else InstallerMotion.release(),
        label = "pressScale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (pressed && enabled) pressedColor else containerColor,
        animationSpec = InstallerMotion.stateChange(),
        label = "pressSurface",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .scale(scale)
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(InstallerDimensions.DividerThickness, borderColor), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(contentPadding),
        content = {
            androidx.compose.foundation.layout.Row(content = content)
        },
    )
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = InstallerDimensions.DividerThickness)
            .background(InstallerColors.WhiteBorder),
    )
}
