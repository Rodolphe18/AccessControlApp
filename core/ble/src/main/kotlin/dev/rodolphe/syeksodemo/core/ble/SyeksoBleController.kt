package dev.rodolphe.syeksodemo.core.ble

import kotlinx.coroutines.flow.Flow

interface SyeksoBleController {
    /**
     * Scans for a peripheral advertising [bleLocalName], connects, writes the open command, and
     * emits [DoorOpenState] progress. The flow completes after a terminal state
     * ([DoorOpenState.Opened] or [DoorOpenState.Error]). Cancelling the collection aborts the scan
     * and disconnects.
     */
    fun open(bleLocalName: String): Flow<DoorOpenState>
}
