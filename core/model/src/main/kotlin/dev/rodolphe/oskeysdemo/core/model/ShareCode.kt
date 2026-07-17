package dev.rodolphe.oskeysdemo.core.model

import java.time.Instant

/**
 * A single-use code a resident hands to a visitor.
 *
 * Whether a code opens a door is decided by the server at redeem time, never here: this type only
 * describes what the app displays. A visitor holding an expired code must not be able to get in by
 * moving their phone's clock.
 */
data class ShareCode(
    val code: String,
    val doorId: DoorId,
    val expiresAt: Instant,
    val redeemedAt: Instant?,
) {
    fun statusAt(now: Instant): Status = when {
        redeemedAt != null -> Status.REDEEMED
        !now.isBefore(expiresAt) -> Status.EXPIRED
        else -> Status.ACTIVE
    }

    enum class Status { ACTIVE, REDEEMED, EXPIRED }
}
