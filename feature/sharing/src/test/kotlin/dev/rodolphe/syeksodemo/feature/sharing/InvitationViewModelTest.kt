package dev.rodolphe.syeksodemo.feature.sharing

import dev.rodolphe.syeksodemo.core.model.Door
import dev.rodolphe.syeksodemo.core.model.DoorId
import dev.rodolphe.syeksodemo.core.model.Invitation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val door = Door(DoorId("door-hall"), "Porte d'entrée", "Résidence Montmartre", "OSKEY-HALL-01")

    private fun vm(
        doors: FakeDoorsRepository = FakeDoorsRepository(),
        invitations: FakeInvitationRepository = FakeInvitationRepository(),
    ) = InvitationViewModel(doors, invitations)

    @Test
    fun `init refreshes invitations and selects first door`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val inv = FakeInvitationRepository()
        val viewModel = vm(doors = doors, invitations = inv)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        assertEquals(1, inv.refreshCalls)
        assertEquals(door.id, viewModel.uiState.value.selectedDoorId)
    }

    @Test
    fun `canCreate requires title, door and a positive window`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val viewModel = vm(doors = doors)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        assertFalse(viewModel.uiState.value.canCreate)      // empty title
        viewModel.onTitleChange("Anniversaire")
        viewModel.onWindowChange(1_000L, 2_000L)
        runCurrent()
        assertTrue(viewModel.uiState.value.canCreate)
    }

    @Test
    fun `create success surfaces the invitation`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val inv = FakeInvitationRepository().apply {
            createResult = Result.success(Invitation("483920", "Anniversaire", "Porte d'entrée", 1L, 2L))
        }
        val viewModel = vm(doors = doors, invitations = inv)
        backgroundScope.launch { viewModel.uiState.collect() }
        viewModel.onTitleChange("Anniversaire")
        viewModel.onWindowChange(1L, 2L)
        runCurrent()

        viewModel.create()
        runCurrent()

        assertEquals("483920", viewModel.uiState.value.created?.code)
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("Anniversaire", "door-hall", 1L, 2L), inv.lastArgs)
        assertEquals(1, viewModel.uiState.value.invitations.size)
    }

    @Test
    fun `create failure sets an error`() = runTest {
        val doors = FakeDoorsRepository().apply { doorsFlow.value = listOf(door) }
        val inv = FakeInvitationRepository().apply { createResult = Result.failure(RuntimeException("boom")) }
        val viewModel = vm(doors = doors, invitations = inv)
        backgroundScope.launch { viewModel.uiState.collect() }
        viewModel.onTitleChange("X")
        viewModel.onWindowChange(1L, 2L)
        runCurrent()

        viewModel.create()
        runCurrent()

        assertEquals("Impossible de créer l'invitation. Réessayez.", viewModel.uiState.value.error)
    }
}
