package dev.rodolphe.oskeysdemo.core.data.repository

import dev.rodolphe.oskeysdemo.core.model.Session
import kotlinx.coroutines.flow.Flow

/** Outcome of a login attempt, shaped for the UI: bad credentials and no-network are different
 *  messages to the resident, so they are different results — not one generic failure. */
sealed interface AuthResult {
    data object Success : AuthResult
    data object InvalidCredentials : AuthResult
    data object NetworkError : AuthResult
}

interface AuthRepository {
    /** The current session; drives whether the app opens on login or on Accueil. */
    val session: Flow<Session>

    suspend fun login(email: String, password: String): AuthResult

    suspend fun logout()
}
