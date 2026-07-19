package dev.rodolphe.syeksodemo.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Real BLE implementation. Everything runs inside a single [callbackFlow] per open() call: scan ->
 * connect -> discover -> write, translating each Android callback into a [DoorOpenState]. The flow
 * closes itself on the first terminal state; [kotlinx.coroutines.channels.awaitClose] guarantees the
 * scan is stopped and the GATT is closed even if the collector cancels.
 *
 * A scan timeout emits [DoorOpenError.NotFound] when no peripheral advertises the requested name, and
 * a connect timeout emits [DoorOpenError.Timeout] if a found device never completes the write.
 */
class AndroidSyeksoBleController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyeksoBleController {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @SuppressLint("MissingPermission") // permission is checked in hasScanConnectPermissions() first
    override fun open(bleLocalName: String): Flow<DoorOpenState> {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            return flowOf(DoorOpenState.Error(DoorOpenError.BluetoothOff))
        }
        if (!hasScanConnectPermissions()) {
            return flowOf(DoorOpenState.Error(DoorOpenError.PermissionMissing))
        }


        return callbackFlow {
            trySend(DoorOpenState.Scanning)

            var gatt: BluetoothGatt? = null
            val scanner = adapter.bluetoothLeScanner
            // Set once we match the target device, so the scan timeout knows to stay quiet.
            val deviceFound = AtomicBoolean(false)
            // Set on any terminal state, so late callbacks/timeouts don't double-emit.
            val terminal = AtomicBoolean(false)

            fun finish(state: DoorOpenState) {
                if (terminal.compareAndSet(false, true)) {
                    trySend(state)
                    close()
                }
            }

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(DoorOpenState.Error(DoorOpenError.ConnectionFailed))
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val characteristic = g.getService(BleContract.SERVICE_UUID)
                        ?.getCharacteristic(BleContract.COMMAND_CHARACTERISTIC_UUID)
                    if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                        finish(DoorOpenState.Error(DoorOpenError.ConnectionFailed))
                        return
                    }
                    trySend(DoorOpenState.Sending)
                    writeOpen(g, characteristic)
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        finish(DoorOpenState.Opened)
                    } else {
                        finish(DoorOpenState.Error(DoorOpenError.WriteFailed))
                    }
                }
            }

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device: BluetoothDevice = result.device
                    val advertisedName = result.scanRecord?.deviceName ?: device.name
                    if (advertisedName == bleLocalName && deviceFound.compareAndSet(false, true)) {
                        runCatching { scanner?.stopScan(this) }
                        trySend(DoorOpenState.Connecting)
                        gatt = device.connectGatt(context, false, gattCallback)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(DoorOpenState.Error(DoorOpenError.ConnectionFailed))
                }
            }

            // LOW_LATENCY = continuous scanning (~100% duty cycle). The default single-arg startScan uses
            // LOW_POWER (~10% duty cycle), which only intermittently catches a peripheral's advertisement
            // within our 10s window. For a short, foreground door-open we want to find it reliably & fast.
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner?.startScan(emptyList(), scanSettings, scanCallback)

            // Scan timeout: no matching advertiser within the window -> NotFound.
            launch {
                delay(BleContract.SCAN_TIMEOUT_MS)
                if (!deviceFound.get()) {
                    runCatching { scanner?.stopScan(scanCallback) }
                    finish(DoorOpenState.Error(DoorOpenError.NotFound))
                }
            }
            // Connect timeout: device found but the write never completed in time -> Timeout.
            launch {
                delay(BleContract.SCAN_TIMEOUT_MS + BleContract.CONNECT_TIMEOUT_MS)
                finish(DoorOpenState.Error(DoorOpenError.Timeout))
            }

            awaitClose {
                runCatching { scanner?.stopScan(scanCallback) }
                runCatching { gatt?.close() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeOpen(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                BleContract.OPEN_COMMAND,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = BleContract.OPEN_COMMAND
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun hasScanConnectPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
