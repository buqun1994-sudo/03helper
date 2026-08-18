package com.tcrrry.helper.ui.state

import com.tcrrry.helper.domain.session.ComponentDescriptor
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.DeviceSummary
import com.tcrrry.helper.domain.session.FailureCategory
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
import com.tcrrry.helper.domain.session.ResultKind
import com.tcrrry.helper.domain.session.SessionFailure
import com.tcrrry.helper.domain.session.SessionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallUiStateMapperTest {
    @Test
    fun `idle and discovery map to one connection screen`() {
        val idle = InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.IDLE))
        val searching = InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.DISCOVERING))
        val found = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.DISCOVERING,
                discoveredDevices = listOf(device),
            ),
        )
        val connecting = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTING,
                device = device.copy(connectionStatus = DeviceConnectionStatus.CONNECTING),
                discoveredDevices = listOf(device.copy(connectionStatus = DeviceConnectionStatus.CONNECTING)),
            ),
        )

        assertEquals(ConnectionVariant.NOT_FOUND, (idle as InstallUiState.Connection).variant)
        assertEquals(ConnectionVariant.SEARCHING, (searching as InstallUiState.Connection).variant)
        assertEquals(ConnectionVariant.FOUND, (found as InstallUiState.Connection).variant)
        assertEquals(ConnectionVariant.CONNECTING, (connecting as InstallUiState.Connection).variant)
        assertEquals("icar-03", found.devices.single().id)
    }

    @Test
    fun `selection locks required components and gates missing selected size`() {
        val ready = InstallUiStateMapper.map(
            selectionSnapshot(
                listOf(
                    component("lyrics", required = true, size = "18 MB"),
                    component("desktop", required = true, size = "12 MB"),
                    component("files", required = false, size = null),
                ),
            ),
        ) as InstallUiState.Selection

        assertTrue(ready.components[0].selected)
        assertTrue(ready.components[1].selected)
        assertFalse(ready.components[2].selected)
        assertEquals(2, ready.summaryCount)
        assertEquals("18 MB + 12 MB", ready.summarySizeLabel)
        assertTrue(ready.canStart)

        val blocked = InstallUiStateMapper.map(
            selectionSnapshot(
                components = ready.components.map {
                    ComponentDescriptor(it.id, it.displayName, it.required, it.versionLabel, it.sizeLabel, it.compatibilityLabel)
                },
                selectedOptionalIds = setOf("files"),
            ),
        ) as InstallUiState.Selection

        assertFalse(blocked.canStart)
    }

    @Test
    fun `installation state maps phase completion and bounded progress`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.AUTHORIZING,
                device = device,
                currentComponentName = "03歌词",
                progress = SessionProgress(completedCount = 1, totalCount = 3, fraction = 1.4f),
            ),
        ) as InstallUiState.Installing

        assertEquals(InstallPhase.CONFIGURE, state.currentPhase)
        assertEquals(setOf(InstallPhase.FETCH, InstallPhase.CHECK, InstallPhase.SEND), state.completedStages)
        assertEquals(1f, state.progress.fraction)
    }

    @Test
    fun `failure and maintenance remain structured states`() {
        val failed = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.FAILED,
                failure = SessionFailure(FailureCategory.CONFIGURATION),
            ),
        ) as InstallUiState.Result
        val maintenance = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
            ),
        ) as InstallUiState.Maintenance

        assertEquals(ResultKind.CONFIGURATION_FAILED, failed.kind)
        assertTrue(maintenance.connected)
        assertEquals("iCAR 03", maintenance.deviceName)
    }

    @Test
    fun `connected catalog failure remains an unavailable selection state`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTED,
                device = device,
                failure = SessionFailure(
                    category = FailureCategory.VERIFICATION,
                    retryable = false,
                    reasonCode = "catalog_android_profile_missing",
                ),
            ),
        ) as InstallUiState.Selection

        assertTrue(state.components.isEmpty())
        assertFalse(state.canStart)
    }

    @Test
    fun `every internal state maps to one of the five stable screens`() {
        val installingStates = listOf(
            InstallationSessionState.SELECTION_CONFIRMED,
            InstallationSessionState.RESOLVING_SOURCE,
            InstallationSessionState.DOWNLOADING_ARCHIVE,
            InstallationSessionState.VERIFYING_ARCHIVE,
            InstallationSessionState.EXTRACTING_APK,
            InstallationSessionState.VERIFYING_ARTIFACTS,
            InstallationSessionState.INSTALLING,
            InstallationSessionState.AUTHORIZING,
            InstallationSessionState.VERIFYING_DEVICE,
        )
        installingStates.forEach { state ->
            assertTrue(
                "Expected installing projection for $state",
                InstallUiStateMapper.map(
                    InstallationSessionSnapshot(state = state, device = device),
                ) is InstallUiState.Installing,
            )
        }
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.SUCCEEDED)) is InstallUiState.Result)
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.PAUSED)) is InstallUiState.Result)
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.FAILED)) is InstallUiState.Result)
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.MAINTENANCE)) is InstallUiState.Maintenance)
    }

    @Test
    fun `selection start requires confirmed device and complete metadata`() {
        val unconfirmed = InstallUiStateMapper.map(
            selectionSnapshot(
                components = listOf(
                    component("lyrics", required = true, size = "18 MB"),
                    component("desktop", required = true, size = "12 MB"),
                ).map { it.copy(compatibilityLabel = null) },
            ).copy(device = device.copy(connectionStatus = DeviceConnectionStatus.CONNECTING)),
        ) as InstallUiState.Selection
        assertFalse(unconfirmed.canStart)

        val incomplete = InstallUiStateMapper.map(
            selectionSnapshot(
                components = listOf(
                    component("lyrics", required = true, size = "18 MB"),
                    component("desktop", required = true, size = "12 MB"),
                ).map { it.copy(versionLabel = null) },
            ),
        ) as InstallUiState.Selection
        assertFalse(incomplete.canStart)
    }

    private fun selectionSnapshot(
        components: List<ComponentDescriptor>,
        selectedOptionalIds: Set<String> = emptySet(),
    ) = InstallationSessionSnapshot(
        state = InstallationSessionState.CONNECTED,
        device = device,
        components = components,
        selectedOptionalComponentIds = selectedOptionalIds,
    )

    private fun component(id: String, required: Boolean, size: String?) = ComponentDescriptor(
        id = id,
        displayName = id,
        required = required,
        versionLabel = "1.0",
        sizeLabel = size,
        compatibilityLabel = "适用于当前车机",
    )

    private companion object {
        val device = DeviceSummary(
            id = "icar-03",
            displayName = "iCAR 03",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
        )
    }
}
