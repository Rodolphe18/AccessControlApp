package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.datastore.SessionDataSource
import dev.rodolphe.syeksodemo.core.model.Invitation
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.core.network.model.CreateInvitationRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.InvitationNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class InvitationRepositoryImpl @Inject constructor(
    private val api: SyeksoApiService,
    private val sessionDataSource: SessionDataSource,
) : InvitationRepository {

    private val _activeInvitations = MutableStateFlow<List<Invitation>>(emptyList())
    override val activeInvitations: Flow<List<Invitation>> = _activeInvitations.asStateFlow()

    override suspend fun refreshInvitations(): Result<Unit> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = api.getInvitations(bearer(jwt))
            _activeInvitations.value = response.invitations.map { it.asExternalModel() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createInvitation(
        title: String,
        doorId: String,
        validFromEpochMs: Long,
        validUntilEpochMs: Long,
    ): Result<Invitation> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val invitation = api.createInvitation(
                bearer(jwt),
                CreateInvitationRequestNetwork(title, doorId, validFromEpochMs, validUntilEpochMs),
            ).asExternalModel()
            _activeInvitations.update { (it + invitation).sortedBy { i -> i.validUntilEpochMs } }
            Result.success(invitation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun currentJwt(): String? =
        sessionDataSource.session.first().jwt.ifEmpty { null }

    private fun bearer(jwt: String): String = "Bearer $jwt"
}

private fun InvitationNetwork.asExternalModel() = Invitation(
    code = code,
    title = title,
    doorName = doorName,
    validFromEpochMs = validFromEpochMs,
    validUntilEpochMs = validUntilEpochMs,
)
