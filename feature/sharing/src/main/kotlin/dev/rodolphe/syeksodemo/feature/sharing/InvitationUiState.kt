package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.Invitation

data class InvitationUiState(
    val doors: List<Door> = emptyList(),
    val selectedDoorId: DoorId? = null,
    val title: String = "",
    val validFromEpochMs: Long = 0L,
    val validUntilEpochMs: Long = 0L,
    val isCreating: Boolean = false,
    val error: String? = null,
    val created: Invitation? = null,
    val invitations: List<Invitation> = emptyList(),
) {
    val canCreate: Boolean
        get() = title.isNotBlank() && selectedDoorId != null &&
            validUntilEpochMs > validFromEpochMs && !isCreating
}
