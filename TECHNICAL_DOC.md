# Shield: Technical Functional Document

## 1. System Overview
**Shield** is an Android security application that provides real-time app protection using **Face Recognition**. Unlike traditional PIN/Pattern locks, it uses the front camera to authenticate the user and employs "deceptive overlays" (Hardware Fault, Health Warning) to deter unauthorized access.

---

## 2. Technical Stack
* **Language**: Kotlin
* **UI Framework**: Jetpack Compose
* **Architecture**: MVVM with Hilt Dependency Injection
* **Face Detection**: Google ML Kit (Face Detection API)
* **Face Recognition**: TensorFlow Lite (FaceNet Model)
* **Background Processing**: Android Foreground Service
* **Persistence**: Jetpack DataStore (Preferences)
* **Camera API**: CameraX (Analysis & Preview)

---

## 3. Core Components

### A. AppLockForegroundService
The "Brain" of the app. It runs 24/7 to monitor user activity.
* **Usage Polling**: Uses `UsageStatsManager` to detect the foreground package every 250ms.
* **Session Management**: Maintains a 60-second "Unlocked" session for authenticated apps.
* **Overlay Control**: Dynamically adds/removes a `ComposeView` to the `WindowManager` to block access to protected apps.

### B. FaceRecognitionManager
The AI engine responsible for biometric processing.
* **Detection**: Uses ML Kit to find a face in the camera bitmap and crop it.
* **Embedding Generation**: Passes the cropped face through the `facenet.tflite` model to generate a 128-dimensional vector (FloatArray).
* **Verification**: Performs L2 Normalization and calculates the Dot Product between the "stored" face and "live" face.

### C. DataStoreManager
The storage layer for application preferences.
* Stores the list of `protected_apps`.
* Stores the serialized `face_embedding` (biometric data).
* Stores the `lock_message_type` (the "deceptive" mode).

### D. FaceLockOverlayContent
A non-focusable, full-screen Compose overlay.
* **Invisible Scanning**: Runs a background CameraX analyzer to scan for a face without showing a preview (initially).
* **Deception Logic**: If authentication fails or delays, it switches to a black screen showing fake errors or health warnings.
* **Voice Alerts**: Integrates Text-to-Speech (TTS) or MediaPlayer to play spiritual/health warnings (e.g., Premanand Ji's advice).

---

## 4. Operational Flow

### Flow 1: Face Enrollment (One-time Setup)
1. User opens **MainActivity** -> **Face ID** tab.
2. `FaceEnrollmentScreen` initializes CameraX front preview.
3. `FaceRecognitionManager` detects a face.
4. App extracts the embedding and saves it to `DataStoreManager`.
5. Biometrics are now registered.

### Flow 2: App Protection & Authentication
1. **Service Monitoring**: `AppLockForegroundService` detects a package change (e.g., User opens "WhatsApp").
2. **Lookup**: Service checks if "WhatsApp" is in the `protectedApps` list.
3. **Overlay Trigger**: If protected and no active session exists, the `FaceLockOverlayContent` is added via `windowManager.addView()`.
4. **Silent Scan**: CameraX starts analyzing frames in the background.
    * **Match Found**: `onAuthenticated()` is called -> Overlay removed -> Session starts.
    * **Wrong Face/No Face**: After 1.5 seconds, the "Deception Screen" (e.g., "Hardware Fault") becomes visible.
5. **Voice Feedback**: In "Kid Safe" mode, Hindi audio warnings are played to discourage phone usage.

### Flow 3: Session Reset
1. User leaves the protected app to the Home Screen or App Switcher.
2. Service detects `com.android.systemui` or Launcher package.
3. `currentlyUnlockedPackage` is cleared.
4. The next time the app is opened, a fresh face scan is required.

---

## 5. Security & Persistence Features
* **Auto-Start**: `BootReceiver` ensures the protection service starts automatically when the phone reboots.
* **Battery Optimization**: The app requests "Don't Optimize" status to prevent the system from killing the monitoring service.
* **Anti-Tamper**: The overlay uses `FLAG_LAYOUT_IN_SCREEN` to cover everything, including the status bar area where possible.
