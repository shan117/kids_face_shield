# Shield: Advanced Technical Specification & Logic Flows

## 1. System Architecture
Shield is a security layer that operates at the system level using a **Foreground Service** orchestrator. It follows **MVVM** principles for its configuration UI and uses a reactive data layer powered by **Jetpack DataStore**.

*   **Core Engine**: Android Foreground Service (`AppLockForegroundService`).
*   **Biometric Stack**: Google ML Kit (Detection) + TensorFlow Lite (FaceNet Recognition).
*   **UI Layer**: Jetpack Compose injected via `WindowManager`.
*   **DI Framework**: Hilt (Dependency Injection).

---

## 2. Deep Dive: Component Responsibilities

### A. The Orchestrator: `AppLockForegroundService`
This service acts as the persistent lifecycle owner of the app's security logic.
-   **Package Monitoring**: Executes a `while(isActive)` loop within a `CoroutineScope`. It polls `UsageStatsManager` every 250ms to detect `MOVE_TO_FOREGROUND` events.
-   **Session Management**: Implements a "Soft Unlock" mechanism. Once a user is authenticated, it stores a `lastAuthTime`. If the user returns to the same app within 60 seconds, the lock is bypassed for efficiency.
-   **Window Management**: Dynamically manages the `ComposeView` overlay. It handles the `TYPE_APPLICATION_OVERLAY` flag and ensures the overlay is `FLAG_NOT_FOCUSABLE` to permit system gestures (Home/Back) while blocking app interaction.
-   **Lifecycle Bridging**: Uses a custom `OverlayLifecycleOwner` (implementing `LifecycleOwner` and `SavedStateRegistryOwner`) to provide a valid environment for CameraX and Compose within a Service.

### B. The AI Brain: `FaceRecognitionManager`
A singleton utility that abstracts the complexity of computer vision.
-   **Pre-processing**: Resizes images to `112x112` and normalizes pixel values to a `[-1, 1]` range (required by FaceNet).
-   **Inference**: Invokes `Interpreter.run()` on the `facenet.tflite` model to produce a 128-dimensional embedding.
-   **Similarity Scoring**: Uses `Dot Product` (Cosine Similarity) to compare vectors.
    -   `Score > 0.5`: Verified Owner.
    *   `Score < 0.5`: Unrecognized/Intruder.

### C. The Reactive Store: `DataStoreManager`
-   Serializes `FloatArray` embeddings into CSV strings for persistent storage.
-   Exposes `Flows` that allow the Foreground Service to instantly react to changes in the protected app list or new face registrations.

---

## 3. End-to-End Execution Flows

### Flow 1: System Boot & Persistence
1.  **Event**: Android OS broadcast `ACTION_BOOT_COMPLETED`.
2.  **Trigger**: `BootReceiver` receives the intent.
3.  **Action**: Calls `context.startForegroundService()`.
4.  **Init**: `AppLockForegroundService` starts, creates a notification channel, and initializes the `startAppMonitoring()` loop.

### Flow 2: App Interception & "Ghost" Authentication
This is the core security loop when a user opens a protected application.
1.  **Detection**: `UsageStatsManager` reports `com.whatsapp` (example) moved to foreground.
2.  **Validation**: Service verifies `com.whatsapp` is in the `protectedApps` set and no active session exists.
3.  **Injection**: `showOverlay()` is called. The `ComposeView` is added to the window with `Color.Transparent`.
4.  **Silent Camera Start**: `FaceLockOverlayContent` initializes CameraX `ImageAnalysis`.
5.  **Concurrent Race**:
    -   **Path A (Biometric)**: Analyzer captures a frame -> `FaceRecognitionManager` extracts embedding -> `isMatch()` returns `true`.
    -   **Path B (Timer)**: A `delay(1500)` coroutine counts down.
6.  **Resolution**:
    -   **If Path A wins**: `onAuthenticated()` is triggered -> Service removes the overlay -> User sees the app without ever seeing a lock screen.
    -   **If Path B wins (or Mismatch)**: `showWarningAfterDelay` becomes `true` -> Overlay background turns `Black` -> "Hardware Error" or "Health Warning" appears.

### Flow 3: Face Enrollment (Training)
1.  **Entry**: User opens `FaceEnrollmentScreen`.
2.  **Preview**: A visible `PreviewView` allows the user to align their face.
3.  **Extraction**: `ImageAnalysis` captures the frame once `detectFace()` confirms a valid facial structure.
4.  **Storage**: The 128-float embedding is extracted and saved to DataStore.

### Flow 4: Session Reset & Exit
1.  **Event**: User presses "Home" or "Recent Apps".
2.  **Detection**: `UsageStatsManager` reports `com.android.systemui` or the Launcher package.
3.  **Action**: `handlePackageChange()` detects the exit from the restricted app.
4.  **Cleanup**: `hideOverlay()` is called, the camera is released, and the `currentlyUnlockedPackage` session is cleared.

---

## 4. Technical Constants & Thresholds

| Constant | Value | Purpose |
| :--- | :--- | :--- |
| `POLLING_INTERVAL` | 250ms | Balancing detection responsiveness with battery consumption. |
| `REAUTH_INTERVAL` | 60,000ms | Prevents annoying re-scans if the user briefly switches apps. |
| `MATCH_THRESHOLD` | 0.5f | Cosine similarity limit for the FaceNet model. |
| `DECEPTION_DELAY` | 1,500ms | The "Invisibility Window" for seamless owner entry. |
| `MODEL_INPUT_SIZE` | 112x112 | Required resolution for the `facenet.tflite` model. |

---

## 5. Security Design Considerations
*   **Anti-Kill**: Running as a `FOREGROUND_SERVICE` with `STICKY` start ensures the OS prioritizes the app's survival.
*   **Gesture Passthrough**: By using `FLAG_NOT_FOCUSABLE`, the app doesn't "steal" touch events meant for the system navigation, preventing a "frozen" phone feel while the overlay is active.
*   **Deceptive UI**: Instead of a "Locked" screen, the app uses "Error" screens to discourage sophisticated bypass attempts by making the intruder believe the device/app is malfunctioning.
