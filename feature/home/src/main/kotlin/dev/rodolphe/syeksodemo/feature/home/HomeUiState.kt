package dev.rodolphe.syeksodemo.feature.home

import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId

/** Everything the Home screen renders. [opening] holds the in-flight/last open state per door. */
data class HomeUiState(
    val doors: List<Door> = emptyList(),
    val opening: Map<DoorId, DoorOpenState> = emptyMap(),
    val activation: ActivationUiState = ActivationUiState(),
)

data class ActivationUiState(
    val isSheetOpen: Boolean = false,
    val code: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = code.isNotBlank() && !isSubmitting
}
