#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

void confirmationPattern();
void startupPattern();
void handleHapticCommand(uint8_t command, uint8_t direction);
void criticalAlert(uint8_t direction);
void highAlert(uint8_t direction);
void mediumAlert(uint8_t direction);
void lowAlert(uint8_t direction);
void directionalPulse(uint8_t direction, int pulses, int duration);
void allMotorsOn();
void allMotorsOff();
void handleButtonPress();

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define RX_CHAR_UUID        "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define TX_CHAR_UUID        "beb5483f-36e1-4688-b7f5-ea07361b26a8"

#define MOTOR_THUMB    22
#define MOTOR_INDEX    22
#define MOTOR_MIDDLE   26
#define MOTOR_RING     26
#define MOTOR_PINKY    23
#define MOTOR_WRIST    23
#define BUTTON_PIN     15

const int motors[6] = {
  MOTOR_THUMB,
  MOTOR_INDEX,
  MOTOR_MIDDLE,
  MOTOR_RING,
  MOTOR_PINKY,
  MOTOR_WRIST
};

#define HAPTIC_CRITICAL  0x01
#define HAPTIC_HIGH      0x02
#define HAPTIC_MEDIUM    0x03
#define HAPTIC_LOW       0x04
#define HAPTIC_CONFIRM   0x10

#define DIR_FRONT  0x00
#define DIR_LEFT   0x01
#define DIR_RIGHT  0x02

#define BUTTON_EVENT 0x01

// BLE
BLEServer* pServer = NULL;
BLECharacteristic* pTxCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Button Debounce
bool lastButtonState = LOW;
unsigned long lastDebounceTime = 0;
const unsigned long debounceDelay = 50;

// BLE Server
class ServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("Device connected");
    confirmationPattern();
  }
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("Device disconnected");
  }
};

class CharacteristicCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {

    String rx = pCharacteristic->getValue();

    if (rx.length() >= 2) {
      uint8_t command = rx[0];
      uint8_t direction = rx[1];

      Serial.print("Command: 0x");
      Serial.print(command, HEX);
      Serial.print(", Direction: ");
      Serial.println(direction);

      handleHapticCommand(command, direction);
    }
  }
};

void setup() {
  Serial.begin(115200);
  Serial.println("Blind Assistant Glove Starting...");

  for (int i = 0; i < 6; i++) {
    pinMode(motors[i], OUTPUT);
    digitalWrite(motors[i], LOW);
  }

  pinMode(BUTTON_PIN, INPUT);

  BLEDevice::init("BlindAssist_Glove");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
    RX_CHAR_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pRxCharacteristic->setCallbacks(new CharacteristicCallbacks());

  pTxCharacteristic = pService->createCharacteristic(
    TX_CHAR_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pTxCharacteristic->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();

  Serial.println("BLE advertising started");
  Serial.println("Waiting for phone...");

  startupPattern();
}

void loop() {
  bool reading = digitalRead(BUTTON_PIN);

  if (reading != lastButtonState) {
    lastDebounceTime = millis();
  }

  if ((millis() - lastDebounceTime) > debounceDelay) {
    if (reading == HIGH && lastButtonState == LOW) {
      handleButtonPress();
    }
  }

  lastButtonState = reading;

  if (!deviceConnected && oldDeviceConnected) {
    delay(500);
    pServer->startAdvertising();
    Serial.println("Restarting advertising...");
    oldDeviceConnected = deviceConnected;
  }

  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  delay(10);
}

void handleHapticCommand(uint8_t command, uint8_t direction) {
  switch (command) {
    case HAPTIC_CRITICAL: criticalAlert(direction); break;
    case HAPTIC_HIGH:     highAlert(direction); break;
    case HAPTIC_MEDIUM:   mediumAlert(direction); break;
    case HAPTIC_LOW:      lowAlert(direction); break;
    case HAPTIC_CONFIRM:  confirmationPattern(); break;
    default: Serial.println("Unknown command"); break;
  }
}

// Haptic Patterns
void criticalAlert(uint8_t direction) {
  Serial.println("CRITICAL ALERT");
  for (int i = 0; i < 4; i++) {
    allMotorsOn(); delay(80);
    allMotorsOff(); delay(80);
  }
  directionalPulse(direction, 3, 150);
}

void highAlert(uint8_t direction) {
  Serial.println("HIGH ALERT");
  directionalPulse(direction, 3, 200);
}

void mediumAlert(uint8_t direction) {
  Serial.println("MEDIUM ALERT");
  directionalPulse(direction, 2, 250);
}

void lowAlert(uint8_t direction) {
  Serial.println("LOW ALERT");
  directionalPulse(direction, 1, 300);
}

void directionalPulse(uint8_t direction, int pulses, int duration) {
  int motorPin;

  if (direction == DIR_LEFT) {
    motorPin = MOTOR_INDEX;
    Serial.println("LEFT");
  } else if (direction == DIR_RIGHT) {
    motorPin = MOTOR_RING;
    Serial.println("RIGHT");
  } else {
    motorPin = MOTOR_MIDDLE;
    Serial.println("FRONT");
  }

  for (int i = 0; i < pulses; i++) {
    digitalWrite(motorPin, HIGH);
    delay(duration);
    digitalWrite(motorPin, LOW);
    delay(100);
  }
}

void confirmationPattern() {
  Serial.println("CONFIRMATION");
  digitalWrite(MOTOR_WRIST, HIGH); delay(100);
  digitalWrite(MOTOR_WRIST, LOW);  delay(80);
  digitalWrite(MOTOR_WRIST, HIGH); delay(100);
  digitalWrite(MOTOR_WRIST, LOW);
}

void startupPattern() {
  Serial.println("Startup pattern");
  for (int i = 0; i < 6; i++) {
    digitalWrite(motors[i], HIGH);
    delay(100);
    digitalWrite(motors[i], LOW);
    delay(50);
  }
}

// Motor control
void allMotorsOn() {
  for (int i = 0; i < 6; i++) digitalWrite(motors[i], HIGH);
}

void allMotorsOff() {
  for (int i = 0; i < 6; i++) digitalWrite(motors[i], LOW);
}

void handleButtonPress() {
  Serial.println("Button pressed");

  if (deviceConnected) {
    uint8_t buttonData = BUTTON_EVENT;
    pTxCharacteristic->setValue(&buttonData, 1);
    pTxCharacteristic->notify();
    Serial.println("Button event sent to phone");
    confirmationPattern();
  } else {
    Serial.println("Phone not connected");
    digitalWrite(MOTOR_WRIST, HIGH); delay(50);
    digitalWrite(MOTOR_WRIST, LOW);
  }
}
