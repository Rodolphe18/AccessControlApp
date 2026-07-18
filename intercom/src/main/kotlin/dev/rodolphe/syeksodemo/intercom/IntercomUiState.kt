package dev.rodolphe.syeksodemo.intercom

data class IntercomUiState(
    val entered: String = "",
    val status: IntercomStatus = IntercomStatus.Idle,
) {
    val canValidate: Boolean get() = entered.length == PIN_LENGTH && status != IntercomStatus.Checking

    companion object {
        const val PIN_LENGTH = 6
    }
}

sealed interface IntercomStatus {
    data object Idle : IntercomStatus
    data object Checking : IntercomStatus
    data object Opening : IntercomStatus
    data object Granted : IntercomStatus
    data class Denied(val reason: String) : IntercomStatus
    data class Error(val message: String) : IntercomStatus
}
