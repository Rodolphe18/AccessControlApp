package dev.rodolphe.syeksodemo.feature.intercomcall

sealed interface IncomingCallUiState {
    data object None : IncomingCallUiState
    data class Ringing(val callId: String, val doorName: String) : IncomingCallUiState
    data object Opening : IncomingCallUiState
    data class Result(val success: Boolean, val message: String) : IncomingCallUiState
}
