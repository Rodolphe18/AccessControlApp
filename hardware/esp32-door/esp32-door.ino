// Syekso demo door — ESP32 BLE peripheral.
// Advertises as OSKEY-HALL-01, exposes one writable characteristic; on receiving "OPEN" it pulses
// the relay/LED pin for 3 seconds. UUIDs and command MUST match core:ble/BleContract.kt.
//
// DEVICE_NAME must equal a seeded door's bleLocalName in the backend (AccessControllerServer
// seedIfEmpty): "OSKEY-HALL-01" (Porte d'entrée) or "OSKEY-GARAGE-01" (Garage).
//
// Board: any ESP32 dev board. Library: "NimBLE-Arduino" (install via Library Manager).

#include <NimBLEDevice.h>

static const char* DEVICE_NAME   = "OSKEY-HALL-01";
static const char* SERVICE_UUID  = "0000a100-0000-1000-8000-00805f9b34fb";
static const char* COMMAND_UUID  = "0000a101-0000-1000-8000-00805f9b34fb";
static const int   RELAY_PIN     = 2;      // onboard LED on most ESP32 devkits; swap for a relay pin
static const uint32_t PULSE_MS   = 3000;

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  // NimBLE-Arduino 2.x signature (adds NimBLEConnInfo&). On 1.x this was onWrite(NimBLECharacteristic*).
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& connInfo) override {
    std::string value = c->getValue();
    Serial.printf("onWrite: '%s'\n", value.c_str());
    if (value == "OPEN") {
      Serial.println("OPEN -> pulsing relay/LED");
      digitalWrite(RELAY_PIN, HIGH);
      delay(PULSE_MS);
      digitalWrite(RELAY_PIN, LOW);
    }
  }
};

void setup() {
  Serial.begin(115200);
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, LOW);

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEServer* server = NimBLEDevice::createServer();
  NimBLEService* service = server->createService(SERVICE_UUID);
  NimBLECharacteristic* command = service->createCharacteristic(
      COMMAND_UUID, NIMBLE_PROPERTY::WRITE);
  command->setCallbacks(new CommandCallbacks());
  service->start();

  // Advertise the NAME only. We deliberately do NOT add the 128-bit service UUID here: a 128-bit UUID
  // plus the name plus flags overflows the 31-byte advertising packet, which drops the name — and the
  // app scans by name. The service is still discovered normally after the phone connects.
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->setName(DEVICE_NAME);
  advertising->start();
  Serial.printf("Advertising as %s\n", DEVICE_NAME);
}

void loop() {
  delay(1000);
}
