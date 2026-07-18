package dev.rodolphe.syeksodemo.intercom

import dev.rodolphe.syeksodemo.core.ble.DoorOpenState
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeSyeksoBleController : SyeksoBleController {
    var states: List<DoorOpenState> = listOf(DoorOpenState.Scanning, DoorOpenState.Opened)
    val openedLocalNames = mutableListOf<String>()

    override fun open(bleLocalName: String): Flow<DoorOpenState> {
        openedLocalNames += bleLocalName
        return states.asFlow()
    }
}
