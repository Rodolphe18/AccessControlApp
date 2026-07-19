package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork

data class CallUiState(
    val directory: List<DirectoryEntryNetwork> = emptyList(),
    val selectedUserId: String? = null,
    val status: CallStatus = CallStatus.Idle,
) {
    val canRing: Boolean get() = selectedUserId != null && status == CallStatus.Idle
}

sealed interface CallStatus {
    data object Idle : CallStatus
    data object Ringing : CallStatus
    data object Opening : CallStatus
    data class Ended(val message: String) : CallStatus
}
