package com.ninepointnine.helper.domain.device

/** Optional, typed input capability owned by the already verified device lease. */
interface RemoteInputGateway {
    suspend fun subscribe(): RemoteInputSubscription
    suspend fun send(request: VehicleInputRequest): VehicleInputReceipt
}

interface RemoteInputSubscription : AutoCloseable {
    suspend fun next(): VehicleInputState
}

data class VehicleInputState(
    val available: Boolean,
    val keyboard: String,
    val epoch: Long,
    val targetPackage: String,
    val targetWindow: Int,
    val sequence: Long,
    val sending: Boolean = false,
)

data class VehicleInputRequest(val id: String, val epoch: Long, val text: String)

/** null means the transport did not prove whether the editor accepted the text. */
data class VehicleInputReceipt(
    val id: String,
    val committed: Boolean?,
    val restored: Boolean,
    val reason: String? = null,
)
