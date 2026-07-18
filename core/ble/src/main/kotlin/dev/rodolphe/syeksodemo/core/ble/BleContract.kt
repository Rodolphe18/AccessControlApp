package dev.rodolphe.syeksodemo.core.ble

import java.util.UUID

/**
 * The GATT contract shared between this app and the ESP32 firmware. The firmware MUST advertise the
 * door's bleLocalName, expose [SERVICE_UUID], and accept a write of [OPEN_COMMAND] on
 * [COMMAND_CHARACTERISTIC_UUID] to trigger the relay.
 *
 * Iteration 1 uses a fixed command with no cryptographic auth (see spec: challenge-response is a
 * later iteration).
 */
object BleContract {
    val SERVICE_UUID: UUID = UUID.fromString("0000a100-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a101-0000-1000-8000-00805f9b34fb")
    val OPEN_COMMAND: ByteArray = "OPEN".toByteArray(Charsets.US_ASCII)

    const val SCAN_TIMEOUT_MS = 10_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
}
