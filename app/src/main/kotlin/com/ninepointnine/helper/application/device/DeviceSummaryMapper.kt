package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary

internal fun ConnectedDevice.toDeviceSummary(
    lastConfirmedLabel: String? = "刚刚确认",
): DeviceSummary = DeviceSummary(
    id = identity.stableId,
    displayName = identity.model,
    connectionStatus = DeviceConnectionStatus.CONFIRMED,
    lastConfirmedLabel = lastConfirmedLabel,
    androidSdk = identity.androidSdk,
    capabilities = capabilities,
)
