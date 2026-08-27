package dev.rodolphe.syeksodemo.intercom

data class IntercomUiState(
    val entered: String = "",
    val status: IntercomStatus = IntercomStatus.Idle,
) {
    /**
     * True while the server check or the door attempt is still running. Guarding on this — rather
     * than on [IntercomStatus.Checking] alone — is what stops a second OK during the BLE phase from
     * firing a second validation against a code the first one has already claimed.
     */
    val isBusy: Boolean get() = when (status) {
        IntercomStatus.Checking,
        IntercomStatus.Searching,
        IntercomStatus.Connecting,
        IntercomStatus.Opening,
        -> true
        else -> false
    }

    val canValidate: Boolean get() = entered.length == PIN_LENGTH && !isBusy

    companion object {
        const val PIN_LENGTH = 6
    }
}

/**
 * The three BLE phases are kept apart on purpose: a single "Ouverture…" covering scan, connect and
 * write leaves the visitor unable to tell a door that is out of range from one that is refusing the
 * command — and it reads as if the door were already open.
 */
sealed interface IntercomStatus {
    data object Idle : IntercomStatus
    data object Checking : IntercomStatus
    data object Searching : IntercomStatus
    data object Connecting : IntercomStatus
    data object Opening : IntercomStatus
    data object Granted : IntercomStatus
    data class Denied(val reason: String) : IntercomStatus
    data class Error(val message: String) : IntercomStatus
}
