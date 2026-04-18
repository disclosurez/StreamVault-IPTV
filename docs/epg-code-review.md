# EPG System — Production Code Review

**Scope**: `XmltvParser`, `EpgRepositoryImpl`, `EpgSourceRepositoryImpl`, `EpgResolutionEngine`, `EpgNameNormalizer`, `EpgViewModel`, `EpgGridComponents`, domain models (`Program`, `EpgModels`).

**Date**: April 2026

---

## Summary

The EPG system has a well-thought-out multi-source architecture and already incorporates
several good practices (streaming XmlPullParser, per-provider mutexes, staging area in
`EpgRepositoryImpl.refreshEpg`, gzip decompression with size cap). However, several
correctness bugs, a data-loss scenario, and observable performance gaps exist in production.

Issues are grouped by severity.

---

## CRITICAL

---

### C-2 · `refreshSourceInternal` deletes EPG data before new data is committed — data loss on failure

**File**: `EpgSourceRepositoryImpl.kt` (~line 269)

**Problem**:
```kotlin
epgChannelDao.deleteBySource(sourceId)   // old data gone
epgProgrammeDao.deleteBySource(sourceId) // old data gone
// … parse from network …
// If an exception is thrown here:
} catch (e: Exception) {
    epgSourceDao.updateRefreshStatus(sourceId, now, e.message)
    Result.error(...)  // no restore — source is now permanently empty
}
```

The two deletes are not in a transaction and no staging area is used. A parse failure,
OOM, network drop, or process kill between the delete and insert leaves the EPG source
with zero channels and zero programmes permanently. The user's guide is blank until
a successful full refresh.

`EpgRepositoryImpl.refreshEpg` already solves this with a negative-providerId staging
area. Use the same pattern here.

**Fix**: stage all inserts under a temporary source row with id `= -sourceId`, then
swap atomically:

```kotlin
val stagingId = -sourceId
epgProgrammeDao.deleteBySource(stagingId)
epgChannelDao.deleteBySource(stagingId)
// … write to stagingId …
transactionRunner.inTransaction {
    epgChannelDao.deleteBySource(sourceId)
    epgProgrammeDao.deleteBySource(sourceId)
    epgChannelDao.moveToSource(stagingId, sourceId)
    epgProgrammeDao.moveToSource(stagingId, sourceId)
}
```

Add `deleteBySource(stagingId)` to the catch block to clean up the staging rows on
failure.

---

### C-4 · Local-time XMLTV timestamps without timezone offset are silently treated as UTC

**File**: `XmltvParser.kt` — `parseLocalDateTime` and the "last resort" path

**Problem**:
Many real-world XMLTV providers emit local-time timestamps without timezone offsets:
```
<programme start="20250101200000" stop="20250101210000" channel="bbc1">
```
The XMLTV spec says these are local (provider) time, not UTC. `parseLocalDateTime`
passes them through `ZoneOffset.UTC`, shifting them by the provider's timezone — up
to ±14 hours. A programme broadcast at 20:00 local (+05:00) appears in the guide at
01:00 the next day.

Worse, the "last resort" digit-strip path drops any timezone suffix before the UTC
assumption:
```kotlin
val cleaned = dateStr.replace("""[^\d]""".toRegex(), "")
// "20250101120000 +0500" → "202501011200000500" → substring(0,14) = "20250101120000"
// tz offset is silently discarded, UTC assumed
```

**Fixes**:
1. Expose a `defaultOffset: ZoneOffset` parameter on `XmltvParser` (defaulting to
   `ZoneOffset.UTC` for backward compat). EPG source settings can optionally carry a
   per-source timezone string that the caller converts to a `ZoneOffset` at parse time.
2. In the last-resort path, try parsing the trailing digits as an offset before
   discarding them:
   ```kotlin
   // If length ≥ 19 and last 5 chars match [+-]\d{4}, parse as offset-aware
   ```

---

## HIGH

---

### H-2 · SQL LIKE wildcards in program search are not escaped — logical bypass

**File**: `EpgRepositoryImpl.kt` — `searchPrograms`

**Problem**:
```kotlin
val normalizedQuery = query.trim()        // NOT run through EpgNameNormalizer
return programDao.searchPrograms(
    queryPattern = "%$normalizedQuery%",  // user input directly interpolated into LIKE pattern
    ...
)
```
Searching for `%` produces `WHERE title LIKE '%%'` which matches every title, ignoring
the 2-character minimum guard. Searching for `%a%` returns exactly the same as `a`.
SQLite wildcard `_` matches any single character.

**Fix** — escape reserved LIKE characters before building the pattern:
```kotlin
fun String.escapeSqlLike(escape: Char = '\\'): String =
    this.replace("$escape", "$escape$escape")
        .replace("%", "$escape%")
        .replace("_", "$escape_")

val escaped = normalizedQuery.escapeSqlLike()
queryPattern = "%$escaped%",
// Also update the DAO @Query to use ESCAPE '\'
```

---

### H-3 · `refreshSourceInternal` deletes before inserting — empty guide window for users

Even absent a failure (C-2), the gap between:
```kotlin
epgChannelDao.deleteBySource(sourceId)
epgProgrammeDao.deleteBySource(sourceId)
// ← any read here sees zero EPG data
// ... network download + parse (can take minutes) ...
// inserts happen
```
means that any guide read during the refresh sees an empty schedule. On large feeds
(>10 MB), the parse can take 30–120 seconds. The staging-area pattern (see C-2) also
closes this availability gap.

---

### H-4 · `exactIdIndex` in `EpgResolutionEngine` maps each ID to itself — silent mismatch risk

**File**: `EpgResolutionEngine.kt`

**Problem**:
```kotlin
exactIdIndex[sourceId] = epgChannels.associate { it.xmltvChannelId to it.xmltvChannelId }
```
The map value is identical to its key. The lookup result (`channelEpgId in index`) is
only used for existence checking:
```kotlin
if (channelEpgId != null && channelEpgId in index) { ... }
```
The value side (`index[channelEpgId]`) is never used, so the map could be a `Set`.
More importantly, `channelEpgId` is compared against `xmltvChannelId` raw — no
normalization, no case-folding, no whitespace trimming. An XMLTV file that stores
`" bbc1.uk "` (with spaces) would not match a channel with `tvg-id="bbc1.uk"`. Some
XMLTV providers pad their IDs with extra spaces or use different casing.

**Fix**: normalize both sides of the exact-ID lookup (at least `trim()` on both), and
use a `Set<String>` for the existence index:
```kotlin
exactIdIndex[sourceId] = epgChannels.map { it.xmltvChannelId.trim() }.toHashSet()
// lookup:
if (channelEpgId != null && (channelEpgId.trim() in exactIdIndex[sid] ?: emptySet()))
```

---

### H-5 · `getNowAndNext` "next" program selection skips over gaps incorrectly

**File**: `EpgRepositoryImpl.kt`

**Problem**:
```kotlin
val current = programs.find { it.startTime <= now && it.endTime > now }
val next = programs.find { it.startTime > now }
```
`next` is found independently: the first program whose `startTime > now`. If `current`
is null (gap in schedule) and two future programs exist, `next` picks the nearer one —
that is correct. But if `current` is non-null and there are overlapping programs (a
common provider data quality issue), `next` could return a program whose `startTime`
falls *within* the current window, making both `current` and `next` appear to run at
the same time.

**Fix**: pick `next` relative to `current`:
```kotlin
val current = programs.find { it.startTime <= now && it.endTime > now }
val nextStart = current?.endTime ?: now
val next = programs.firstOrNull { it.startTime >= nextStart && it != current }
```

---

## MEDIUM

---

### M-1 · `refreshSource` marks `lastRefreshAt` at start of download, not success

**File**: `EpgSourceRepositoryImpl.kt`

```kotlin
epgSourceDao.updateRefreshStatus(sourceId, now, null)  // called BEFORE download
```

`lastRefreshAt` is set to the start of the attempt. If the download takes 2 minutes
and fails, the UI displays "Last refreshed: 2 minutes ago" with an error badge — which
reads as contradictory. `lastRefreshAt` should only be updated on success alongside
`lastSuccessAt`:

```kotlin
// Remove upfront call; on success:
epgSourceDao.updateRefreshSuccess(sourceId, System.currentTimeMillis())
// On failure, only update the error field:
epgSourceDao.updateRefreshError(sourceId, e.message ?: "Unknown error")
```

---

### M-2 · `getOverrideCandidates` loads all EPG channels into heap memory

**File**: `EpgSourceRepositoryImpl.kt`

```kotlin
epgChannelDao.getBySource(assignment.epgSourceId)  // returns List<EpgChannelEntity>
    .asSequence()
    .filter { ... }
```

For a source with 50 000 channels (common for large multi-country XMLTV feeds),
`getBySource` returns all rows. With 3–4 enabled sources, this can allocate 10–20 MB
of object heap per override search.

**Fix**: move the filter to the DAO with a Room `@Query` using `LIKE` on both
`xmltvChannelId` and `displayName`, and use a `LIMIT` clause:
```kotlin
@Query("""SELECT * FROM epg_channels
    WHERE epg_source_id = :sourceId
    AND (xmltv_channel_id LIKE :pattern OR display_name LIKE :pattern)
    LIMIT :limit""")
suspend fun searchBySource(sourceId: Long, pattern: String, limit: Int): List<EpgChannelEntity>
```

---

### M-3 · Program search has no debounce — expensive recomposition per keystroke

**File**: `EpgViewModel.kt` — `observeGuidePresentation`

Every character typed in the guide search bar triggers `buildGuideDisplaySnapshot` on
`Dispatchers.Default`. With 5 000+ programs in memory, this is a CPU-heavy map/filter
over the full dataset with no delay:

```kotlin
} .collectLatest { presentation ->
    // called on every keystroke
    val displaySnapshot = withContext(Dispatchers.Default) {
        buildGuideDisplaySnapshot(...)  // expensive
    }
```

**Fix**: add a flow debounce of 150–200 ms on `programSearchQuery` before it reaches
the combine:
```kotlin
programSearchQuery.debounce(150L)
```

---

### M-4 · EpgResolutionEngine — name match confidence 0.7 is too aggressive for partial matches

**File**: `EpgResolutionEngine.kt`

`EpgNameNormalizer.normalize` removes *all* non-alphanumeric characters:
- `"BBC ONE"` → `"bbcone"`
- `"BBC ONE HD"` → `"bbconehd"`
- `"BBC ONE+1"` → `"bbcone1"`

Only `"BBC ONE"` (normalized: `"bbcone"`) would match `"bbcone"` exactly, while the
HD/+1 variants won't. That is actually reasonable. The risk is that two *different*
channels collide after normalization (e.g., `"TV5"` and `"TV 5"`). Confidence of 0.7
should be acceptable but the resolution engine should log per-channel collisions for
debugging (see M-6).

---

### M-5 · `assignSourceToProvider` / `resolveAffectedProviders` always pass empty `hiddenLiveCategoryIds`

**File**: `EpgSourceRepositoryImpl.kt`

All internal resolution calls use `resolveForProvider(providerId, emptySet())`. The
hidden-category set comes from user preferences and is only available at the ViewModel
layer. Resolution runs without it, so channels in hidden categories get EPG mappings
that will never be used — wasted work, and the mapping table grows with stale noise.
In practice this is harmless but contributes to resolution summary counts that
misrepresent match quality.

No fix required for correctness, but consider making the ViewModel trigger resolution
through a use case that injects the hidden-category set from preferences.

---

### M-6 · No per-channel EPG resolution failure logging

**File**: `EpgResolutionEngine.kt`

The only observability into mapping quality is a single summary line:
```kotlin
Log.d(TAG, "Resolution for provider $providerId: $summary")
```

When a user reports "Channel X has no guide data", there is no log to distinguish:
- Channel has `epgChannelId = null`
- Channel has an ID but no EPG source is assigned
- Channel ID exists in source but has zero programmes for today
- Name normalization matched the wrong channel

**Fix**: log (at `VERBOSE` level) every unresolved channel along with the attempted
normalize key and whether any candidates existed in the name index.

---

### M-7 · `EpgSourceRepositoryImpl` and `EpgRepositoryImpl` both define separate `MAX_EPG_SIZE_BYTES` and `epgHttpClient` — divergence risk

Both files duplicate:
```kotlin
private const val MAX_EPG_SIZE_BYTES = 200L * 1_048_576
private val epgHttpClient: OkHttpClient by lazy { ... }
```

If one is updated (e.g., size cap raised to 500 MB), the other is silently left at
the old value. Extract these into a shared `EpgDownloadConfig` object injected by Hilt.

---

## LOW

---

### L-1 · `nowTicker` creates a fresh coroutine loop per subscriber

**File**: `EpgRepositoryImpl.kt`

Each call to `getNowAndNext` starts a new `while(true) { emit(); delay(60s) }` loop.
With 60 channels on screen, 60 timers fire independently, each potentially issuing a
DB query. Using a `SharedFlow` ticker would reduce this to one timer feeding all
subscribers:

```kotlin
private val nowTicker: Flow<Long> = flow {
    while (true) { emit(System.currentTimeMillis()); delay(NOW_AND_NEXT_REFRESH_INTERVAL_MS) }
}.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)
```

---

### L-2 · `jumpToDay` uses UTC epoch modulo for "time of day" — off by timezone for non-UTC users

**File**: `EpgViewModel.kt`

```kotlin
fun jumpToDay(dayStartMillis: Long) {
    val currentTimeOfDay = guideAnchorTime.value.mod(DAY_SHIFT_MS)
        .let { if (it < 0L) it + DAY_SHIFT_MS else it }
    updateGuideAnchorTime(dayStartMillis + currentTimeOfDay)
}
```

`mod(DAY_SHIFT_MS)` gives the UTC time-within-day. If `dayStartMillis` is the UTC
midnight of the selected date but the user is in UTC+8, "current time of day" is
8 hours behind local reality. The compound anchor can land on a time a full `tz-offset`
away from what the user expects. Use `ZonedDateTime` arithmetic (same local-time fix as
`G-06` in `EPG_GRID_CODE_REVIEW.md`).

---

### L-3 · `GuideTimelineHeader` uses `SimpleDateFormat` — not `java.time`

**File**: `EpgGridComponents.kt`

```kotlin
val hourFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
```

`SimpleDateFormat` is mutable and not thread-safe. Inside `remember {}` per composition
site it is used from only one thread (composition), so there is no data race, but
`HH:mm` is hardcoded 24-hour which ignores the device's 12/24-hour system setting
(available via `DateFormat.getTimeFormat(context)`). This also applies to `ProgramItem`.

**Fix**: replace with `DateTimeFormatter` or pull the format from the system
`DateFormat.getTimeFormat(context)` so it respects the user's locale setting.

---

### L-4 · `Program.progressPercent` depends on the caller-set `isNowPlaying` flag

**File**: `domain/model/Program.kt`

```kotlin
fun progressPercent(currentTimeMillis: Long = System.currentTimeMillis()): Float {
    if (!isNowPlaying) return 0f
    ...
}
```

`isNowPlaying` is only set by certain code paths (e.g., the streaming path in
`parseStreaming`). Programs produced by the legacy `parse()` method never have it set.
Any UI component that calls `progressPercent()` on a program fetched outside the
now-playing path always gets `0f`, producing a stuck progress bar. The flag is 
superfluous — just compute from the timestamps:

```kotlin
fun progressPercent(currentTimeMillis: Long = System.currentTimeMillis()): Float {
    if (endTime <= startTime || currentTimeMillis < startTime || currentTimeMillis >= endTime) return 0f
    return ((currentTimeMillis - startTime).toFloat() / (endTime - startTime)).coerceIn(0f, 1f)
}
```

---

### L-5 · No EPG refresh rate-limiting or back-off

Manual and automatic EPG refresh can be triggered repeatedly via `refresh()` in
`EpgViewModel`. The per-source `Mutex` serializes concurrent calls but does not
prevent rapid sequential calls. An unhappy user rage-clicking "Refresh EPG" can saturate
the CDN endpoint or the device's network. Add a minimum re-refresh interval check
against `EpgSource.lastRefreshAt` (e.g., 5 minutes) before issuing the download.

---

### L-6 · `EpgUiState` initializes anchor times via `System.currentTimeMillis()` at data-class construction

**File**: `EpgViewModel.kt`

```kotlin
data class EpgUiState(
    val guideAnchorTime: Long = System.currentTimeMillis(),
    val guideWindowStart: Long = System.currentTimeMillis() - EpgViewModel.LOOKBACK_MS,
    val guideWindowEnd: Long = System.currentTimeMillis() + EpgViewModel.LOOKAHEAD_MS
)
```

These three `currentTimeMillis()` calls are evaluated at (potentially) different
instants, so `guideWindowStart` and `guideWindowEnd` can disagree with
`guideAnchorTime`. In practice the difference is sub-millisecond, but unit tests may
observe skew. Initialise all three from a single captured `now` constant.

---

## Test Coverage Gaps

| Area | Status | Gap |
|---|---|---|
| `XmltvParser` — timezone handling | Tested (offset + +0300) | No test for no-offset local-time, or the digit-strip last-resort |
| `XmltvParser` — BOM / encoding | Not tested | UTF-16 BOM, ISO-8859-1 encoded real feeds |
| `EpgResolutionEngine` — ID trimming | Not tested | Leading/trailing spaces in `xmltvChannelId` |
| `searchPrograms` LIKE wildcards | Not tested | `%` and `_` in query string |
| `refreshSourceInternal` failure recovery | Not tested | Parse crash after delete — empty state |

---

## Prioritized Fix Plan

| Priority | Issue | Effort |
|---|---|---|
| 🔴 Critical | C-2 — Stage-then-swap for source refresh | M |
| 🔴 Critical | C-4 — Local-time UTC assumption + digit-strip drops tz | M |
| 🟠 High | H-2 — LIKE wildcard escape in search | S |
| 🟠 High | H-3 — Empty guide window during refresh | M (see C-2) |
| 🟠 High | H-4 — ID-index exact match no normalization | S |
| 🟠 High | H-5 — `getNowAndNext` next-program selection | S |
| 🟡 Medium | M-1 — `lastRefreshAt` at start not success | S |
| 🟡 Medium | M-2 — `getOverrideCandidates` in-memory load | M |
| 🟡 Medium | M-3 — No debounce on guide search | S |
| 🟡 Medium | M-6 — Per-channel resolution failure logging | S |
| 🟡 Medium | M-7 — Deduplicate EPG config constants | S |
| 🟢 Low | L-1 through L-6 | S each |

**Effort key**: S = small (< 2 h), M = medium (2–8 h), L = large (> 8 h)

---

## Improvement Opportunities & Missing Features

The items below are not bugs — they are genuine gaps in functionality or architecture
that would materially improve the user experience or system reliability in production.

---

### Technical Capabilities

#### TC-1 · No HTTP conditional requests — full file re-download on every refresh


Every EPG refresh downloads the full XMLTV file regardless of whether the server's
content has changed. Many CDNs serving EPG files expose `ETag` and `Last-Modified`
headers, making `If-None-Match` / `If-Modified-Since` `304 Not Modified` fast-paths
trivially achievable.

**Suggested approach**: persist the last `ETag` and `Last-Modified` response header
values per `EpgSource` (two nullable `String` columns in `EpgSourceEntity`). On the
next refresh send conditional headers:

```kotlin
val request = Request.Builder()
    .url(source.url)
    .apply { source.etag?.let { header("If-None-Match", it) } }
    .apply { source.lastModified?.let { header("If-Modified-Since", it) } }
    .build()
// HTTP 304 → skip parse, just call epgSourceDao.updateRefreshSuccess(sourceId, now)
```

For large feeds (100+ MB) this is the highest-value performance improvement available
with no architecture changes.

---

#### TC-2 · No per-source configurable refresh interval

All EPG sources share a single app-wide refresh cadence. Some XMLTV providers update
hourly; others update weekly. There is no `refreshIntervalHours` field on `EpgSource`
and no per-source job scheduler — a single global `SyncWorker` handles everything.

**Suggested approach**: add `refreshIntervalHours: Int` to `EpgSource` (default 24).
`SyncWorker` or a dedicated `EpgRefreshWorker` should schedule per-source
`PeriodicWorkRequest` instances keyed by source ID and updated whenever the interval
setting changes.

---

#### TC-3 · EPG `<channel>` icon URL never used as provider channel logo fallback

`EpgChannelEntity.iconUrl` is parsed from XMLTV `<channel><icon src="…"/>` elements
and stored in the database, but it is never consulted when a provider channel has no
logo. For M3U providers that don't include `tvg-logo`, this data is a direct substitute.

**Suggested approach**: in `ChannelRepositoryImpl` (or a new `ChannelIconResolver`),
after resolving the channel's own `logoUrl`, fall back to the mapped
`EpgChannelEntity.iconUrl` when the primary logo is null or empty. No network
round-trips required — the data is already local.

---

#### TC-4 · No horizontal virtualization in the program timeline

The program strip for each row renders *all* `ProgramItem` composables for the full
loaded time window as children of a `Box`, even when only 6–8 are visible. With a
7-day window (336 half-hour slots) and 60 channels, the first render allocates up to
20 160 composable slots.

**Suggested approach**: convert the row's inner `Box` to a `LazyRow`, or use a
single-pass `Canvas` draw approach, so only the programs visible in the horizontal
viewport are composed at any given time.

> Also noted in `EPG_GRID_CODE_REVIEW.md` section 2.1.

---

### Data & Integration Gaps

#### DI-1 · EPG-to-catch-up alignment is not validated

The guide marks programs with `hasArchive = true` based on EPG metadata, but there is
no check that the program's `startTime` falls within the channel's actual rewind window
(typically 7 days from the provider). A program aired 10 days ago with `hasArchive =
true` will silently fail when the user attempts to watch it, producing a generic player
error rather than a predictive guide-level "Replay unavailable" message.

**Suggested approach**: add a `catchUpWindowDays: Int` field to `Channel` (populated
from Xtream `tv_archive_duration` or M3U `catchup-days`). Compute archive eligibility
at display time:
```kotlin
val replayAvailable = program.hasArchive &&
    program.startTime >= now - channel.catchUpWindowDays * 86_400_000L
```

---

#### DI-2 · Combined guide ignores hidden categories

In `observeCombinedGuide`, `hiddenCategoryIds = emptySet()` is hardcoded:

```kotlin
hiddenCategoryIds = emptySet(),  // user hidden-category preferences are ignored
```

A user who has hidden adult or regional categories from provider A still sees all their
channels in the combined guide. The fix is to read `PreferencesRepository`
`getHiddenCategoryIds(providerId, ContentType.LIVE)` per-member inside
`observeCombinedGuide` and pass the correct set per provider into the channel filter.

---

#### DI-3 · `providerHasEpg` check is too coarse — generates phantom PROVIDER_NATIVE mappings

In `EpgResolutionEngine`:
```kotlin
val providerHasEpg = programDao.countByProvider(providerId) > 0
```

This returns `true` if *any* programs exist for the provider. Every channel therefore
receives a `PROVIDER_NATIVE` mapping (confidence 0.5) — including channels that have
zero associated programs. These phantom mappings cause the resolution summary to
overcount `providerNativeMatches` and make guide rows show "No schedule" where the
mapping falsely implies EPG coverage exists.

**Fix**: replace the global count with a per-channel check inside the resolution map loop:
```kotlin
// in the channel mapping step, replace step 4 with:
val hasNativeEpg = channelEpgId != null &&
    programDao.countByChannel(providerId, channelEpgId) > 0
if (hasNativeEpg) {
    providerNativeMatches++
    return@map ChannelEpgMappingEntity( ... EpgSourceType.PROVIDER ... )
}
```

---

#### DI-4 · No automatic EPG stale detection at the source level

`isGuideStale` is a ViewModel-level signal computed from whether the current time window
has any usable programs. But an `EpgSource` last refreshed 3 weeks ago shows
`lastError = null` with a 3-week-old `lastSuccessAt` — no TTL or staleness flag warns
the user or the resolution engine before guide reads fail.

**Suggested approach**: add a computed `isStale` check (or a DAO query):
```kotlin
val EpgSource.isStale: Boolean
    get() = System.currentTimeMillis() - lastSuccessAt > refreshIntervalHours * 3_600_000L * 2
```

Surface stale sources with a warning badge in `SettingsEpgSection`. Optionally, the
resolution engine could prefer non-stale sources when multiple candidates match, and
`EpgResolutionSummary` could include a `staleSourceCount` field.

