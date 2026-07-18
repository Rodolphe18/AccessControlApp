package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.data.repository.ActivationResult
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepository
import dev.rodolphe.syeksodemo.core.model.Door
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDoorsRepository : DoorsRepository {
    val doorsFlow = MutableStateFlow<List<Door>>(emptyList())
    override val doors: Flow<List<Door>> = doorsFlow
    override suspend fun refreshDoors(): Result<Unit> = Result.success(Unit)
    override suspend fun activate(code: String): ActivationResult = ActivationResult.Success
}
