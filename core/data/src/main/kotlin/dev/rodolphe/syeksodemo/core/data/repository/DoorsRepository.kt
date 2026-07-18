package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.model.Door
import kotlinx.coroutines.flow.Flow

/** Outcome of redeeming an activation code, mapped from the server's status codes so the UI can say
 *  exactly what went wrong (unknown code vs. already used vs. offline). */
sealed interface ActivationResult {
    data object Success : ActivationResult
    data object InvalidCode : ActivationResult
    data object AlreadyUsed : ActivationResult
    data object Unauthorized : ActivationResult
    data object NetworkError : ActivationResult
    data object ServerError : ActivationResult
}

interface DoorsRepository {
    /** The resident's doors, from Room — the source of truth. Works with no network. */
    val doors: Flow<List<Door>>

    /** Redeem an activation code; on success the new doors land in Room and flow through [doors]. */
    suspend fun activate(code: String): ActivationResult

    /** Pull the latest doors from the server into Room. */
    suspend fun refreshDoors(): Result<Unit>
}
