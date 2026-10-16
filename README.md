# Copy Contacts to SIM 📱

A modern, fast, and privacy-focused Android application to copy contacts from phone accounts (Google, WhatsApp, Samsung, local storage) to your SIM card's ADN (Abbreviated Dialing Numbers) phonebook.

Built with **Kotlin 2.0**, **MVVM**, **Coroutines**, and **Material 3**.

---

## ✨ Features

- **📶 Dual-SIM Support:** Automatically detects active SIM subscriptions, carriers, and slot indices.
- **🔄 Dual SIM API Architecture:**
  - Modern `SimPhonebookContract` API for Android 12+ (API 31–35).
  - Automatic fallback to classic `content://icc/adn` provider for older Android versions and OEM ROMs.
- **🔤 Smart Character Encoding:**
  - Automatically handles ASCII names (14+ bytes) and UCS-2 encoding for Sinhala, Tamil, and Unicode/emoji names.
  - Automatically shortens names that exceed SIM record capacities.
- **⚡ Smart Disambiguation:** Appends unique identifiers for contacts with multiple numbers (e.g., `John M1`, `John M2`).
- **🛡️ Duplicate Detection:** Automatically detects and skips numbers already saved on the SIM.
- **🗑️ SIM Contact Management:**
  - View all contacts currently saved on the SIM.
  - Delete individual contacts from the SIM.
  - Bulk "Delete All" option to easily clear the SIM before copying.
- **🎨 Material Design 3:**
  - Full Dark Mode support.
  - Color-coded initial letter avatars for each contact.
  - Debounced search filter for responsive scrolling.
  - Cancelable copy operations.
- **🔒 Privacy First:**
  - No internet permission required (`android.permission.INTERNET` is not requested).
  - All operations are completely offline.
  - `allowBackup="false"` to keep contact data secure.

---

## 🛠️ Architecture & Tech Stack

- **Language:** Kotlin 2.0
- **Target SDK:** Android 15 (API 35) / Minimum SDK: Android 5.0 (API 21)
- **Architecture:** MVVM (Model-View-ViewModel) with Android Architecture Components
- **Concurrency:** Kotlin Coroutines & `StateFlow`
- **UI:** Material Design 3 Components, ViewBinding, `RecyclerView` with `ListAdapter` & `DiffUtil`

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 or JDK 21
- Android SDK with platform 35 installed

### Building from Source

```bash
# Clone the repository
git clone https://github.com/mmrajapaksha/Copy-Contacts-to-SIM.git

# Navigate to project directory
cd Copy-Contacts-to-SIM

# Build debug APK
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📜 License

This project is open-source.
