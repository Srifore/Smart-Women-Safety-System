#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ESP_I2S.h>

#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

static const char *DEVICE_NAME = "SafeLink-01";
static const char *SERVICE_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50000";
static const char *DEVICE_ID_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50001";
static const char *DEVICE_STATUS_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50002";
static const char *HEARTBEAT_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50003";
static const char *SOS_TRIGGER_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50004";
static const char *SOS_ACK_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50005";
static const char *BATTERY_LEVEL_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50006";
static const char *COMMAND_UUID = "d7c7f030-6b59-4bf0-8a08-2f20c2e50007";

static const int MIC_BCLK_PIN = 26;
static const int MIC_WS_PIN = 25;
static const int MIC_SD_PIN = 34;
static const int BLUE_STATUS_LED_PIN = LED_BUILTIN;
static const int SOS_LED_PIN = 4;
static const int BUZZER_PIN = 27;
static const int SOS_BUTTON_PIN = 33;

static const uint32_t BUTTON_LONG_PRESS_MS = 3000;
static const uint32_t SOS_ACTIVE_MS = 10000;
static const uint32_t EVENT_COOLDOWN_MS = 30000;
static const uint32_t MIC_SAMPLE_RATE = 16000;
static const size_t AUDIO_BUFFER_SAMPLES = 256;
static const int AUDIO_ENERGY_THRESHOLD = 1800;
static const int AUDIO_PEAK_THRESHOLD = 9000;
static const int AUDIO_WINDOWS_REQUIRED = 4;

BLEServer *bleServer = nullptr;
BLECharacteristic *deviceIdCharacteristic = nullptr;
BLECharacteristic *deviceStatusCharacteristic = nullptr;
BLECharacteristic *heartbeatCharacteristic = nullptr;
BLECharacteristic *sosTriggerCharacteristic = nullptr;
BLECharacteristic *sosAckCharacteristic = nullptr;
BLECharacteristic *batteryCharacteristic = nullptr;
BLECharacteristic *commandCharacteristic = nullptr;
bool bleConnected = false;
uint32_t lastEventAt = 0;

I2SClass microphoneI2S;
int16_t audioBuffer[AUDIO_BUFFER_SAMPLES];

bool buttonPressed = false;
uint32_t buttonPressedAt = 0;
uint32_t loudWindowCount = 0;
uint32_t lastMicCheckAt = 0;
bool sosActive = false;
uint32_t sosStartedAt = 0;

static void publishEvent(const char *eventName);
static void triggerAlert(const char *eventName);

class VoiceSafetyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    digitalWrite(BLUE_STATUS_LED_PIN, HIGH);
    if (deviceStatusCharacteristic != nullptr) {
      deviceStatusCharacteristic->setValue("CONNECTED");
      deviceStatusCharacteristic->notify();
    }
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    digitalWrite(BLUE_STATUS_LED_PIN, LOW);
    if (deviceStatusCharacteristic != nullptr) {
      deviceStatusCharacteristic->setValue("DISCONNECTED");
    }
    server->getAdvertising()->start();
  }
};

class CommandCharacteristicCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String value = characteristic->getValue().c_str();
    if (value == "TRIGGER_SOS") {
      triggerAlert("BLE_COMMAND");
      return;
    }

    if (value == "CLEAR_SOS") {
      sosActive = false;
      noTone(BUZZER_PIN);
      digitalWrite(SOS_LED_PIN, LOW);
      if (deviceStatusCharacteristic != nullptr) {
        deviceStatusCharacteristic->setValue(bleConnected ? "CONNECTED" : "DISCONNECTED");
        if (bleConnected) {
          deviceStatusCharacteristic->notify();
        }
      }
    }
  }
};

static void publishEvent(const char *eventName) {
  Serial.println(eventName);
  digitalWrite(SOS_LED_PIN, HIGH);
  delay(80);
  digitalWrite(SOS_LED_PIN, LOW);

  if (!bleConnected || sosTriggerCharacteristic == nullptr) {
    return;
  }

  sosTriggerCharacteristic->setValue(eventName);
  sosTriggerCharacteristic->notify();
}

static void triggerAlert(const char *eventName) {
  uint32_t now = millis();
  if (now - lastEventAt < EVENT_COOLDOWN_MS) {
    return;
  }
  lastEventAt = now;
  sosActive = true;
  sosStartedAt = now;
  digitalWrite(SOS_LED_PIN, HIGH);
  tone(BUZZER_PIN, 2200);
  if (deviceStatusCharacteristic != nullptr) {
    deviceStatusCharacteristic->setValue("SOS_TRIGGERED");
    deviceStatusCharacteristic->notify();
  }
  publishEvent(eventName);
}

static void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new VoiceSafetyServerCallbacks());

  BLEService *service = bleServer->createService(SERVICE_UUID);
  deviceIdCharacteristic = service->createCharacteristic(
    DEVICE_ID_UUID,
    BLECharacteristic::PROPERTY_READ
  );
  deviceIdCharacteristic->setValue(DEVICE_NAME);

  deviceStatusCharacteristic = service->createCharacteristic(
    DEVICE_STATUS_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  deviceStatusCharacteristic->setValue("DISCONNECTED");
  deviceStatusCharacteristic->addDescriptor(new BLE2902());

  heartbeatCharacteristic = service->createCharacteristic(
    HEARTBEAT_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  heartbeatCharacteristic->setValue("HEARTBEAT");
  heartbeatCharacteristic->addDescriptor(new BLE2902());

  sosTriggerCharacteristic = service->createCharacteristic(
    SOS_TRIGGER_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  sosTriggerCharacteristic->addDescriptor(new BLE2902());

  sosAckCharacteristic = service->createCharacteristic(
    SOS_ACK_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );

  batteryCharacteristic = service->createCharacteristic(
    BATTERY_LEVEL_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  batteryCharacteristic->setValue((uint8_t)100);
  batteryCharacteristic->addDescriptor(new BLE2902());

  commandCharacteristic = service->createCharacteristic(
    COMMAND_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  commandCharacteristic->setCallbacks(new CommandCharacteristicCallbacks());

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  advertising->start();
}

static void setupMicrophone() {
  microphoneI2S.setPins(MIC_BCLK_PIN, MIC_WS_PIN, -1, MIC_SD_PIN);
  if (!microphoneI2S.begin(I2S_MODE_STD, MIC_SAMPLE_RATE, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO)) {
    Serial.println("Mic init failed");
  }
}

static void setupButton() {
  pinMode(SOS_BUTTON_PIN, INPUT_PULLUP);
  pinMode(BLUE_STATUS_LED_PIN, OUTPUT);
  pinMode(SOS_LED_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BLUE_STATUS_LED_PIN, LOW);
  digitalWrite(SOS_LED_PIN, LOW);
  noTone(BUZZER_PIN);
}

static void handleButton() {
  bool pressedNow = digitalRead(SOS_BUTTON_PIN) == LOW;

  if (pressedNow && !buttonPressed) {
    buttonPressed = true;
    buttonPressedAt = millis();
  }

  if (!pressedNow && buttonPressed) {
    buttonPressed = false;
  }

  if (buttonPressed && millis() - buttonPressedAt >= BUTTON_LONG_PRESS_MS) {
    buttonPressed = false;
    triggerAlert("BUTTON_LONG_PRESS");
  }
}

static void handleVoiceDetection() {
  if (millis() - lastMicCheckAt < 40) {
    return;
  }
  lastMicCheckAt = millis();

  size_t bytesRead = microphoneI2S.readBytes(reinterpret_cast<char *>(audioBuffer), sizeof(audioBuffer));
  if (bytesRead < sizeof(int16_t) * 32) {
    return;
  }

  size_t sampleCount = bytesRead / sizeof(int16_t);
  uint32_t energySum = 0;
  int peak = 0;
  size_t loudSamples = 0;

  for (size_t index = 0; index < sampleCount; index++) {
    int sample = abs(audioBuffer[index]);
    energySum += sample;
    if (sample > peak) {
      peak = sample;
    }
    if (sample > AUDIO_ENERGY_THRESHOLD) {
      loudSamples++;
    }
  }

  uint32_t averageEnergy = energySum / sampleCount;
  if (averageEnergy > AUDIO_ENERGY_THRESHOLD || peak > AUDIO_PEAK_THRESHOLD) {
    loudWindowCount++;
  } else {
    loudWindowCount = 0;
  }

  if (loudWindowCount >= AUDIO_WINDOWS_REQUIRED && loudSamples > sampleCount / 4) {
    loudWindowCount = 0;
    triggerAlert("VOICE_HELP");
  }
}

static void handleSosTimeout() {
  if (!sosActive) {
    return;
  }

  if (millis() - sosStartedAt >= SOS_ACTIVE_MS) {
    sosActive = false;
    noTone(BUZZER_PIN);
    digitalWrite(SOS_LED_PIN, LOW);
    if (deviceStatusCharacteristic != nullptr) {
      deviceStatusCharacteristic->setValue(bleConnected ? "CONNECTED" : "DISCONNECTED");
      if (bleConnected) {
        deviceStatusCharacteristic->notify();
      }
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  setupButton();
  setupBle();
  setupMicrophone();
  Serial.println("VoiceSafety ESP32 ready");
}

void loop() {
  handleButton();
  handleVoiceDetection();
  handleSosTimeout();
  delay(5);
}
