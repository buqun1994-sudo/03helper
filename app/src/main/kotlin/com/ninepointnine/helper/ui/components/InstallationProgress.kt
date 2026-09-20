package com.ninepointnine.helper.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.UiProgress
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions
import com.ninepointnine.helper.ui.theme.InstallerMotion
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.roundToInt

@Composable
fun InstallationPhaseList(
    state: InstallUiState.Installing,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPhase) {
        val currentIndex = state.currentPhase.ordinal
        val currentItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        val fullyVisible = currentItem != null &&
            currentItem.offset >= listState.layoutInfo.viewportStartOffset &&
            currentItem.offset + currentItem.size <= listState.layoutInfo.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(currentIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
    ) {
        itemsIndexed(InstallPhase.entries, key = { _, phase -> phase }) { index, phase ->
            AnimatedEntry(visible = true, index = index) {
                InstallationPhaseRow(
                    label = stringResource(phase.labelResource()),
                    phase = phase,
                    state = state,
                    modifier = Modifier.testTag("$testTag.${phase.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun InstallationPhaseRow(
    label: String,
    phase: InstallPhase,
    state: InstallUiState.Installing,
    modifier: Modifier = Modifier,
) {
    val completed = phase in state.completedStages
    val current = phase == state.currentPhase
    val displayedProgress = if (current) {
        rememberDisplayedProgress(phase, state.progress)
    } else {
        if (completed) 1f else 0f
    }
    PressableSurface(
        onClick = {},
        modifier = modifier,
        enabled = false,
        minHeight = InstallerDimensions.ListItemMinHeight,
        containerColor = if (current) InstallerColors.WhiteSurface else Color.Transparent,
        borderColor = if (current) InstallerColors.WhiteBorder else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = when {
                    completed -> PhaseVisual.COMPLETED
                    current -> PhaseVisual.CURRENT
                    else -> PhaseVisual.PENDING
                },
                transitionSpec = {
                    (fadeIn(InstallerMotion.stateChange()) +
                        scaleIn(InstallerMotion.stateChange(), initialScale = 0.92f))
                        .togetherWith(
                            fadeOut(InstallerMotion.stateChange()) +
                                scaleOut(InstallerMotion.stateChange(), targetScale = 0.92f),
                        )
                },
                label = "phaseStatus",
            ) { visual ->
                StatusIcon(
                    name = when (visual) {
                        PhaseVisual.COMPLETED -> "circle_check"
                        PhaseVisual.CURRENT -> "loader_circle"
                        PhaseVisual.PENDING -> "circle"
                    },
                    contentDescription = label,
                    tint = if (visual == PhaseVisual.COMPLETED) InstallerColors.Success else InstallerColors.White,
                    size = InstallerDimensions.SmallIconSize,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current || completed) InstallerColors.White else InstallerColors.AuxiliaryWhite,
                )
                if (current) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { displayedProgress },
                            color = InstallerColors.White,
                            trackColor = InstallerColors.WhiteBorder,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                R.string.install_progress_percent,
                                (displayedProgress * 100f).roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = InstallerColors.White,
                            modifier = Modifier.width(44.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDisplayedProgress(
    phase: InstallPhase,
    progress: UiProgress,
): Float {
    val latestProgress by rememberUpdatedState(progress)
    val target by produceState(initialValue = 0f, key1 = phase) {
        val startedAt = System.nanoTime()
        while (true) {
            val latest = latestProgress
            val real = latest.fraction?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
            val completed = real == 1f && !latest.indeterminate
            val next = when {
                completed -> 1f
                real != null && !latest.indeterminate -> real.coerceAtMost(PREDICTED_PROGRESS_LIMIT)
                else -> maxOf(
                    real ?: 0f,
                    predictedInstallProgress((System.nanoTime() - startedAt) / 1_000_000L),
                ).coerceAtMost(PREDICTED_PROGRESS_LIMIT)
            }
            value = maxOf(value, next)
            if (completed) break
            delay(PROGRESS_TICK_MILLIS)
        }
    }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = InstallerMotion.progress(),
        label = "installationPhaseProgress",
    )
    return animated.coerceIn(0f, 1f)
}

internal fun predictedInstallProgress(elapsedMillis: Long): Float {
    val elapsed = elapsedMillis.coerceAtLeast(0L).toDouble()
    return when {
        elapsed <= FIRST_MILESTONE_MILLIS ->
            (FIRST_MILESTONE * elapsed / FIRST_MILESTONE_MILLIS).toFloat()
        elapsed <= SECOND_MILESTONE_MILLIS -> {
            val segment = (elapsed - FIRST_MILESTONE_MILLIS) /
                (SECOND_MILESTONE_MILLIS - FIRST_MILESTONE_MILLIS)
            (FIRST_MILESTONE + (SECOND_MILESTONE - FIRST_MILESTONE) * segment).toFloat()
        }
        else -> {
            val tail = elapsed - SECOND_MILESTONE_MILLIS
            (SECOND_MILESTONE + (PREDICTED_PROGRESS_LIMIT.toDouble() - SECOND_MILESTONE) *
                (1.0 - exp(-tail / TAIL_TIME_CONSTANT_MILLIS))).toFloat()
        }
    }.coerceIn(0f, PREDICTED_PROGRESS_LIMIT)
}

private fun InstallPhase.labelResource(): Int = when (this) {
    InstallPhase.FETCH -> R.string.phase_fetch
    InstallPhase.CHECK -> R.string.phase_check
    InstallPhase.SEND -> R.string.phase_send
    InstallPhase.INSTALL -> R.string.phase_install
    InstallPhase.CONFIGURE -> R.string.phase_configure
    InstallPhase.VERIFY -> R.string.phase_verify
}

private enum class PhaseVisual {
    COMPLETED,
    CURRENT,
    PENDING,
}

private const val PROGRESS_TICK_MILLIS = 50L
private const val FIRST_MILESTONE_MILLIS = 1_600.0
private const val SECOND_MILESTONE_MILLIS = 4_800.0
private const val TAIL_TIME_CONSTANT_MILLIS = 6_000.0
private const val FIRST_MILESTONE = 0.70
private const val SECOND_MILESTONE = 0.90
private const val PREDICTED_PROGRESS_LIMIT = 0.96f
