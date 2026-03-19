# TV Compose And Accessibility Notes

Last updated: 2026-03-18

## Compose for TV dependency status

- `androidx.tv:tv-material` is pinned in `gradle/libs.versions.toml` at `1.0.1`.
- The original audit note about `1.0.0-alpha12` is outdated.
- The dependency itself is now stable, but not every API used by the app is fully non-experimental.

## Remaining experimental TV Material usage

Current known `ExperimentalTvMaterial3Api` usage is intentionally limited to:

- `FilterChip` usage in `SearchScreen`
  - file: `app/src/main/java/com/streamvault/app/ui/screens/search/SearchScreen.kt`

Why this matters:

- future TV Material updates may still require migration work for this surface
- the risk is now localized instead of spread across the UI layer

Recommended update process:

1. update `compose-tv-material` only after TV regression testing
2. compile the app and verify `SearchScreen` first
3. retest D-pad focus behavior and chip selection visuals in search

## Accessibility status

### Implemented

- content descriptions already have broad coverage across interactive TV surfaces
- reduced motion support now respects `Settings.Global.ANIMATOR_DURATION_SCALE`
- shimmer loading states disable animation when reduced motion is enabled
- Coil image crossfades disable when reduced motion is enabled
- focus ring visibility was strengthened by increasing shared focus border widths

### Current behavior

Reduced motion implementation is centralized in:

- `app/src/main/java/com/streamvault/app/ui/accessibility/MotionPreferences.kt`

Shared motion-sensitive paths currently covered:

- `app/src/main/java/com/streamvault/app/ui/components/SkeletonLoader.kt`
- `app/src/main/java/com/streamvault/app/ui/components/AsyncImageModels.kt`
- `app/src/main/java/com/streamvault/app/StreamVaultApp.kt`

Shared focus ring sizing is defined in:

- `app/src/main/java/com/streamvault/app/ui/design/FocusSpec.kt`

### Still open

- no dedicated high-contrast mode
- no app-level text scaling strategy beyond system defaults
- no documented TalkBack validation pass yet
- some nonessential UI animations still exist outside the shared shimmer and image paths

## Manual validation checklist

### Reduced motion

1. Set system animation scale to `0x` on the Android TV device or emulator.
2. Open screens that show skeleton loaders and poster artwork.
3. Confirm:
   - shimmer does not animate
   - image loads do not crossfade
   - the UI remains readable and responsive

### Focus visibility

1. Navigate Home, Search, EPG, Settings, and Player overlays using only the D-pad.
2. Confirm the focused element remains obvious from couch distance on a large display.
3. Recheck on bright and dark content backgrounds.

### TalkBack

1. Enable TalkBack on device.
2. Verify Search, Home shelves, player controls, and settings rows are announced in a useful order.
3. Verify locked content, action buttons, and back navigation controls have meaningful labels.
