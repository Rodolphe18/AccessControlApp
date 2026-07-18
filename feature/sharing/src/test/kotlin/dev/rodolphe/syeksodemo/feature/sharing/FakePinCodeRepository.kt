package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.data.repository.PinCodeRepository
import dev.rodolphe.syeksodemo.core.model.PinCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePinCodeRepository : PinCodeRepository {
    val pinsFlow = MutableStateFlow<List<PinCode>>(emptyList())
    override val activePins: Flow<List<PinCode>> = pinsFlow

    var refreshCalls = 0
    override suspend fun refreshPins(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }

    // The next createPin returns this; a success is also appended to pinsFlow.
    var createResult: Result<PinCode> = Result.success(PinCode("000000", "Porte", 0L))
    override suspend fun createPin(doorId: String): Result<PinCode> {
        createResult.getOrNull()?.let { pinsFlow.value = pinsFlow.value + it }
        return createResult
    }
}
