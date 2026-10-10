package com.ninepointnine.helper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.input.RemoteInputSnapshot
import com.ninepointnine.helper.ui.components.AnimatedEntry
import com.ninepointnine.helper.ui.components.PressableSurface
import com.ninepointnine.helper.ui.components.StatusIcon
import com.ninepointnine.helper.ui.components.TaskTopBar
import com.ninepointnine.helper.ui.components.TopLevelFloatingNotice
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions
import com.ninepointnine.helper.ui.theme.InstallerMotion
import kotlinx.coroutines.delay

@Composable
fun MaintenanceInstallationMenuPage(
    onRecommended: () -> Unit,
    onLocalApk: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_install_menu"),
        verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_install_menu),
            iconName = "folder_plus",
            onBack = onBack,
        )
        Text(
            text = stringResource(R.string.maintenance_install_menu_description),
            color = InstallerColors.AuxiliaryWhite,
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedEntry(visible = true, index = 0) {
                SourceChoiceButton(
                    title = stringResource(R.string.maintenance_install_recommended),
                    description = stringResource(R.string.maintenance_install_recommended_description),
                    iconName = "folder_plus",
                    onClick = onRecommended,
                    testTag = "maintenance_install_recommended",
                )
            }
            AnimatedEntry(visible = true, index = 1) {
                SourceChoiceButton(
                    title = stringResource(R.string.maintenance_install_local),
                    description = stringResource(R.string.maintenance_install_local_description),
                    iconName = "file_plus",
                    onClick = onLocalApk,
                    testTag = "maintenance_install_local",
                )
            }
        }
    }
}

@Composable
private fun SourceChoiceButton(
    title: String,
    description: String,
    iconName: String,
    onClick: () -> Unit,
    testTag: String,
) {
    PressableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        minHeight = 112.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(
                name = iconName,
                contentDescription = title,
                tint = InstallerColors.White,
                size = 28.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = InstallerColors.White, style = MaterialTheme.typography.headlineSmall)
                Text(description, color = InstallerColors.AuxiliaryWhite, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun RemoteInputPage(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
) {
    val remoteInput = state.remoteInput
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    val leavePage = {
        keyboardController?.hide()
        onBack()
    }
    BackHandler(enabled = true, onBack = leavePage)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { _ -> }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val editorMaxHeight = (maxHeight * 0.62f).coerceAtLeast(180.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
                .testTag("remote_input_page"),
        ) {
            TaskTopBar(
                title = stringResource(R.string.maintenance_input_text),
                iconName = "keyboard",
                onBack = leavePage,
            )
            RemoteInputTabs()
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.remote_input_description),
                    color = InstallerColors.AuxiliaryWhite,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = remoteInput.draft,
                    onValueChange = { text -> onIntent(InstallUiIntent.UpdateRemoteInputDraft(text)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 148.dp, max = editorMaxHeight)
                        .focusRequester(focusRequester)
                        .testTag("remote_input_editor"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = InstallerColors.White),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.remote_input_hint),
                            color = InstallerColors.AuxiliaryWhite,
                        )
                    },
                    label = { Text(stringResource(R.string.remote_input_label)) },
                    singleLine = false,
                    maxLines = Int.MAX_VALUE,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InstallerColors.White,
                        unfocusedTextColor = InstallerColors.White,
                        focusedBorderColor = InstallerColors.White,
                        unfocusedBorderColor = InstallerColors.WhiteBorder,
                        focusedLabelColor = InstallerColors.White,
                        unfocusedLabelColor = InstallerColors.AuxiliaryWhite,
                        cursorColor = InstallerColors.White,
                    ),
                )
                Text(
                    text = remoteInputStatusText(remoteInput),
                    color = if (remoteInput.canSend) InstallerColors.Success else InstallerColors.AuxiliaryWhite,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("remote_input_status"),
                )
            }
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            RemoteInputSendButton(
                input = remoteInput,
                onSend = { onIntent(InstallUiIntent.SendRemoteInputText) },
            )
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        }
    }
}

@Composable
private fun RemoteInputTabs() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_input_tabs"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PressableSurface(
            onClick = {},
            enabled = false,
            modifier = Modifier.weight(1f),
            minHeight = 40.dp,
            containerColor = InstallerColors.WhiteSurface,
            pressedColor = InstallerColors.WhiteSurface,
            borderColor = InstallerColors.White,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_input_tab_text),
                color = InstallerColors.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RemoteInputSendButton(
    input: RemoteInputSnapshot,
    onSend: () -> Unit,
) {
    val canSend = input.canSend && !input.sending
    val disabledSurface = Color(0x66FFFFFF)
    PressableSurface(
        onClick = onSend,
        enabled = !input.sending,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_input_send"),
        minHeight = InstallerDimensions.PrimaryActionHeight,
        containerColor = if (canSend) InstallerColors.White else disabledSurface,
        pressedColor = if (canSend) InstallerColors.AuxiliaryWhite else Color(0x80FFFFFF),
        borderColor = if (canSend) InstallerColors.White else InstallerColors.WhiteBorder,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (input.sending) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = InstallerColors.PressedBlue,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = stringResource(
                    if (input.sending) R.string.remote_input_sending else R.string.remote_input_send,
                ),
                color = if (canSend) InstallerColors.PressedBlue else InstallerColors.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = if (input.sending) 8.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun remoteInputStatusText(input: RemoteInputSnapshot): String {
    if (input.canSend) return stringResource(R.string.remote_input_ready)
    return stringResource(remoteInputReasonResource(input.statusReason))
}

internal fun remoteInputReasonResource(reason: String?): Int = when (reason) {
    "remote_input_disconnected" -> R.string.remote_input_status_disconnected
    "remote_input_syncing" -> R.string.remote_input_status_syncing
    "remote_input_keyboard_closed" -> R.string.remote_input_status_keyboard_closed
    "remote_input_unavailable",
    "remote_input_target_unavailable",
    -> R.string.remote_input_status_unavailable
    "remote_input_busy" -> R.string.remote_input_status_busy
    "remote_input_sending" -> R.string.remote_input_status_sending
    "remote_input_unknown_result" -> R.string.remote_input_status_unknown
    "remote_input_empty" -> R.string.remote_input_status_empty
    "remote_input_text_too_long" -> R.string.remote_input_status_too_long
    else -> R.string.remote_input_status_unavailable
}

@Composable
fun RemoteInputNotice(
    remoteInput: RemoteInputSnapshot,
    onDismiss: () -> Unit,
) {
    val notice = remoteInput.notice ?: return
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomInset = with(density) { maxOf(imeBottom, navigationBottom).toDp() }
    LaunchedEffect(remoteInput.noticeRevision) {
        delay(2400L)
        onDismiss()
    }
    TopLevelFloatingNotice(bottomInset = bottomInset) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(InstallerMotion.stateChange()) + slideInVertically(
                animationSpec = InstallerMotion.stateChange(),
                initialOffsetY = { height -> height / 2 },
            ),
            exit = fadeOut(InstallerMotion.stateChange()) + slideOutVertically(
                animationSpec = InstallerMotion.stateChange(),
                targetOffsetY = { height -> height / 2 },
            ),
        ) {
            Text(
                text = stringResource(remoteInputNoticeResource(notice)),
                color = InstallerColors.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .testTag("remote_input_notice")
                    .background(
                        color = Color(0xE61D232B),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

internal fun remoteInputNoticeResource(notice: String): Int = when (notice) {
    "remote_input_sent" -> R.string.remote_input_notice_sent
    "remote_input_sent_restore_failed" -> R.string.remote_input_notice_restore_failed
    "remote_input_send_failed" -> R.string.remote_input_notice_failed
    "remote_input_unknown_result" -> R.string.remote_input_notice_unknown
    "remote_input_text_too_long" -> R.string.remote_input_notice_too_long
    "remote_input_busy" -> R.string.remote_input_notice_busy
    "remote_input_request_invalid" -> R.string.remote_input_notice_failed
    "remote_input_empty" -> R.string.remote_input_status_empty
    "remote_input_keyboard_closed" -> R.string.remote_input_status_keyboard_closed
    else -> R.string.remote_input_notice_unavailable
}
