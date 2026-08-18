package com.tcrrry.helper.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

object InstallerMotion {
    const val PageEnterDuration = 220
    const val PageExitDuration = 180
    const val ListItemDuration = 180
    const val StaggerDelay = 24
    const val PressDuration = 90
    const val ReleaseDuration = 160
    const val StateChangeDuration = 160
    const val ProgressDuration = 180

    val Easing: Easing = FastOutSlowInEasing

    fun <T> pageEnter(delayMillis: Int = 0): FiniteAnimationSpec<T> = tween(
        durationMillis = PageEnterDuration,
        delayMillis = delayMillis,
        easing = Easing,
    )

    fun <T> pageExit(): FiniteAnimationSpec<T> = tween(
        durationMillis = PageExitDuration,
        easing = Easing,
    )

    fun <T> listItem(index: Int): FiniteAnimationSpec<T> = tween(
        durationMillis = ListItemDuration,
        delayMillis = index * StaggerDelay,
        easing = Easing,
    )

    fun <T> press(): FiniteAnimationSpec<T> = tween(
        durationMillis = PressDuration,
        easing = Easing,
    )

    fun <T> release(): FiniteAnimationSpec<T> = tween(
        durationMillis = ReleaseDuration,
        easing = Easing,
    )

    fun <T> stateChange(): FiniteAnimationSpec<T> = tween(
        durationMillis = StateChangeDuration,
        easing = Easing,
    )

    fun <T> progress(): FiniteAnimationSpec<T> = tween(
        durationMillis = ProgressDuration,
        easing = Easing,
    )
}
