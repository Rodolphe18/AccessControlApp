package dev.rodolphe.syeksodemo.feature.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepository
import dev.rodolphe.syeksodemo.core.data.repository.InvitationRepository
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.Invitation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InvitationViewModel @Inject constructor(
    doorsRepository: DoorsRepository,
    private val invitationRepository: InvitationRepository,
) : ViewModel() {

    private data class Local(
        val selectedDoorId: DoorId? = null,
        val title: String = "",
        val validFromEpochMs: Long = 0L,
        val validUntilEpochMs: Long = 0L,
        val isCreating: Boolean = false,
        val error: String? = null,
        val created: Invitation? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<InvitationUiState> =
        combine(doorsRepository.doors, invitationRepository.activeInvitations, local) { doors, invitations, l ->
            InvitationUiState(
                doors = doors,
                selectedDoorId = l.selectedDoorId ?: doors.firstOrNull()?.id,
                title = l.title,
                validFromEpochMs = l.validFromEpochMs,
                validUntilEpochMs = l.validUntilEpochMs,
                isCreating = l.isCreating,
                error = l.error,
                created = l.created,
                invitations = invitations,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InvitationUiState(),
        )

    init {
        viewModelScope.launch { invitationRepository.refreshInvitations() }
    }

    fun selectDoor(doorId: DoorId) = local.update { it.copy(selectedDoorId = doorId) }
    fun onTitleChange(value: String) = local.update { it.copy(title = value, error = null) }
    fun onWindowChange(fromMs: Long, untilMs: Long) =
        local.update { it.copy(validFromEpochMs = fromMs, validUntilEpochMs = untilMs) }

    fun create() {
        val s = uiState.value
        val doorId = s.selectedDoorId ?: return
        if (!s.canCreate) return
        local.update { it.copy(isCreating = true, error = null, created = null) }
        viewModelScope.launch {
            invitationRepository.createInvitation(s.title.trim(), doorId.value, s.validFromEpochMs, s.validUntilEpochMs).fold(
                onSuccess = { inv -> local.update { it.copy(isCreating = false, created = inv) } },
                onFailure = { local.update { it.copy(isCreating = false, error = "Impossible de créer l'invitation. Réessayez.") } },
            )
        }
    }
}
