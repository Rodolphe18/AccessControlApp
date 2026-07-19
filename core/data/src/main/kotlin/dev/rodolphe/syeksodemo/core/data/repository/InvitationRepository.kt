package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.model.Invitation
import kotlinx.coroutines.flow.Flow

interface InvitationRepository {
    val activeInvitations: Flow<List<Invitation>>

    suspend fun refreshInvitations(): Result<Unit>

    suspend fun createInvitation(
        title: String,
        doorId: String,
        validFromEpochMs: Long,
        validUntilEpochMs: Long,
    ): Result<Invitation>
}
