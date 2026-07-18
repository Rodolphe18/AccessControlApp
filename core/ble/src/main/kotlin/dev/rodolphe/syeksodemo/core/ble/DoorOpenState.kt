package dev.rodolphe.syeksodemo.core.ble

/** Progress of a single door-open attempt, emitted in order until a terminal state. */
sealed interface DoorOpenState {
    data object Scanning : DoorOpenState
    data object Connecting : DoorOpenState
    data object Sending : DoorOpenState
    data object Opened : DoorOpenState
    data class Error(val reason: DoorOpenError) : DoorOpenState
}

enum class DoorOpenError {
    BluetoothOff,
    PermissionMissing,
    NotFound,
    ConnectionFailed,
    WriteFailed,
    Timeout,
}
