#include <BLEDevice.h>
#include <BLEAdvertising.h>
#include <esp_bt.h>

// Payload the Commute Android app matches on: manufacturer company id 0xFFFF (little-endian in
// the AD structure) followed by the raw ASCII bytes "COMMUTE1" — see BleUtils.kt on the phone
// side and doc/ble-beacon/Program.cs for the PC-dongle equivalent this firmware replaces.
// Advertise-only by policy: this board never runs a GATT server and never accepts a connection,
// so there is nothing for a phone (or anything else) to control here, only broadcast to read.
static const char TOKEN[] = "COMMUTE1";

void setup() {
  BLEDevice::init("");

  // The two knobs the PC's WinRT dongle could never touch, which is the whole reason this board
  // exists: transmit power and advertising interval.
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9); // +9 dBm, max for this chip

  String manufacturerData;
  manufacturerData += (char)0xFF;
  manufacturerData += (char)0xFF; // company id 0xFFFF, little-endian
  manufacturerData += TOKEN;      // 8 raw ASCII bytes, no extra framing

  BLEAdvertisementData advData;
  advData.setManufacturerData(manufacturerData);

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->setAdvertisementData(advData);
  advertising->setScanResponse(false);
  advertising->setMinInterval(0x20); // 20ms — the spec's fastest non-directed advertising rate
  advertising->setMaxInterval(0x20);
  advertising->start();
}

void loop() {
  delay(60000); // nothing to do here — advertising runs entirely in the BLE controller
}
