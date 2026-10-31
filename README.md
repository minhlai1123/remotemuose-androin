# Remote Mouse Pro — Android Client (Kotlin / Native)

Native Android client implementation for **Remote Mouse Pro**, turning any Android smartphone or tablet into an ultra-low-latency wireless trackpad, mouse, and multimedia keyboard for Windows and desktop computers.

Conforms to the **RMPP (Remote Mouse Packet Protocol) v1.1 Specification**.

---

## 🌟 Key Features

### 1. High-Precision Touchpad & Pointer Ballistics
- **Multi-Touch Gestures**: Single-finger move, primary click (tap), secondary click (two-finger tap), and double-tap drag-and-drop.
- **Dual-Finger Scrolling**: Smooth inertia scrolling with configurable Natural Scroll direction.
- **Pointer Ballistics Engine**: Non-linear velocity acceleration curve, clamped max acceleration ($2.8\times$), and configurable sensitivity ($0.5\times - 3.0\times$).
- **Left-Handed Mode**: Seamless swap of primary and secondary click buttons.

### 2. Virtual Keyboard & Unicode Input
- **Sticky Modifiers**: Latching toggle for `Ctrl`, `Alt`, `Shift`, and `Win` (Super/Command) keys.
- **Specialized Keycodes**: Direct shortcuts for `Esc`, `Tab`, `Enter`, `Backspace`, `Delete`, `F5`, and `F11`.
- **Vietnamese Unicode Support**: Native IME input buffering, streaming full Unicode characters (Telex/VNI) to Desktop Host via `MSG_TEXT_INPUT` (Opcode `0x05`).

### 3. Banking-Grade Security & Pairing
- **Strict TLS 1.3 Encryption**: Powered by **Google Conscrypt 2.5.2** to guarantee RFC 8446 TLS 1.3 support across all Android versions (Android 8.0 Oreo up to Android 14+).
- **SHA-256 Certificate Fingerprint Pinning (TOFU)**: Instant host identity verification against certificate fingerprint encoded in pairing QR code or discovery payload.
- **Single-Use Challenge-Response PIN**: Time-bound numeric PIN verification preventing replay attacks.
- **Hardware AndroidKeyStore Storage**: AES-GCM encrypted storage for pairing tokens and host fingerprints via `SecureTokenStorage`.

### 4. Zero-Configuration Discovery & QR Scanner
- **mDNS / DNS-SD Discovery**: Automatic host resolution using Android `NsdManager` for service `_rmpp._tcp`.
- **UDP Broadcast Fallback**: Passive listener on port `54322` when multicast DNS is restricted by local network topology.
- **Camera QR Scanner**: Built-in CameraX & ML Kit scanner (`QrScannerActivity.kt`) for 1-second instant pairing via QR code.

---

## 🏗 Architecture & Modules

```text
android_client/
├── gradlew, gradlew.bat, gradle/             # Gradle 8.10.2 Wrapper
├── build.gradle.kts                          # Root build configuration
├── settings.gradle.kts                       # Module settings
├── gradle.properties                         # Build properties
└── app/
    ├── build.gradle.kts                      # AGP 8.7.0, Kotlin 2.0.21, Conscrypt 2.5.2
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml           # Permissions (Camera, Internet, Network state)
        │   ├── java/com/remotemouse/
        │   │   ├── MainActivity.kt           # Primary UI, Touchpad, Keyboard & Lifecycle
        │   │   ├── discovery/                # NsdManager mDNS & UDP fallback listener
        │   │   ├── history/                  # Paired device history management
        │   │   ├── input/                    # GestureHandler, PointerBallistics, KeyboardManager
        │   │   ├── network/                  # RmppClient (TLS 1.3 socket, state machine, watchdog)
        │   │   ├── protocol/                 # RMPP binary frames, CRC16-CCITT, stream parser
        │   │   ├── qr/                       # QrCodeParser & QrScannerActivity
        │   │   └── security/                 # SecureTokenStorage (AndroidKeyStore AES-GCM)
        │   └── res/
        │       ├── layout/activity_main.xml  # Touchpad, controls & keyboard UI layout
        │       └── values/strings.xml        # Localization strings
        └── test/java/com/remotemouse/        # Unit tests for Protocol, Ballistics, QR & Gestures
```

---

## 📋 Requirements & Compatibility

- **Language**: Kotlin 2.0.21 / Java 17+
- **Android Versions**:
  - `minSdkVersion`: **26** (Android 8.0 Oreo)
  - `targetSdkVersion`: **34** (Android 14)
- **Build System**:
  - Gradle: **8.10.2**
  - Android Gradle Plugin (AGP): **8.7.0**
- **Core Dependencies**:
  - `org.conscrypt:conscrypt-android:2.5.2` (Universal TLS 1.3)
  - `androidx.appcompat:appcompat:1.6.1`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`

---

## 🚀 Building & Testing

### Using Gradle Wrapper (CLI)
```bash
# Build Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew testDebugUnitTest
```

The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Using Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `android_client` directory.
3. Allow Gradle to sync.
4. Connect an Android device or launch an emulator.
5. Click **Run > Run 'app'** (`Shift + F10`).

---

## 📄 License
Internal proprietary project — Remote Mouse Pro. All rights reserved.
