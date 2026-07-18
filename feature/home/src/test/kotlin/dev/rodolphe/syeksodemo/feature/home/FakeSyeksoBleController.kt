package dev.rodolphe.syeksodemo.feature.home

import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeSyeksoBleController : SyeksoBleController {
    // Each open() replays this sequence of states.
    var states: List<DoorOpenState> = listOf(
        DoorOpenState.Scanning,
        DoorOpenState.Connecting,
        DoorOpenState.Sending,
        DoorOpenState.Opened,
    )
    val openedLocalNames = mutableListOf<String>()

    override fun open(bleLocalName: String): Flow<DoorOpenState> {
        openedLocalNames += bleLocalName
        return states.asFlow()
    }
}
