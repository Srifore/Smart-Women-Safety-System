# Voice Safety (Android)

Local-only Android app for an ESP32 women-safety device. Contacts are stored in the phone's private `SharedPreferences`; there is no server, account, or cloud database.

ESP32 firmware lives in `esp32_firmware/VoiceSafetyESP32.ino`.

## What it does

- Stores 3 to 6 emergency contacts locally.
- Sends an SMS to all saved contacts from the on-screen SOS button.
- Connects by BLE to an ESP32 advertising the name `VoiceSafety-ESP32`.
- Sends the same SMS when the ESP32 notifies `VOICE_HELP` or `BUTTON_LONG_PRESS`.
- Ignores repeated device alerts for 30 seconds to avoid SMS floods.

## ESP32 BLE contract

Create a BLE GATT server with service UUID `7b28f8f0-7a4a-4e5d-a2e0-13579bdf0001` and a notifying UTF-8 characteristic UUID `7b28f8f0-7a4a-4e5d-a2e0-13579bdf0002`.

Send exactly one of:

- `VOICE_HELP` after the mic/voice-detection logic recognizes help
- `BUTTON_LONG_PRESS` after the physical button is held for at least 3 seconds

The Android app asks for Bluetooth and SMS permissions at runtime. Build/install this as an Android app; SMS sending needs an active SIM/mobile plan. Keep the app open for this starter version so it maintains the BLE connection.
