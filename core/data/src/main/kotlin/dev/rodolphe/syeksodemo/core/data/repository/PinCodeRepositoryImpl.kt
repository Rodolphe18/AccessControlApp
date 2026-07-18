package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.datastore.SessionDataSource
import dev.rodolphe.syeksodemo.core.model.PinCode
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.core.network.model.CreatePinRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.PinCodeNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class PinCodeRepositoryImpl @Inject constructor(
    private val api: SyeksoApiService,
    private val sessionDataSource: SessionDataSource,
) : PinCodeRepository {

    private val _activePins = MutableStateFlow<List<PinCode>>(emptyList())
    override val activePins: Flow<List<PinCode>> = _activePins.asStateFlow()

    override suspend fun refreshPins(): Result<Unit> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = api.getPinCodes(bearer(jwt))
            _activePins.value = response.codes.map { it.asExternalModel() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createPin(doorId: String): Result<PinCode> {
        val jwt = currentJwt() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val pin = api.createPinCode(bearer(jwt), CreatePinRequestNetwork(doorId)).asExternalModel()
            _activePins.update { (it + pin).sortedBy { p -> p.expiresAtEpochMs } }
            Result.success(pin)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun currentJwt(): String? =
        sessionDataSource.session.first().jwt.ifEmpty { null }

    private fun bearer(jwt: String): String = "Bearer $jwt"
}

private fun PinCodeNetwork.asExternalModel() =
    PinCode(pin = pin, doorName = doorName, expiresAtEpochMs = expiresAtEpochMs)
