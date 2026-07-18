package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.PinCode

data class SharingUiState(
    val doors: List<Door> = emptyList(),
    val selectedDoorId: DoorId? = null,
    val activePins: List<PinCode> = emptyList(),
    val generatedPin: PinCode? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
) {
    val canGenerate: Boolean get() = selectedDoorId != null && !isGenerating
}
