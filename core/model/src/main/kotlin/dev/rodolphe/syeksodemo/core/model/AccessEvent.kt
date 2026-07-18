package dev.rodolphe.syeksodemo.core.model

import java.time.Instant

/**
 * One attempt to open a door.
 *
 * Recorded locally first: an offline open still has to show up in the history, so events queue on
 * the device and sync to the server when there is a network again.
 */
data class AccessEvent(
    val id: String,
    val doorId: DoorId,
    val at: Instant,
    val outcome: Outcome,
    val syncedToServer: Boolean,
) {
    enum class Outcome { GRANTED, DENIED }
}
