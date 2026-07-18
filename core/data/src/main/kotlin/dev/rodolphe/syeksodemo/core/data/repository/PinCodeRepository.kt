package dev.rodolphe.syeksodemo.core.data.repository

import dev.rodolphe.syeksodemo.core.model.PinCode
import kotlinx.coroutines.flow.Flow

interface PinCodeRepository {
    /** The resident's still-valid PINs (refreshed by [refreshPins], updated by [createPin]). */
    val activePins: Flow<List<PinCode>>

    suspend fun refreshPins(): Result<Unit>

    suspend fun createPin(doorId: String): Result<PinCode>
}
