package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.data.model.asEntity
import dev.rodolphe.syeksodemo.core.database.dao.DoorDao
import dev.rodolphe.syeksodemo.core.database.model.asExternalModel
import dev.rodolphe.syeksodemo.core.datastore.SessionDataSource
import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.core.network.model.ActivationRequestNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class DoorsRepositoryImpl @Inject constructor(
    private val api: SyeksoApiService,
    private val doorDao: DoorDao,
    private val sessionDataSource: SessionDataSource,
) : DoorsRepository {

    override val doors: Flow<List<Door>> =
        doorDao.observeDoors().map { entities -> entities.map { it.asExternalModel() } }

    override suspend fun activate(code: String): ActivationResult {
        val jwt = currentJwt() ?: return ActivationResult.Unauthorized
        return try {
            val response = api.activate(bearer(jwt), ActivationRequestNetwork(code = code))
            doorDao.upsertDoors(response.doors.map { it.asEntity() })
            ActivationResult.Success
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> ActivationResult.Unauthorized
                404 -> ActivationResult.InvalidCode
                409 -> ActivationResult.AlreadyUsed
                else -> ActivationResult.ServerError
            }
        } catch (_: IOException) {
            ActivationResult.NetworkError
        }
    }

    override suspend fun refreshDoors(): Result<Unit> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = api.getDoors(bearer(jwt))
            doorDao.upsertDoors(response.doors.map { it.asEntity() })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun currentJwt(): String? =
        sessionDataSource.session.first().jwt.ifEmpty { null }

    private fun bearer(jwt: String): String = "Bearer $jwt"
}
