package dev.rodolphe.oskeysdemo.core.data.repository

import dev.rodolphe.oskeysdemo.core.database.dao.DoorDao
import dev.rodolphe.oskeysdemo.core.datastore.SessionDataSource
import dev.rodolphe.oskeysdemo.core.network.OskeysApiService
import dev.rodolphe.oskeysdemo.core.network.model.LoginRequestNetwork
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: OskeysApiService,
    private val sessionDataSource: SessionDataSource,
    private val doorDao: DoorDao,
) : AuthRepository {

    override val session = sessionDataSource.session

    override suspend fun login(email: String, password: String): AuthResult = try {
        val response = api.login(LoginRequestNetwork(email = email, password = password))
        sessionDataSource.save(
            jwt = response.token,
            userId = response.user.id,
            displayName = response.user.displayName,
            email = response.user.email,
        )
        AuthResult.Success
    } catch (e: HttpException) {
        if (e.code() == 401) AuthResult.InvalidCredentials else AuthResult.NetworkError
    } catch (_: IOException) {
        AuthResult.NetworkError
    }

    override suspend fun logout() {
        sessionDataSource.clear()
        // Also drop the cached doors so the next resident never sees the previous one's access.
        doorDao.clearDoors()
    }
}
