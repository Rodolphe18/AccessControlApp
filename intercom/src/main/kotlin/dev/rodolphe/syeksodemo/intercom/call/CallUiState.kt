package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork

data class CallUiState(
    val directory: List<DirectoryEntryNetwork> = emptyList(),
    val selectedUserId: String? = null,
    val status: CallStatus = CallStatus.Idle,
) {
    /**
     * A finished call must not lock the panel: [CallStatus.Ended] is as ringable as [CallStatus.Idle],
     * so the visitor can retry — or pick a different resident and ring them instead. Only an
     * in-flight call (Ringing / InCall) blocks a new one.
     */
    val canRing: Boolean get() = selectedUserId != null &&
        (status == CallStatus.Idle || status is CallStatus.Ended)
}

sealed interface CallStatus {
    data object Idle : CallStatus
    data object Ringing : CallStatus
    data object InCall : CallStatus
    /**
     * A call that is over. [isFailure] separates one that never connected — nobody home, declined,
     * timed out — from one that ran its course, so the UI invites a retry only where retrying makes
     * sense. No default: every ending has to say which it is.
     */
    data class Ended(val message: String, val isFailure: Boolean) : CallStatus
}
