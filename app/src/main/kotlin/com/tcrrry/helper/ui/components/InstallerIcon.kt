package com.tcrrry.helper.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR

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

private fun lucideDrawable(name: String): Int = when (name) {
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
    "refresh_cw" -> LucideR.drawable.lucide_ic_refresh_cw
    "search" -> LucideR.drawable.lucide_ic_search
    "settings_2" -> LucideR.drawable.lucide_ic_settings_2
    "trash_2" -> LucideR.drawable.lucide_ic_trash_2
    "triangle_alert" -> LucideR.drawable.lucide_ic_triangle_alert
    "wifi_off" -> LucideR.drawable.lucide_ic_wifi_off
    "wrench" -> LucideR.drawable.lucide_ic_wrench
    else -> 0
}
