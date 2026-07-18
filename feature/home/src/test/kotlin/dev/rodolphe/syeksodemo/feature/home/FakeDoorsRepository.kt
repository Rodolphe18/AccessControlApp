package dev.rodolphe.syeksodemo.feature.home

import dev.rodolphe.syeksodemo.core.data.repository.ActivationResult
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepository
import dev.rodolphe.syeksodemo.core.model.Door
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDoorsRepository : DoorsRepository {
    val doorsFlow = MutableStateFlow<List<Door>>(emptyList())
    override val doors: Flow<List<Door>> = doorsFlow

    var refreshResult: Result<Unit> = Result.success(Unit)
    var refreshCalls = 0
    override suspend fun refreshDoors(): Result<Unit> {
        refreshCalls++
        return refreshResult
    }

    // The next activate() returns this; doorsToEmitOnSuccess is pushed into doorsFlow on Success.
    var activateResult: ActivationResult = ActivationResult.Success
    var doorsToEmitOnSuccess: List<Door> = emptyList()
    override suspend fun activate(code: String): ActivationResult {
        if (activateResult is ActivationResult.Success) {
            doorsFlow.value = doorsToEmitOnSuccess
        }
        return activateResult
    }
}
