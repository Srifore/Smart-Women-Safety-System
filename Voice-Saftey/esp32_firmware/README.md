# ESP32 Firmware

This folder contains the Arduino sketch for the ESP32 side of Voice Safety.

## What it sends

- `VOICE_HELP` when the mic sees repeated loud speech-like audio
- `BUTTON_LONG_PRESS` when the physical SOS button is held for 3 seconds

## BLE contract

- Device name: `VoiceSafety-ESP32`
- Service UUID: `7b28f8f0-7a4a-4e5d-a2e0-13579bdf0001`
- Notify characteristic UUID: `7b28f8f0-7a4a-4e5d-a2e0-13579bdf0002`

## Wiring

The sketch uses these default pins. Change them at the top of `VoiceSafetyESP32.ino` if your board wiring is different.

- `MIC_BCLK_PIN = 26`
- `MIC_WS_PIN = 25`
- `MIC_SD_PIN = 34`
- `SOS_BUTTON_PIN = 33`
- `BLUE_STATUS_LED_PIN = LED_BUILTIN`
- `SOS_LED_PIN = 4`

For an INMP441-style mic module:

- `VDD` -> `3V3`
- `GND` -> `GND`
- `SCK` -> `MIC_BCLK_PIN`
- `WS` -> `MIC_WS_PIN`
- `SD` -> `MIC_SD_PIN`
- `L/R` -> `GND` for left channel or `3V3` for right channel

## Flashing

Open `VoiceSafetyESP32.ino` in Arduino IDE or PlatformIO, select your ESP32 board, and upload.

If the voice trigger is too sensitive or not sensitive enough, tune:

- `AUDIO_ENERGY_THRESHOLD`
- `AUDIO_PEAK_THRESHOLD`
- `AUDIO_WINDOWS_REQUIRED`

For real speech keyword spotting, replace the simple loud-audio trigger with a dedicated on-device model later.
