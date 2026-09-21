package com.ninepointnine.helper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import com.ninepointnine.helper.R
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions
import com.ninepointnine.helper.ui.theme.InstallerMotion

@Composable
fun TaskTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    iconName: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(InstallerDimensions.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                InstallerIcon(
                    name = "arrow_left",
                    contentDescription = stringResource(R.string.navigation_back),
                    tint = InstallerColors.White,
                    size = 24.dp,
                )
            }
        }
        if (iconName != null) {
            InstallerIcon(
                name = iconName,
                contentDescription = title,
                tint = InstallerColors.White,
                size = 24.dp,
                modifier = Modifier.padding(start = if (onBack != null) 4.dp else 0.dp),
            )
        }
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = InstallerColors.White,
            modifier = Modifier.padding(start = 10.dp),
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
fun IconTextActionButton(
    text: String,
    iconName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = InstallerColors.White,
) {
    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = InstallerMotion.stateChange(),
        label = "iconTextActionEnabled",
    )
    PressableSurface(
        onClick = onClick,
        modifier = modifier.alpha(contentAlpha),
        enabled = enabled,
        minHeight = 48.dp,
        containerColor = InstallerColors.PageBlue,
        pressedColor = InstallerColors.PressedBlue,
        borderColor = InstallerColors.WhiteBorder,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(name = iconName, contentDescription = text, tint = iconTint, size = 20.dp)
            Text(
                text,
                color = InstallerColors.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The single host for transient bottom notices. A transparent, non-focusable
 * and non-touchable dialog window keeps the notice above business dialogs and
 * their scrims without stealing the user's action target.
 */
@Composable
fun TopLevelFloatingNotice(
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setDimAmount(0f)
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            }
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            content()
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
