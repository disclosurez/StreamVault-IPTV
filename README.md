# 📺 StreamVault — Android TV IPTV Player

## 📖 Overview
**StreamVault** is a fast, modern, and production-grade **Android TV IPTV Player** built with **Jetpack Compose for TV**. It is designed to replace aging, clunky IPTV apps with a fluid, "console-like" user experience.

The app supports **Xtream Codes** API and standard **M3U** playlists. It features Live TV, Movies (VOD), Series, comprehensive EPG (Electronic Program Guide), and a robust Favorites system.

### 🎯 Core Philosophy
- **Performance First**: Zero frame drops on standard Android TV hardware (Fire TV Stick 4K, Chromecast with Google TV).
- **TV-Native**: Designed strictly for D-Pad navigation. No "touch emulation". Focus management is explicit and deterministic.
- **Clean Architecture**: Strict separation of concerns (Domain, Data, Player, UI) to ensure scalability and maintainability.

---

## 🏗 Architecture & Modules

The project follows **Clean Architecture** principles with a multi-module Gradle setup:

### 1. `:app` (UI & Presentation)
- **Tech**: Jetpack Compose for TV, ViewModel, Hilt.
- **Responsibility**: Renders the UI, manages focus state, and handles user input.
- **Key Pattern**: "Screens" observe `UiState` flows from ViewModels. No business logic in Composables.

### 2. `:domain` (Business Logic)
- **Tech**: Pure Kotlin.
- **Responsibility**: Defines core models (`Channel`, `Program`, `Provider`), Repository interfaces, and Use Cases (`GetChannelsUseCase`, `SyncEpgUseCase`).
- **Dependency**: Zero Android dependencies.

### 3. `:data` (Data Layer)
- **Tech**: Room, Retrofit, OkHttp.
- **Responsibility**: Implements Repositories. Handles data synchronization, offline caching (Room), and API communication (Xtream Codes / M3U parsing).
- **Key Feature**: "Offline-First" capability using Room as the single source of truth.

### 4. `:player` (Media Engine)
- **Tech**: Media3 (ExoPlayer).
- **Responsibility**: Encapsulates all playback logic. Handles adaptive streaming (HLS/DASH), track selection, and Zapping (channel switching).

---

## 🛠 Technology Stack

| Layer | Technology | Notes |
|-------|------------|-------|
| **Language** | Kotlin 2.0+ | Modern syntax, Coroutines, Flow. |
| **UI** | Jetpack Compose for TV | Specially designed for 10-foot interfaces. |
| **Media** | Media3 (ExoPlayer) | Robust playback for HLS, DASH, TS. |
| **DI** | Hilt | Standard dependency injection. |
| **Database** | Room | Local persistence for Channels, EPG, Favorites. |
| **Network** | Retrofit + OkHttp | Xtream Codes API client. |
| **Async** | Coroutines + Flow | All data streams are reactive. |
| **Image** | Coil | Optimized image loading with caching. |

---

## ⚡ Key Implementation Details (For Future Agents)

### 🎮 Focus & Input Handling (Crucial)
Android TV requires **explicit focus management**. Compose for TV handles much of this, but we use specific patterns for stability:
- **Search Bar**: Uses a custom `Box` + `BasicTextField` pattern. Standard `OutlinedTextField` causes keyboard focus issues on TV. The outer Box handles the D-Pad focus and click, then requests focus for the inner text field.
- **Long Press**: We use a **Top-Level Boolean Lock** (`ignoreNextClick`) in `HomeScreen` to handle the "Release after Long Press" event. This prevents the dialog from closing immediately when the user releases the select button.

### 📺 Playback & Zapping
- **Zapping**: Channel switching (Up/Down in player) is handled by passing `internalChannelId` to the player. The player uses this ID to query the database for the *next/previous* channel in the *current category context*.

### 📂 Favorites & Groups
- **Favorites**: Stored locally in Room. Toggling a favorite is optimistic and instant in the UI.
- **Custom Groups**: Users can create custom "Virtual Categories" to organize channels.

### 📦 TV Compose And Accessibility Tracking
- `androidx.tv:tv-material` is intentionally pinned in `gradle/libs.versions.toml`.
- The library is now stable, but `SearchScreen` still uses `FilterChip` APIs behind `ExperimentalTvMaterial3Api`.
- Reduced-motion handling now respects Android's animator duration scale for shimmer and image crossfades.
- See `docs/audit/tv-compose-accessibility.md` for the current migration risk and accessibility checklist.

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Koala or later.
- JDK 17+.
- Android TV Emulator (API 34) or physical device (Enabled Developer Options).

### Build Instructions
1.  **Clone the repo**.
2.  **Sync Gradle**.
3.  **Run**: Select `app` configuration and run on an Android TV device.

### Project Structure
```text
streamvault/
├── app/            # UI, Navigation, DI
├── data/           # Database, API, Repository Impls
├── domain/         # Models, Use Cases, Interfaces
├── player/         # Media3/ExoPlayer Wrapper
└── gradle/         # Version Catalog (libs.versions.toml)
```

---

*Documentation maintained by Antigravity Agents.*
