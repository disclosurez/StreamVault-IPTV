# EPG Grid — Production Code Review

**Reviewed:** April 2026  
**Files:** `EpgScreen.kt`, `EpgGridComponents.kt`, `EpgHeroComponents.kt`,
`EpgControlComponents.kt`, `EpgScreenDialogs.kt`, `EpgViewModel.kt`

---

## Executive Summary

The EPG grid has a solid architectural foundation: a well-separated ViewModel pipeline,
proper `LazyColumn` row virtualization, a `CompositionLocal`-based clock that limits
recomposition, and a layered fallback strategy for missing guide data. The most important
issues that need fixing before a premium release are:

1. **Critical rendering bug** — program cells use `padding(start = itemStart)` inside a
   `Box`, so overlapping or back-to-back programs stack at the wrong pixel column instead
   of being positioned absolutely.
2. **Desynchronized horizontal scroll** — the timeline header and per-row program strips
   share a single `ScrollState` instance but the header scroll and each row scroll are
   driven independently; they can diverge on fast D-pad navigation.
3. **Hard channel cap at 60** — `MAX_CHANNELS = 60` silently truncates large provider
   catalogues with no pagination, making the guide unusable for providers with hundreds
   or thousands of live channels.
4. **30-second "now" clock visible only inside `GuideNowProvider`** — three separate
   `GuideNowProvider {}` wrappers exist in `EpgScreen.kt` (hero, grid, dialog), each
   producing an **independent** 30-second ticker; they can disagree by up to 30 s,
   causing the "now" bar and the hero progress indicator to be out of sync.
5. **`ImmersiveGuideHero` calls `System.currentTimeMillis()` directly** — bypassing
   `LocalGuideNow`, so the hero progress bar does not respect the composition clock and
   will show a stale value after any data refresh.

---

## 1. Correctness

### 1.1 Program Positioning Bug (Critical)

**Location:** `EpgGridComponents.kt` — `ProgramItem` and the inner `Box` in `EpgRow`.

```kotlin
// ProgramItem
modifier = Modifier
    .padding(start = itemStart)   // ← WRONG: padding, not absolute offset
    .width(itemWidth)
```

`padding(start = itemStart)` inside a `Box` layout places the item relative to the
previous sibling's right edge, not relative to the `Box` origin. With a standard
`Row` or `forEach` inside a `Box`, every program cell shifts by the sum of all
preceding `itemStart` values. The result: programs are visually displaced to the
right, back-to-back programs overlap, and the "now" column aligns with nothing real.

**Fix:** Use `Modifier.absoluteOffset(x = itemStart)` or `Modifier.offset(x = itemStart)`
(the latter is layout-relative but equivalent in a `Box`):

```kotlin
modifier = Modifier
    .offset(x = itemStart)
    .width(itemWidth)
    // … rest unchanged
```

The same pattern must be applied to the "now" line indicator inside `GuideTimelineHeader`.

### 1.2 Now-Indicator Offset in the Timeline Header

**Location:** `EpgGridComponents.kt` — `GuideTimelineHeader`, inner `Box`.

```kotlin
Box(
    modifier = Modifier
        .padding(start = totalTimelineWidth * elapsedRatio)  // same padding bug
        .width(2.dp)
        .fillMaxHeight()
        .background(Primary)
)
```

Same issue as 1.1: `padding(start = …)` stacks on top of whatever precedes this `Box`
child instead of anchoring it at the exact ratio position.

**Fix:** `.offset(x = totalTimelineWidth * elapsedRatio)`.

### 1.3 Cross-Midnight Handling

`guideWindowStart = anchorTime - LOOKBACK_MS` and `guideWindowEnd = anchorTime + LOOKAHEAD_MS`
span a fixed relative range from the anchor, which is correct. However:

- `jumpToDay()` computes time-of-day as `anchorTime.mod(DAY_SHIFT_MS)`, using Kotlin's
  `Long.mod()`. When `anchorTime` is negative (any time before epoch — unlikely in
  practice but possible if a test passes 0L as "uninitialised") the result is negative,
  shifting the window to the previous calendar day. Add a floor clamp: if `anchorTime`
  is below epoch milliseconds for year 2000, treat it as `System.currentTimeMillis()`.

- The XMLTV parser and EPG providers are cross-midnight aware (they store UTC epoch
  milestamps), so stored programs are safe. The UI correctly clamps `visibleStart` and
  `visibleEnd` in `ProgramItem`. **No additional issue here.**

### 1.4 Program Width Clamping Without Gap

```kotlin
val visibleEnd = max(visibleStart + 1, minOf(program.endTime, windowEnd))
```

Adding `+ 1` ms prevents zero-width but results in a 1-ms-wide item that renders at
`minimumItemWidth`. At 40–56dp minimum this is fine for display, but the program will
appear at the position of the *clamped end*, not its true start. A program whose
`endTime ≤ windowStart` should be **filtered out** before reaching `ProgramItem`.
Consider pre-filtering the program list in `buildGuideDisplaySnapshot`.

### 1.5 Overlapping Programs

The code renders every item in `programs.forEach { … }` with no overlap detection.
If the upstream EPG data contains two programs whose time ranges intersect, both items
will be rendered and one will visually overlap the other (especially with the current
padding-offset bug amplifying the displacement). Add a pre-pass that splits or drops
overlapping programs before building the `programsByChannel` map.

---

## 2. Scrolling & Navigation

### 2.1 Header and Row Scroll Desynchronization

**Location:** `EpgGrid`, `EpgRow`.

```kotlin
val horizontalScrollState = rememberScrollState()
// passed to: GuideTimelineHeader(scrollState = horizontalScrollState)
// and to each: EpgRow(scrollState = horizontalScrollState)
```

A single `ScrollState` is created at the `EpgGrid` level and shared. This is the
correct intent.  However, inside `GuideTimelineHeader` and `EpgRow` the `Box` that
wraps the timeline content uses a `Row` that applies `.horizontalScroll(scrollState)`.
When D-pad focus moves **inside one row's program strip** (via TV focus traversal),
the `BringIntoViewRequester` on the focused item may call `bringIntoView()` which
scrolls **that row's scroll state** but not the header, because `bringIntoView`
triggers the nearest `ScrollableContainer` ancestor, not the shared instance.

**Fix:** Replace per-row `horizontalScroll` with a single `LazyRow` or `HorizontalPager`
for the program strip, or use `nestedScroll` to propagate scroll commands up to the
shared `ScrollState`. A simpler approach: use a single full-width `Canvas` or
`BoxWithConstraints` to draw all program cells at absolute pixel positions, removing
the nested horizontal scroll entirely.

### 2.2 D-Pad Focus Traversal Inside the Program Strip

Each `ProgramItem` is a `TvClickableSurface` rendered inside a `forEach` loop, which
means the Compose TV focus system sees them as ordinary focusables in document order.
For a very wide timeline (multiple hours), D-pad right from the last *visible* program
will try to focus the next item which may be 300dp to the right and offscreen.
**The programme strip does not auto-scroll to bring the focused item into view** because
there is no `BringIntoViewRequester` wired to `ProgramItem`.

**Fix:** Add a `BringIntoViewRequester` to each `ProgramItem` and call
`bringIntoViewRequester.bringIntoView()` inside the `onFocusChanged` when `isFocused`
becomes true:

```kotlin
val bringIntoViewRequester = remember { BringIntoViewRequester() }
val scope = rememberCoroutineScope()
modifier = Modifier
    .bringIntoViewRequester(bringIntoViewRequester)
    .onFocusChanged {
        if (it.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() }
    }
```

### 2.3 Channel-Rail Focus Not Restored After Data Refresh

**Location:** `EpgScreen.kt` — `LaunchedEffect(uiState.channels, uiState.programsByChannel)`.

```kotlin
LaunchedEffect(uiState.channels, uiState.programsByChannel) {
    // resolves focusedChannel / focusedProgram in-memory …
}
```

This effect correctly resolves the model objects, but there is no call to restore
**Compose focus** to the previously focused item. If the channel list refreshes (e.g.
after a forced refresh or after the first background sync), focus drops to the system
default (typically row 0). On TV this is jarring.

**Fix:** Store the previously focused `itemKey` (from `epgChannelKey`) in a
`rememberSaveable`, then after the `LazyColumn` layout stabilises use a
`LaunchedEffect(channelKeys)` with `listState.scrollToItem(restoredIndex)` and
`focusRequester.requestFocus()` on the matching row.

### 2.4 Fast Channel-Zap Not Supported from Grid

There is no D-pad shortcut from within the grid to immediately play the focused channel
(e.g. pressing `OK` on the channel-rail goes to the channel play action, but a number
key or `KEYCODE_CHANNEL_UP/DOWN` is not handled). The grid does not intercept
`onPreviewKeyEvent` at the screen level.

**Fix:** Add a `Modifier.onPreviewKeyEvent` at the `EpgGrid` or `FullEpgScreen` level
to handle `KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN` for fast zap and
`KEYCODE_PROG_RED` / `KEYCODE_PROG_GREEN` for jump-to-now / options shortcuts.

---

## 3. Performance

### 3.1 Hard Channel Cap Is Not a Virtualization Strategy

```kotlin
const val MAX_CHANNELS = 60
```

The `LazyColumn` in `EpgGrid` already virtualizes vertical rows lazily. The `MAX_CHANNELS`
cap was introduced as a performance guard but it silently discards channels 61+ with no
indication to the user. For a provider with 500 live channels this makes the guide
incomplete. The real bottleneck is the **program loading step**, not the render.

**Recommended fix:** Remove the cap from the UI pass; apply pagination or an infinite-scroll
trigger at the ViewModel layer so the first 60 channels load eagerly and the next page
loads when the user scrolls near the bottom:

```kotlin
// In EpgGrid LazyColumn:
if (index == channels.size - 10) {
    LaunchedEffect(Unit) { onLoadMore() }
}
```

### 3.2 `buildList` for Hour Markers Re-Runs on Every Recomposition

**Location:** `GuideTimelineHeader`.

```kotlin
val hourMarkers = buildList { … }   // not inside remember {}
```

The hour-marker list is rebuilt on every recomposition of the header. The header
recomposes every 30 seconds (from `rememberGuideNow`) but also whenever `windowStart`,
`windowEnd`, or scroll position changes. Wrap in `remember`:

```kotlin
val hourMarkers = remember(windowStart, windowEnd, markerStepMs) {
    buildList { … }
}
```

Note: the per-row `markers` list already does this correctly — the header should match
that pattern.

### 3.3 Grid-Line Markers Drawn Per-Row

**Location:** `EpgRow` — inner `Box`.

```kotlin
markers.forEach { marker ->
    Box(modifier = Modifier
        .padding(start = totalTimelineWidth * markerRatio)
        …
        .background(Color.White.copy(alpha = 0.08f)))
}
```

For 60 rows × N markers (e.g. 14 half-hours = 14 markers), that is 840 `Box`
composables per full render, each containing a `background` draw call. Each has the
same x-positions and appearance. This is expensive and visually redundant: drawing the
grid lines on a single `Canvas` overlay placed below the `LazyColumn` (or as a fixed
background `Box`) would replace 840 composable slot allocations with one `Canvas.drawLine`
loop.

### 3.4 Multiple Independent `rememberGuideNow()` Instances

**Location:** `EpgScreen.kt` lines 322, 364, 503.

```kotlin
GuideNowProvider { GuideHeroSection(…) }        // ticker A
GuideNowProvider { EpgGrid(…) }                 // ticker B
GuideNowProvider { CompactGuideProgramDialog(…) } // ticker C
```

Each `GuideNowProvider` wraps an independent `produceState` coroutine ticking every
30 seconds. Three coroutines run instead of one, and they can be up to 30 s out of
phase.  The "now" bar in the grid can display a different time from the hero progress
bar.

**Fix:** Hoist a single `GuideNowProvider` above all three call sites, or move
`rememberGuideNow()` into the ViewModel as a `StateFlow<Long>` driven by a single
`tickerFlow`.

### 3.5 `ImmersiveGuideHero` Bypasses the Composition Clock

**Location:** `EpgHeroComponents.kt` — `ImmersiveGuideHero`.

```kotlin
val currentTime = System.currentTimeMillis()
```

This is a direct wall-clock read outside the `LocalGuideNow` composition local, so it
does not update unless recomposition is triggered by something else. The progress bar
and "Updated N min ago" label can show stale values indefinitely after the initial
composition. Replace with `currentGuideNow()` (already provided by the surrounding
`GuideNowProvider`).

### 3.6 Channel Logo Loading Without Placeholders

`ChannelLogoBadge` is called once per visible row. If the logo URL is absent or slow,
nothing prevents a row-height shift when the image loads. Ensure the badge provides a
fixed-size placeholder (it references `channelName` for a letter fallback, but verify
that the fallback occupies the same pixel dimensions as a loaded image).

---

## 4. State Handling

### 4.1 Scroll Position Lost on Window Shift

When the user calls `jumpForward()` or `jumpBackward()`, `guideWindowStart/End` changes,
which triggers a full recomposition of `EpgGrid`. Because `horizontalScrollState` is
created with `rememberScrollState()` (no saved state key tied to the window), the scroll
position resets to 0 on every window shift. For a "jump forward 30 min" action the user
expects the visible content to advance proportionally, not snap to the left edge.

**Fix:** After `jumpForwardHalfHour()`, programmatically scroll the `horizontalScrollState`
by the equivalent pixel distance. In the ViewModel expose the scroll target as a
`SharedFlow<Long>` (milliseconds to scroll to); observe it in the grid and call
`scrollState.animateScrollTo(targetPixelOffset)`.

### 4.2 `focusedProgram` State Matching Is Fragile

```kotlin
focusedProgram = focusedProgram?.let { focused ->
    resolvedPrograms.firstOrNull {
        it.startTime == focused.startTime &&
        it.endTime == focused.endTime &&
        it.title == focused.title
    }
}
```

Matching by `(startTime, endTime, title)` instead of a stable ID means that a metadata
update (e.g., a slightly corrected title from a reparsed XMLTV feed) will lose the focus
target and fall back to the current live program. If `Program` has a stable `id` or
`(channelEpgId, startTime)` composite key, prefer that.

### 4.3 `pendingLockedAction` Reset Race

**Location:** `EpgScreen.kt` — PIN dialog confirm handler.

```kotlin
scope.launch {
    if (viewModel.verifyPin(pin)) {
        val action = pendingLockedAction
        …
        pendingLockedAction = null
        action?.let(::executeLockedGuideAction)
    } else {
        pinError = context.getString(R.string.home_incorrect_pin)
    }
}
```

`pendingLockedAction` is `remember { mutableStateOf<LockedGuideAction?>(null) }` (not
`rememberSaveable`), so it is lost on configuration change (screen rotation on a phone
form-factor, or activity recreation). The user enters the PIN, rotation happens, the
action is null, and nothing plays. For a TV-only product this is low risk but worth
noting.

---

## 5. Time Sync & Guide Updates

### 5.1 No Periodic Auto-Refresh of Guide Data

The guide viewport auto-advances when the user calls `jumpForward()`, but the underlying
EPG data (`programsByChannel`) is only refreshed when:
- The user presses "Refresh" manually, or
- `refreshNonce` is incremented.

If the app runs for 6+ hours without a user interaction (common on always-on TV
devices), live programs will start showing as "expired" and a new day's schedule will
not appear. The ViewModel has no `WorkManager` or `TickerFlow` that calls `refresh()`
when the guide window falls entirely in the past.

**Fix:** In `init` or `observeGuideBase`, start a coroutine that compares
`System.currentTimeMillis()` to `guideWindowEnd` every 15 minutes and calls
`refresh()` when the window is about to expire:

```kotlin
viewModelScope.launch {
    while (true) {
        delay(15 * 60_000L)
        val state = _uiState.value
        if (System.currentTimeMillis() > state.guideWindowEnd - HALF_HOUR_SHIFT_MS) {
            refresh()
        }
    }
}
```

### 5.2 Auto-Scroll to "Now" Not Automatic on Screen Entry

The guide opens at the stored `guideAnchorTime`, which may be several hours in the past
if the user left the guide at 18:00 yesterday. The "Jump to Now" button corrects this,
but it requires user action. On first entry (when `startupCategoryId` is non-null and
`isStartupSelection == true`), `guideAnchorTime` should be initialised to
`System.currentTimeMillis()`.

Currently `restoreGuidePreferences()` restores the persisted anchor time unconditionally.
Add a staleness check:

```kotlin
val saved = preferencesRepository.guideAnchorTime.first()
val now = System.currentTimeMillis()
if (saved != null && abs(saved - now) < 6 * 60 * 60_000L) {
    guideAnchorTime.value = saved
} // else leave at now (default)
```

---

## 6. Edge Cases

### 6.1 Zero-Duration Programs

```kotlin
val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)  // window safe
// but no guard for program duration:
val widthRatio = ((visibleEnd - visibleStart).toFloat() / totalDuration).coerceIn(0f, 1f)
```

If `program.startTime == program.endTime` (a zero-duration entry from a malformed
XMLTV feed), `visibleStart == visibleEnd` (before the `+ 1` guard), and `widthRatio`
is 0. The item renders at `minimumItemWidth` but at the *start* position. This is
acceptable, but the `+ 1` guard in `visibleEnd` should be documented as intentional
and the filter in `loadGuidePrograms` should discard entries where `endTime <= startTime`.

### 6.2 Extremely Long Programs (24-hour slot)

A program spanning the full window (e.g., a 24-hour slot for a channel with no detailed
schedule) produces `widthRatio ≈ 1.0`, so `itemWidth = totalTimelineWidth`. This is
visually correct (a single cell spanning the full strip) but may cause layout issues if
`totalTimelineWidth` is very large (`calculatedTimelineWidth` can exceed the viewport
by 8× for a 24-hour window), producing a cell wider than the screen. The minimum width
clamp doesn't help because the cell is *too large*, not too small. Clamp the maximum
cell width to `totalTimelineWidth` and test that horizontal scrolling still works.

### 6.3 Missing EPG for All Channels (guide stale)

The `isGuideStale` flag is set and shown as a badge in the hero. However the main
grid area still renders normally, just showing "No schedule" text in each row. This is
the right degraded mode. Consider adding a subtle banner above the grid when stale
(similar to the `LinearProgressIndicator` already shown during refresh) so the user
knows EPG is stale even when scrolled past the hero area.

### 6.4 Empty `programs` List Alignment

```kotlin
if (programs.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.epg_no_schedule_short), color = OnSurfaceDim)
    }
}
```

The "no schedule" box fills the entire `totalTimelineWidth` wide strip, which is
correct. No issue here — just note that this Box should also display at the same
vertical baseline as program cells to avoid a visual height difference.

---

## 7. Responsiveness & Input Latency

### 7.1 `loadGuidePrograms` Blocks the Data Fetch on the IO Dispatcher

```kotlin
private suspend fun loadGuidePrograms(…): GuideProgramsResult {
    return withContext(Dispatchers.IO) {
        // 3 sequential steps: resolved → legacy → Xtream fallback
        val resolvedPrograms = runCatching { epgRepository.getResolvedProgramsForChannels(…) }
        val legacyPrograms = if (unresolvedChannels.isNotEmpty()) { … }
        val fallbackProgramsByChannel = fetchXtreamGuideFallback(…)
    }
}
```

Steps 2 and 3 are sequential, but they operate on disjoint channel sets. They can be
parallelised with `coroutineScope { async { … } async { … } awaitAll() }`, removing
the serial delay when both a legacy query and an Xtream fallback query are needed.

### 7.2 `loadCombinedGuidePrograms` Is Fully Sequential

```kotlin
groupedPrograms.forEach { (providerId, providerChannels) ->
    val result = loadGuidePrograms(…)
    putAll(result.programsByChannel)
}
```

For a combined profile with N providers, guide programs are loaded one provider at a
time. Switch to `coroutineScope { groupedPrograms.map { async { loadGuidePrograms(…) } }.awaitAll() }`.

### 7.3 No Debounce on `jumpForwardHalfHour` / `jumpBackward`

A user holding D-pad on the jump button fires a rapid succession of `jumpForward()` calls,
each triggering `updateGuideAnchorTime()` which persists to `PreferencesRepository` and
emits a new upstream value that invalidates the entire guide data load. Add a debounce:

```kotlin
private val _pendingAnchorTime = MutableStateFlow(guideAnchorTime.value)
// in init:
viewModelScope.launch {
    _pendingAnchorTime.debounce(300).collect { anchorTime ->
        guideAnchorTime.value = anchorTime
        preferencesRepository.setGuideAnchorTime(anchorTime)
    }
}
fun updateGuideAnchorTime(ms: Long) { _pendingAnchorTime.value = ms }
```

---

## 8. UI Clarity

### 8.1 Current Program Highlight Is Subtle

```kotlin
containerColor = if (isCurrent) Primary.copy(alpha = 0.2f) else SurfaceElevated,
```

20% alpha over `SurfaceElevated` is barely visible, especially on OEM TV panels with
boosted brightness or OLED pixel representation. Consider 30–40% alpha or a distinct
left-border accent of 4dp width in `Primary` color for the current program cell.

### 8.2 Favorite Badge vs. Catch-Up Badge Conflict

```kotlin
if (isFavorite || channel.catchUpSupported) {
    Box { Text(if (channel.catchUpSupported) "Replay" else "★") }
}
```

Only one badge is shown even when both conditions are true: a catch-up channel that is
also a favorite shows only "Replay", silently dropping the favorite indicator. Show
both badges side by side, or use a combined badge.

### 8.3 Channel Number Formatting

```kotlin
text = if (channel.number > 0) "${channel.number}. ${channel.name}" else channel.name
```

For channels numbered 1–9, this renders as "1. ChannelName" which looks inconsistent
next to "10. OtherChannel" because no zero-padding is applied. On TV UIs with a fixed
channel-rail width, consider right-aligning the number in a fixed-width column rather
than concatenating it into the name string.

### 8.4 Timeline Header Does Not Scroll Visually During D-Pad Navigation

The timeline header is rendered separately above the `LazyColumn`. As the user D-pads
down through channels and a program item scrolls horizontal state, the header should
follow. Because both share the same `scrollState`, the header does visually scroll —
but only when the user is **inside a program strip**. When the user is focused on the
channel-rail column, horizontal scroll is not possible at all. This means the header
time label and the visible programs can be misaligned if the user scrolled the programs
and then moved focus to the channel rail and back.

**Fix:** Lock horizontal scroll to the program strip interaction only; the header
always reflects the current `scrollState.value` which is correct. No code change
needed, but verify on device that the `LazyColumn` scroll does not inadvertently reset
`horizontalScrollState` to 0 on item recomposition.

---

## 9. Error Resilience

### 9.1 Silent Failure on `runCatching` in `loadGuidePrograms`

```kotlin
val resolvedPrograms = runCatching {
    epgRepository.getResolvedProgramsForChannels(…)
}.getOrElse { emptyMap() }
```

Failures are swallowed silently and degrade to `emptyMap()`. The `failedScheduleCount`
tracks missing channels but does not surface the underlying exception. For debugging
purposes, at least log the exception at `ERROR` level:

```kotlin
}.onFailure { e -> Timber.e(e, "EPG resolve failed for provider $providerId") }
 .getOrElse { emptyMap() }
```

Without this, reproducing EPG data regressions in production requires device-side
logcat captures from users.

### 9.2 Xtream Fallback Does Not Honour `MAX_XTREAM_GUIDE_FALLBACK_CHANNELS`

```kotlin
val fallbackChannels = missingChannels.take(MAX_XTREAM_GUIDE_FALLBACK_CHANNELS)
```

`MAX_XTREAM_GUIDE_FALLBACK_CHANNELS = 24`, so when a provider serves 60 channels but
none have XMLTV guide data, only the first 24 get the Xtream API fallback. Channels
25–60 display as "No schedule" with no retry path. Consider raising the limit or making
it configurable, or at least noting it in the stale-badge text.

### 9.3 Combined Guide With No Member Providers

```kotlin
private fun combinedProviderIdsFlow(profileId: Long): Flow<List<Long>> = flow {
    emit(combinedM3uRepository.getProfile(profileId)?.members.orEmpty())
}.map { members -> members.filter { it.enabled }.map { it.providerId } }
```

This is a **cold, non-repeating flow**. If the profile's members list changes while
the guide is open (e.g., the user enables a provider in Settings), the combined guide
will not update because the flow only emits once. Convert it to a `Flow` that
re-reads from the database reactively (e.g., a Room DAO query flow rather than a
`getProfile()` suspend call).

---

## 10. Security & Data Integrity

### 10.1 `program.description.ifBlank` Falls Through to Unfiltered String

**Location:** `ImmersiveGuideHero`.

```kotlin
text = program.description.ifBlank { stringResource(R.string.epg_hero_no_program_description) }
```

Program descriptions from XMLTV are free text. If a provider injects HTML entities
or markup in the description field (common with some European XMLTV feeds), it will
render as raw text. This is acceptable for a `Text` composable (no injection path),
but ensure the XMLTV parser stores `.trim()` descriptions to avoid leading/trailing
whitespace rendering.

---

## 11. Additional Bugs & Architectural Gaps

### 11.1 `epgChannelKey` Includes `index` — Unstable LazyColumn Diff (G-21)

**Location:** `EpgGridComponents.kt` — `epgChannelKey`.

```kotlin
internal fun epgChannelKey(channel: Channel, index: Int): String {
    return "channel:${channel.id}:…:$index"
}
```

Including `index` in the stable key means that if any channel shifts position in the
list (e.g. a category filter changes, or channels are reordered after a refresh), every
item **at or below** the first changed position gets a new key. Compose treats a new
key as a new item: it removes the old slot, creates a new one, and redraws — even
if the channel data is identical. Remove `index` from the key; `channel.id` + `channel.streamId`
+ `epgId` + `channel.name.trim()` is already sufficiently unique without it.

### 11.2 Horizontal Scroll Not Positioned to "Now" on Guide Entry (G-22)

Even when the anchor time is correct, `rememberScrollState()` starts at offset 0 (the
left edge). For a 7-hour window with 1 hour of lookback, the "now" position is at
1/7 ≈ 14% of the total timeline width — off the left edge of the viewport on any
screen narrower than the full timeline. The user must manually scroll right to see
current programs.

**Fix:** Add a `LaunchedEffect(Unit)` inside `EpgGrid` that, after the first layout
pass, scrolls `horizontalScrollState` to `(totalTimelineWidth * nowRatio - viewportWidth / 2)`
clamped to `[0, maxScrollValue]`, centering "now" on screen:

```kotlin
val nowRatio = ((now - guideWindowStart).toFloat() / totalDuration).coerceIn(0f, 1f)
LaunchedEffect(Unit) {
    val targetPx = ((totalTimelineWidth * nowRatio).value * density.density -
                    (timelineViewportWidth / 2).value * density.density).toInt().coerceAtLeast(0)
    horizontalScrollState.scrollTo(targetPx)
}
```

This is distinct from G-20, which fixes anchor-time staleness; G-22 fixes the pixel
offset within an already-correct time window.

### 11.3 No Numeric Digit (0–9) Remote-Key Channel Zap (G-23)

Section 2.4 recommended handling `KEYCODE_CHANNEL_UP/DOWN`, but the larger gap is
**direct numeric input**: standard IR remotes have 0–9 keys. All commercial EPGs
implement a digit-accumulation overlay: the user presses 1, 5, ENTER → channel 15.

**Fix:** At the `FullEpgScreen` level, intercept `KeyEvent.KEYCODE_0` through
`KeyEvent.KEYCODE_9` in `onPreviewKeyEvent`. Accumulate digits in a `rememberSaveable`
string, display an overlay chip showing the current number, and after a 1.2-second
idle timeout resolve the accumulated number to the matching `channel.number` and
scroll the `LazyColumn` to that row (or play the channel directly).

### 11.4 `Program.imageUrl` Never Rendered (G-24)

The data layer populates `Program.imageUrl` from XMLTV `<icon>` elements and Xtream
metadata, but neither `CompactGuideProgramDialog` nor `ImmersiveGuideHero` renders it.
This is a significant visual gap: sport events, movies, and popular series typically
have high-quality banner art in EPG sources.

**Fix:**
- In `ImmersiveGuideHero`: replace the `ChannelLogoBadge` with an `AsyncImage` that
  loads `program.imageUrl` when non-null, falling back to the channel logo.
- In `CompactGuideProgramDialog`: add a `16:9` banner above the program title when
  `program.imageUrl` is non-null.

### 11.5 "Live Now" Mode (`GuideChannelMode.ANCHORED`) Not Discoverable (G-25)

`GuideChannelMode.ANCHORED` filters the channel list to only those with a program
airing at the anchor time — exactly the "what's on now" view users expect on first
open. However it is accessible only via the **options overlay** (three taps away),
buried among density and schedule filters. Most users will never find it.

**Fix:** Add a `LIVE NOW` toggle chip directly in `GuideToolbarRow` between the
category chip and "Jump to Now". One tap activates ANCHORED mode; tapping again
returns to ALL. The toolbar already has room for a fourth chip.

### 11.6 No Incremental Window Preloading (G-26)

Every `jumpForward()` / `jumpBackwardHalfHour()` triggers a complete re-fetch of
`programsByChannel` for the new window. Until the coroutine completes the user sees
blank program strips. For a provider with 60 channels × 14 half-hour markers this is
typically 200–400 ms of latency on a local network and 1–2 s on a slow connection.

**Fix:** Maintain a two-window sliding cache in the ViewModel:
- `windowCache: Map<LongRange, GuideProgramsResult>` keyed by `(windowStart, windowEnd)`.
- When the user navigates forward, eagerly pre-fetch the *next* 3-hour window in the
  background while still displaying the current one.
- When a pre-fetched window becomes active, show it instantly.
- Evict windows that no longer overlap the displayable range.

### 11.7 `Program` Lacks a Stable Composite Room Key (G-27)

G-14 recommends matching focused programs by a stable ID rather than `(startTime,
endTime, title)`, and G-21 recommends removing `index` from the `LazyColumn` key.
Both fixes ultimately depend on programs having a stable, unique identifier.

The `ProgramEntity` Room table currently has no surrogate primary key that survives a
provider re-sync. Add a composite unique index on `(provider_id, channel_epg_id,
start_time)` and expose it as `Program.stableKey: String` in the domain model
(`"$providerId:$channelEpgId:$startTime"`). Use this key wherever programs need to be
compared across data refreshes.

---

## 12. Missing Features

### 12.1 Schedule Recording from the Guide (F-01)

The app has a complete `RecordingManager`, `ScheduleRecording` use case, and the
live player overlay already exposes one-time, daily, and weekly scheduling. The EPG
grid is the **primary** place users schedule recordings on every commercial STB, yet
`CompactGuideProgramDialog` has no "Record" or "Schedule" button.

**Fix:** Add a third action button to `CompactGuideProgramDialog` that calls
`viewModel.scheduleRecording(channel, program)`. For past programs the button should
be hidden; for future programs show "Schedule"; for currently-airing shows show
"Record from now". Wire to the existing `ScheduleRecording` use case — no new business
logic required.

### 12.3 Inline Live Preview in the Hero (F-03)

When focus lands on a channel row, the hero updates with metadata — but there is no
live video. The HomeScreen already implements the exact pattern needed: an auxiliary
`PlayerEngine` instance with `bypassAudioFocus = true`, started after a focus-dwell
delay, torn down on `Lifecycle.ON_STOP` and `onDispose`.

**Fix:** Add a `PlayerSurface` inside `ImmersiveGuideHero` that starts after a
configurable dwell threshold (e.g., 1500 ms). Use `@AuxiliaryPlayerEngine` injection
identical to the HomeScreen implementation. Respect the user's "disable guide preview"
preference if one exists, and ensure the surface is released at the same lifecycle
boundaries as the HomeScreen preview.

### 12.4 Genre and Rating Filter in the Toolbar (F-04)

`Program.genre` and `Program.rating` are populated by the data layer and stored in
the database, but the guide toolbar has no genre or rating filter. For a provider
with 300+ channels carrying News, Sport, Movies, Kids, and Documentary, a one-tap
genre filter is the fastest content-discovery path — faster than category browsing.

**Fix:** Add a genre chip to `GuideToolbarRow` that, when tapped, opens a compact
bottom-sheet picker (reuse `GuideCategoryPickerDialog` style) listing distinct genres
from `programsByChannel.values.flatMap { … }.mapNotNull { it.genre }.distinct()`. The
selected genre is then applied in `buildGuideDisplaySnapshot` as an additional filter
layer on `candidateProgramsByChannel`.

### 12.5 EPG Coverage Badge on Category Toolbar Chip (F-05)

`channelsWithSchedule` and `totalChannelCount` are computed in the ViewModel and
displayed in the hero badge (`GuideHeroBadge`). However, once the user scrolls down
past the hero area the coverage information disappears. The category toolbar chip
only shows the category name.

**Fix:** Append the coverage ratio to the category chip label:

```kotlin
label = "$selectedCategoryName  ${channelsWithSchedule}/${totalChannelCount}",
```

A low ratio (e.g., "Sport  2/40") immediately tells the user EPG is thin for that
category without requiring a scroll back to the hero.

### 12.6 Phone and Tablet Layout (F-06)

The guide is designed exclusively for TV (1280dp+ landscape). `rememberIsTelevisionDevice()`
is already imported in `EpgScreen.kt` and `EpgControlComponents.kt`, and
`BoxWithConstraints` is already used in `EpgGrid`. Despite this, no compact layout
branch exists.

On a phone the channel rail is 180dp wide and the program strip is several thousand dp
wide — the guide is effectively broken.

**Fix (suggested layout for compact screens):**
- **Channel list** (`maxWidth < 600dp`): a `LazyColumn` of channel-rail items only,
  full-width, with no horizontal program strip.
- **Program list**: when the user selects a channel, show a `LazyColumn` of that
  channel's programs for the full day as a detail pane (master–detail pattern using
  `TwoPaneLayout` or a back-stack push).
- **Hero**: collapse to a `64dp` card showing the focused channel + current program.

`rememberIsTelevisionDevice()` should drive the top-level layout branch so TV behaviour
is fully unchanged.

### 12.7 "Now & Next" Compact Guide Mode (F-07)

The full timeline grid is the only available guide view. Many users — especially on
first open — want a quick "what's on right now and what's coming up next?" list without
navigating the horizontal timeline. `getNowAndNext()` already exists in `EpgRepository`
and is unused in the guide surface.

**Fix:** Add a `GuideChannelMode.NOW_AND_NEXT` branch to `buildGuideDisplaySnapshot`.
In that mode, replace the `EpgGrid` with a `LazyColumn` of compact rows, each showing:
- Channel logo + name (same channel-rail cell)
- Current program title + progress bar (using `Program.progressPercent()`)
- Next program title + start time

No new network calls or data-layer code are required — `getNowAndNext` is already
available for every channel. Add a "Now & Next" chip to `GuideToolbarRow` alongside
the existing `GuideChannelMode.ALL` and `GuideChannelMode.ANCHORED` options.

### 12.8 Multi-Day Visual Calendar Picker (F-08)

Day navigation is available through `jumpToPreviousDay()` / `jumpToNextDay()` and the
arrow-key-accessible jump buttons, but there is no visual date widget. Navigating more
than 2–3 days forward or backward requires many sequential D-pad presses with no visual
feedback about the destination date. Every commercial STB EPG exposes a 7–14 day
calendar as a primary navigation element.

**Fix:** Add a `GuideDayPickerRow` — a horizontally scrollable strip of day chips
above the category selector — showing 7 days centred on today. Each chip displays the
day abbreviation and date number (e.g. "Fri 18"). Selecting a chip calls the existing
`jumpToDay(dayStartMillis)`.

Important: day boundary computation must use local time, not UTC epoch division (see
G-06 above):
```kotlin
val zone = TimeZone.getDefault().toZoneId()
val dayStart = LocalDate.now(zone).plusDays(offset)
    .atStartOfDay(zone).toInstant().toEpochMilli()
```

### 12.9 EPG Resolution Quality Visible to the User (F-09)

`EpgResolutionSummary` tracks total channels, exact-ID matches, normalized-name
matches, and unresolved counts after every resolution pass, but none of this reaches
the user. When a channel shows "No schedule" the user cannot tell whether:
- No EPG source is assigned to their provider
- The source has data but the channel ID / name didn't match
- The source was last refreshed weeks ago

`F-05` (coverage badge in the toolbar) covers the narrow case of *how many channels
have any schedule in the current window*. This item is the broader feedback layer.

**Fix:**
- **Settings — EPG Sources screen**: after each refresh show the per-source resolution
  breakdown ("matched 312 / 450 channels — 138 unresolved") sourced from
  `EpgSourceRepository.getResolutionSummary(providerId)`.
- **`CompactGuideProgramDialog`**: when `programsByChannel` for the channel is empty,
  query `EpgSourceRepository.getChannelMapping(providerId, channelId)` and display a
  contextual reason chip:
  - `EpgSourceType.NONE` → "No EPG source covers this provider"
  - `EpgMatchType.NORMALIZED_NAME` with empty programmes → "EPG matched by name — no schedule for today"
  - No mapping row → "This channel could not be matched to any EPG source"
- **Debug overlay** (behind a dev flag): show the `ChannelEpgMapping` confidence score
  and `matchType` below the channel name in the guide rail.

---

## Summary of Actionable Items

| Priority | ID    | Issue | Location |
|----------|-------|-------|----------|
| Critical | G-01  | Program cells use `padding(start=…)` instead of `offset(x=…)` — all programs render at wrong pixel positions | `EpgGridComponents` — `ProgramItem`, `GuideTimelineHeader` now-bar |
| Critical | G-02  | Three independent `GuideNowProvider` wrappers; "now" bar and hero can be 30 s out of sync | `EpgScreen.kt` lines 322, 364, 503 |
| High     | G-03  | `ImmersiveGuideHero` uses `System.currentTimeMillis()` instead of `currentGuideNow()` | `EpgHeroComponents.kt` |
| High     | G-04  | `MAX_CHANNELS = 60` silently truncates provider catalogues with no pagination or user feedback | `EpgViewModel` |
| High     | G-05  | No periodic auto-refresh; guide goes stale after window expires on always-on devices | `EpgViewModel.init` |
| High     | G-06  | `jumpToPrimeTime` / `jumpToDay` use UTC midnight, not local midnight | `EpgViewModel` |
| High     | G-07  | `loadCombinedGuidePrograms` loads per-provider sequentially; should be parallel | `EpgViewModel` |
| Medium   | G-08  | `buildList` for hour markers not inside `remember {}` — rebuilds every recomposition | `EpgGridComponents — GuideTimelineHeader` |
| Medium   | G-09  | Grid-line markers drawn as 840+ `Box` composables; should be a single `Canvas` overlay | `EpgGridComponents — EpgRow` |
| Medium   | G-10  | D-pad focus on program item does not trigger `bringIntoView`; program strip does not auto-scroll | `EpgGridComponents — ProgramItem` |
| Medium   | G-11  | Scroll position resets to 0 on every window time-shift | `EpgGrid` / `EpgViewModel` |
| Medium   | G-12  | Composite guide `combinedProviderIdsFlow` is a cold one-shot flow; member changes not reflected | `EpgViewModel` |
| Medium   | G-13  | No debounce on `jumpForwardHalfHour` / anchor-time updates; rapid D-pad fires many data reloads | `EpgViewModel.updateGuideAnchorTime` |
| Medium   | G-14  | `focusedProgram` match uses title+time instead of stable ID; breaks on metadata updates | `EpgScreen.kt` — `LaunchedEffect` |
| Medium   | G-15  | Focus not restored to previously focused row after data refresh | `EpgScreen.kt` |
| Low      | G-16  | Current program highlight alpha 0.2 is hard to see on OEM TV panels | `EpgGridComponents — ProgramItem` |
| Low      | G-17  | Favorite + catch-up badge: only one shows when both are true | `EpgGridComponents — EpgRow` |
| Low      | G-18  | `runCatching` in `loadGuidePrograms` silently swallows EPG fetch errors | `EpgViewModel` |
| Low      | G-19  | Xtream fallback hard-capped at 24 channels; channels 25–60 never get fallback EPG | `EpgViewModel` |
| Low      | G-20  | Auto-scroll to "now" not applied on first guide entry if persisted anchor is stale | `EpgViewModel.restoreGuidePreferences` |
| Medium   | G-21  | `epgChannelKey` includes `index`; any channel reorder causes full LazyColumn diff and redraws all visible cells | `EpgGridComponents — epgChannelKey` |
| Medium   | G-22  | Horizontal scroll not positioned to "now" pixel offset on guide entry; user sees left edge even with correct anchor time | `EpgGrid` — `LaunchedEffect(Unit)` missing |
| Medium   | G-23  | No numeric digit (0–9) remote-key accumulation for direct channel-number zap | `EpgScreen.kt` — `onPreviewKeyEvent` |
| Medium   | G-24  | `Program.imageUrl` populated by data layer but never rendered in `CompactGuideProgramDialog` or `ImmersiveGuideHero` | `EpgScreenDialogs`, `EpgHeroComponents` |
| Medium   | G-25  | `GuideChannelMode.ANCHORED` ("on right now") buried in options overlay; not reachable with a single toolbar action | `EpgHeroComponents — GuideToolbarRow` |
| High     | G-26  | No incremental window preloading; every time-shift triggers a full data reload and blank-grid flash | `EpgViewModel` |
| High     | G-27  | `Program` has no stable composite Room key; fixes for G-14 and G-21 both depend on one | `data` — `ProgramEntity` |
| High     | F-01  | **Missing feature:** No "Record" or "Schedule recording" action in the program dialog despite `RecordingManager` and `ScheduleRecording` use case existing | `EpgScreenDialogs — CompactGuideProgramDialog` |
| High     | F-03  | **Missing feature:** No inline live preview in the hero when focus dwells on a channel row; HomeScreen auxiliary `PlayerEngine` pattern is directly reusable | `EpgHeroComponents — ImmersiveGuideHero` |
| Medium   | F-04  | **Missing feature:** No genre or rating filter chip in the toolbar despite `Program.genre` and `Program.rating` being populated | `EpgHeroComponents — GuideToolbarRow` |
| Medium   | F-05  | **Missing feature:** No category EPG-coverage badge on the toolbar category chip; `channelsWithSchedule` / `totalChannelCount` exists but only shown in the hero | `EpgHeroComponents — GuideToolbarRow` |
| High     | F-06  | **Missing feature:** No phone/tablet layout; guide is designed exclusively for 1280dp+ TV screens and is unusable on handheld form factors | `EpgScreen.kt` / `EpgGrid` |
| High     | F-07  | **Missing feature:** No "Now & Next" compact guide mode; `getNowAndNext()` exists in the repository but is unused in the guide surface | `EpgViewModel` / `EpgScreen.kt` |
| Medium   | F-08  | **Missing feature:** No multi-day visual calendar picker; day navigation requires many sequential key presses with no date feedback | `EpgScreen.kt` / `EpgViewModel` |
| Medium   | F-09  | **Missing feature:** EPG resolution quality (matched/unresolved counts, match type, source staleness) is never surfaced to the user | `SettingsEpgSection`, `EpgScreenDialogs` |

