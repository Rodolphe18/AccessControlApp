package dev.rodolphe.syeksodemo.intercom.call

import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork

/** Loads the building's resident directory. Interface so CallViewModel is unit-testable without Retrofit. */
interface DirectoryProvider {
    suspend fun residents(): List<DirectoryEntryNetwork>
}

/** Static per-intercom config (demo: entrance of Résidence Montmartre). Provided via Hilt. */
data class IntercomConfig(
    val buildingId: String,
    val doorName: String,
    val doorBleLocalName: String,
)
