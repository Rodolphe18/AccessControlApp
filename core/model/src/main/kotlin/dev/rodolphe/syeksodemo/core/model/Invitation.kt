package dev.rodolphe.syeksodemo.core.model

/** A titled, windowed, multi-use access invitation for a door. */
data class Invitation(
    val code: String,
    val title: String,
    val doorName: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)
