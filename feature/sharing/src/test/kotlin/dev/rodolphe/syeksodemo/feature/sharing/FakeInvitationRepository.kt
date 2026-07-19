package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.data.repository.InvitationRepository
import dev.rodolphe.syeksodemo.core.model.Invitation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeInvitationRepository : InvitationRepository {
    val flow = MutableStateFlow<List<Invitation>>(emptyList())
    override val activeInvitations: Flow<List<Invitation>> = flow

    var refreshCalls = 0
    override suspend fun refreshInvitations(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }

    var createResult: Result<Invitation> = Result.success(Invitation("000000", "T", "Porte", 0L, 1L))
    var lastArgs: List<Any?>? = null
    override suspend fun createInvitation(
        title: String,
        doorId: String,
        validFromEpochMs: Long,
        validUntilEpochMs: Long,
    ): Result<Invitation> {
        lastArgs = listOf(title, doorId, validFromEpochMs, validUntilEpochMs)
        createResult.getOrNull()?.let { flow.value = flow.value + it }
        return createResult
    }
}
