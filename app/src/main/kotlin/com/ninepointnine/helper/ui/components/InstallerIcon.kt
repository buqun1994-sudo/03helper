package com.ninepointnine.helper.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.ninepointnine.helper.R

/** Icons decoded from verified APKs by the application composition root. */
val LocalApkIcons = staticCompositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }

/** Resolves the bundled Lucide Android drawable without exposing its generated package to UI code. */
@Composable
fun InstallerIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
) {
    val drawableId = remember(name) {
        lucideDrawable(name)
    }
    val sizedModifier = modifier.size(size)
    if (drawableId != 0) {
        Icon(
            painter = painterResource(drawableId),
            contentDescription = contentDescription,
            tint = tint,
            modifier = sizedModifier,
        )
    } else {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = contentDescription,
            tint = tint,
            modifier = sizedModifier,
        )
    }
}

/** Renders the APK-owned icon when one is locally available, otherwise a neutral placeholder. */
@Composable
fun ComponentLogo(
    iconKey: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val apkIcon = LocalApkIcons.current[iconKey]
    if (apkIcon != null) {
        Image(
            bitmap = apkIcon,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else if (bundledLogo(iconKey) != 0) {
        Image(
            painter = painterResource(bundledLogo(iconKey)),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        InstallerIcon(
            name = componentFallbackIcon(iconKey),
            contentDescription = contentDescription,
            modifier = modifier,
            tint = Color.White,
            size = size,
        )
    }
}

/** Stable product artwork is available synchronously before any cloud request. */
private fun bundledLogo(iconKey: String): Int = when (iconKey) {
    "03helper", "helper" -> LucideR.drawable.lucide_ic_package
    "desktop" -> R.drawable.desktop_logo
    "lyrics" -> R.drawable.lyrics_logo
    "cast" -> R.drawable.cast_logo
    "file-manager" -> R.drawable.file_manager_logo
    else -> 0
}

private fun componentFallbackIcon(iconKey: String): String = when (iconKey) {
    "03helper", "helper" -> "car_front"
    else -> "package_x"
}

private fun lucideDrawable(name: String): Int = when (name) {
    "arrow_left" -> LucideR.drawable.lucide_ic_arrow_left
    "car_front" -> LucideR.drawable.lucide_ic_car_front
    "chevron_right" -> LucideR.drawable.lucide_ic_chevron_right
    "circle" -> LucideR.drawable.lucide_ic_circle
    "circle_alert" -> LucideR.drawable.lucide_ic_circle_alert
    "circle_check" -> LucideR.drawable.lucide_ic_circle_check
    "circle_pause" -> LucideR.drawable.lucide_ic_circle_pause
    "cloud_off" -> LucideR.drawable.lucide_ic_cloud_off
    "download" -> LucideR.drawable.lucide_ic_download
    "file_down" -> LucideR.drawable.lucide_ic_file_down
    "folder_plus" -> LucideR.drawable.lucide_ic_folder_plus
    "layout_grid" -> LucideR.drawable.lucide_ic_layout_grid
    "loader_circle" -> LucideR.drawable.lucide_ic_loader_circle
    "lock_keyhole" -> LucideR.drawable.lucide_ic_lock_keyhole
    "music_2" -> LucideR.drawable.lucide_ic_music_2
    "package_x" -> LucideR.drawable.lucide_ic_package_x
    "panels_top_left" -> LucideR.drawable.lucide_ic_panels_top_left
    "play" -> LucideR.drawable.lucide_ic_play
    "refresh_cw" -> LucideR.drawable.lucide_ic_refresh_cw
    "search" -> LucideR.drawable.lucide_ic_search
    "settings_2" -> LucideR.drawable.lucide_ic_settings_2
    "trash_2" -> LucideR.drawable.lucide_ic_trash_2
    "triangle_alert" -> LucideR.drawable.lucide_ic_triangle_alert
    "square" -> LucideR.drawable.lucide_ic_square
    "info" -> LucideR.drawable.lucide_ic_info
    "wifi_off" -> LucideR.drawable.lucide_ic_wifi_off
    "wrench" -> LucideR.drawable.lucide_ic_wrench
    else -> 0
}
