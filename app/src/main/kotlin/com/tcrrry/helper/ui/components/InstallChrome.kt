package com.tcrrry.helper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.tcrrry.helper.R
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerDimensions
import com.tcrrry.helper.ui.theme.InstallerMotion

@Composable
fun TaskTopBar(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(InstallerDimensions.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = InstallerColors.White,
        )
    }
}

@Composable
fun InstallStepIndicator(
    activeStep: Int,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(
        stringResource(R.string.step_connect),
        stringResource(R.string.step_select),
        stringResource(R.string.step_installing),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = labels.joinToString(" / ") },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val active = index <= activeStep
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(if (active) InstallerColors.White else InstallerColors.WhiteBorder, RoundedCornerShape(2.dp)),
                )
                Text(
                    text = label,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (active) InstallerColors.White else InstallerColors.AuxiliaryWhite,
                )
            }
        }
    }
}

@Composable
fun AnimatedEntry(
    visible: Boolean,
    modifier: Modifier = Modifier,
    index: Int = 0,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(InstallerMotion.listItem(index)) + slideInVertically(
            animationSpec = InstallerMotion.listItem(index),
            initialOffsetY = { with(density) { 8.dp.roundToPx() } },
        ),
        exit = fadeOut(tween(InstallerMotion.ListItemDuration, easing = InstallerMotion.Easing)) +
            slideOutVertically(
                animationSpec = tween(InstallerMotion.ListItemDuration, easing = InstallerMotion.Easing),
                targetOffsetY = { with(density) { -8.dp.roundToPx() } },
            ),
    ) {
        content()
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PressableSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = InstallerDimensions.PrimaryActionHeight,
        containerColor = InstallerColors.White,
        pressedColor = InstallerColors.AuxiliaryWhite,
        borderColor = InstallerColors.White,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = InstallerColors.PressedBlue,
            )
        }
    }
}

@Composable
fun StatusIcon(
    name: String,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = InstallerDimensions.IconSize,
) {
    InstallerIcon(
        name = name,
        contentDescription = contentDescription,
        tint = tint,
        size = size,
        modifier = modifier,
    )
}
