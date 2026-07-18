package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.database.dao.DoorDao
import dev.rodolphe.syeksodemo.core.datastore.SessionDataSource
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.core.network.model.LoginRequestNetwork
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: SyeksoApiService,
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
