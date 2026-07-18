package dev.rodolphe.syeksodemo.core.model

/** A single-use access PIN issued by a resident for a door. */
data class PinCode(
    val pin: String,
    val doorName: String,
    val expiresAtEpochMs: Long,
)
