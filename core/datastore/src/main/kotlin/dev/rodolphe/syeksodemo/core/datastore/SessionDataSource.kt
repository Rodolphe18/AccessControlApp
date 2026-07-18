package dev.rodolphe.syeksodemo.core.datastore

import androidx.datastore.core.DataStore
import dev.rodolphe.syeksodemo.core.datastore.proto.UserSession
import dev.rodolphe.syeksodemo.core.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The one place that touches the session DataStore. Exposes the session as a domain [Session] and
 * hides the proto entirely, so nothing upstream depends on the storage format.
 */
class SessionDataSource @Inject constructor(
    private val dataStore: DataStore<UserSession>,
) {
    val session: Flow<Session> = dataStore.data.map { it.asExternalModel() }

    suspend fun save(jwt: String, userId: String, displayName: String, email: String) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setJwt(jwt)
                .setIsLoggedIn(true)
                .setUserId(userId)
                .setDisplayName(displayName)
                .setEmail(email)
                .build()
        }
    }

    suspend fun clear() {
        dataStore.updateData { UserSession.getDefaultInstance() }
    }
}

private fun UserSession.asExternalModel() = Session(
    jwt = jwt,
    isLoggedIn = isLoggedIn,
    userId = userId,
    displayName = displayName,
    email = email,
)
