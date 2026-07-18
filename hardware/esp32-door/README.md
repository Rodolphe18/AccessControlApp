# Syekso demo door — ESP32 firmware

BLE peripheral that plays the role of an Syekso lock for the demo.

## Requirements
- An ESP32 dev board
- Arduino IDE with the ESP32 board package
- Library: **NimBLE-Arduino** (Library Manager)

## Flash
1. Open `esp32-door.ino`.
2. Select your ESP32 board and port.
3. Upload.

## How it works
- Advertises as `OSKEY-HALL-01` — this MUST equal a door's `bleLocalName` in the backend seed data.
  The `AccessControllerServer` seeds two doors: `OSKEY-HALL-01` (Porte d'entrée) and `OSKEY-GARAGE-01`
  (Garage). To simulate the garage door instead, set `DEVICE_NAME` to `OSKEY-GARAGE-01`.
- On a write of `OPEN` to characteristic `0000a101-…`, pulses `RELAY_PIN` (GPIO 2 = onboard LED by
  default) for 3 s. Wire a relay to that pin to drive a real strike.
- The UUIDs and command string mirror `core/ble/src/main/kotlin/dev/rodolphe/syeksodemo/core/ble/BleContract.kt`.
  Change them in both places together.
