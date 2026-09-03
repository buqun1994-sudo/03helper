package com.ninepointnine.helper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.session.ComponentResultStatus
import com.ninepointnine.helper.ui.state.ComponentResultRow
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions

/**
 * The only user-facing projection of one installation result. Raw installed,
 * authorization and availability booleans remain domain evidence and are not
 * rendered as a diagnostic checklist.
 */
@Composable
fun InstallationResultRow(
    result: ComponentResultRow,
    modifier: Modifier = Modifier,
) {
    val (label, icon, tint) = when (result.status) {
        ComponentResultStatus.READY -> Triple(
            stringResource(R.string.result_row_success),
            "circle_check",
            InstallerColors.Success,
        )

        ComponentResultStatus.AUTHORIZATION_INCOMPLETE -> Triple(
            stringResource(R.string.result_row_authorization_incomplete),
            "circle_alert",
            InstallerColors.Warning,
        )

        ComponentResultStatus.AVAILABILITY_INCOMPLETE -> Triple(
            stringResource(R.string.result_row_availability_incomplete),
            "circle_alert",
            InstallerColors.Warning,
        )

        ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION -> Triple(
            stringResource(R.string.result_row_pending_confirmation),
            "circle_alert",
            InstallerColors.Warning,
        )

        ComponentResultStatus.NOT_INSTALLED -> Triple(
            result.errorReason?.let { reason ->
                stringResource(R.string.result_row_failure_with_reason, reason)
            } ?: stringResource(R.string.result_row_failure),
            "triangle_alert",
            InstallerColors.Error,
        )
    }

    PressableSurface(
        onClick = {},
        enabled = false,
        modifier = modifier,
        containerColor = InstallerColors.WhiteSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = result.componentName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = InstallerColors.White,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint,
                )
                // Keep the stage label compact, but expose the structured
                // reason underneath it for post-install failures. A user
                // should not have to infer which authorization or
                // availability check failed from the stage name alone.
                if (
                    result.status != ComponentResultStatus.NOT_INSTALLED &&
                        !result.errorReason.isNullOrBlank()
                ) {
                    Text(
                        text = result.errorReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = InstallerColors.AuxiliaryWhite,
                    )
                }
            }
            StatusIcon(
                name = icon,
                contentDescription = label,
                tint = tint,
                size = InstallerDimensions.SmallIconSize,
            )
        }
    }
}
