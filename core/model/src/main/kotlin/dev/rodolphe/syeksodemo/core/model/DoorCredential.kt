package dev.rodolphe.syeksodemo.core.model

import java.time.Instant

/**
 * What the app knows about a provisioned door key — deliberately everything *except* the key.
 *
 * The key material is imported into the Android Keystore at provisioning time and never leaves it,
 * so there is no field here to hold it and no way for it to reach the database or a log line.
 * Signing a challenge means asking the Keystore to do it, not reading a secret back out.
 */
data class DoorCredential(
    val keyId: String,
    val doorId: DoorId,
    val validUntil: Instant,
) {
    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(validUntil)
}
