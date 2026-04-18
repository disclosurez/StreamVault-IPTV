# Recording System — Comprehensive Review

**Date:** 2026-04-16  
**Scope:** All recording-related code across `domain/`, `data/`, `app/` modules

---

## 1. Architecture Overview

The recording stack consists of:

| Layer | Files |
|---|---|
| Domain model | `RecordingModels.kt` (enums, request/item/storage DTOs) |
| Domain interface | `RecordingManager.kt` |
| Domain use case | `ScheduleRecording.kt` |
| Data implementation | `RecordingManagerImpl.kt` |
| Data support | `RecordingSupport.kt`, `RecordingConflictDetector.kt` |
| Capture engines | `RecordingCaptureEngine.kt` (TS + HLS) |
| Scheduling | `RecordingAlarmScheduler.kt`, `RecordingAlarmReceiver.kt` |
| Foreground service | `RecordingForegroundService.kt` |
| Background worker | `RecordingReconcileWorker.kt` |
| Boot restore | `RecordingRestoreReceiver.kt` |
| Source resolver | `RecordingSourceResolver.kt` |
| Persistence | `RecordingEntities.kt`, `RecordingDaos.kt` |
| UI | `SettingsRecordingComponents.kt`, `SettingsRecordingActions.kt` |
| Player wiring | `PlayerViewModelActions.kt`, `ScheduleRecording.kt` use-case |

---

## 2. Critical Bugs

### 2.1 TOCTOU Race on Simultaneous Recording Start

**Files:** `RecordingManagerImpl.kt` — `validateRecordingWindow()`, `startManualRecording()`, `scheduleRecording()`  
**Severity:** HIGH

`validateRecordingWindow()` reads overlapping DB rows, checks the count, and returns. The actual `recordingRunDao.insert()` happens afterwards with no mutex or transaction. Two concurrent `startManualRecording` / `scheduleRecording` calls can both pass the window check and both be inserted, potentially exceeding `maxSimultaneousRecordings`.

```kotlin
// validateRecordingWindow() — read-only check
val overlapping = recordingRunDao.getOverlapping(startMs, endMs) ...
if (overlapping.size >= storage.maxSimultaneousRecordings) return "conflict"

// — gap here — no mutex, another call can pass the check simultaneously

recordingRunDao.insert(run)   // inserted by two concurrent callers
```

**Fix:** Wrap the validate → insert → schedule-alarm sequence inside a `Mutex` (or a Room `withTransaction` that includes the overlap query).

---

### 2.2 `retryRecording()` Crashes if Schedule Window Has Expired

**File:** `RecordingManagerImpl.kt` — `retryRecording()`  
**Severity:** HIGH

`retryRecording` builds a `RecordingRequest` with:

```kotlin
scheduledStartMs = maxOf(System.currentTimeMillis() + 2_000L, schedule.requestedStartMs),
scheduledEndMs   = schedule.requestedEndMs   // unchanged from original
```

If the original schedule has already passed its end time, `scheduledStartMs > scheduledEndMs` and `RecordingRequest.init` throws `IllegalArgumentException`. The enclosing `withContext` does not use `runCatching`, so the exception propagates uncaught into the caller's coroutine scope (seen in `SettingsRecordingActions.retryRecording`) and crashes the coroutine silently; the UI receives no actionable error message.

**Fix:** Wrap the body of `retryRecording` in `runCatching { ... }.fold(...)` (matching `startManualRecording` / `scheduleRecording`), and add an explicit guard returning an error when `requestedEndMs <= now`.

---

### 2.3 Orphan DB Row Left on `startManualRecording` Capture Failure

**File:** `RecordingManagerImpl.kt` — `startManualRecording()`  
**Severity:** MEDIUM

After `recordingRunDao.insert(run)` and `alarmScheduler.scheduleStop(...)` succeed, if `startCapture()` returns `Result.Error`, an `IllegalStateException` is thrown, caught by `runCatching`, and returned as `Result.error`. The row is left in the DB with `status = RECORDING` and no active coroutine job. `reconcileRecordingState()` will attempt to restart it via `RecordingForegroundService.startCapture()` indefinitely, potentially causing repeated useless foreground-service churn until the end alarm fires.

**Fix:** On `startCapture` failure inside `startManualRecording`, immediately mark the run as `FAILED` via `markRunFailed()` before propagating the error.

---

### 2.4 `stopRecording` Marks Partial Writes as COMPLETED

**File:** `RecordingManagerImpl.kt` — `stopRecording()`  
**Severity:** MEDIUM

```kotlin
status = if (run.bytesWritten > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED,
```

`bytesWritten` is written by `updateRunProgress()`, which is called asynchronously during capture. Between the first bytes flowing from the network and the first DB progress update, the field reads `0`. A user who immediately stops a just-started recording gets `CANCELLED`. Conversely, the inverse corner case is meaningful: a recording where bytes were written but the stream was cut (network drop) and then the user presses stop would be marked `COMPLETED`, even though the file is truncated. The distinction silently misrepresents the recording quality to the user.

---

## 3. Race Conditions

### 3.1 `promoteScheduledRecording` Is Not Serialized

**File:** `RecordingManagerImpl.kt` — `promoteScheduledRecording()`  
**Severity:** MEDIUM

`promoteScheduledRecording` is called from two independent paths for the same `recordingId`:
- The alarm fires → `RecordingAlarmReceiver` → `RecordingForegroundService.onStartCommand` → `manager.promoteScheduledRecording(recordingId)`
- `reconcileRecordingState()` → `RecordingForegroundService.startCapture(context, run.id)` → same service path

The `startCapture()` function has an `isActiveJob` guard, but the promote flow between `resolveRecordableSource()`, `recordingRunDao.update(updatedRun)`, and `startCapture()` is not atomically serialized. Two concurrent promote calls for the same run can both call `resolveRecordableSource()` (making duplicate network requests), both call `recordingRunDao.update()` (last writer wins), and both attempt `startCapture()`. The second call hits `isActiveJob → true` and returns `Result.success(Unit)`, but the state may have been partially mutated by both calls.

**Fix:** Add a per-recording mutex keyed by `recordingId`, or serialize promotion through the existing `activeJobsMutex` with a broader lock scope.

---

### 3.2 `spawnNextRecurringRunIfNeeded` Is Not Transactional

**File:** `RecordingManagerImpl.kt` — `spawnNextRecurringRunIfNeeded()`  
**Severity:** MEDIUM

This function reads `scheduledRuns` from the DB, checks for an existing next-occurrence run, and then inserts a new one — all without a transaction or mutex. If a concurrent alarm triggers `promoteScheduledRecording` for another occurrence of the same recurring rule simultaneously, both calls could fail to see the pending insertion by the other and insert duplicate next-occurrence runs.

---

### 3.3 `updateRunProgress` Has a Read-Modify-Write Race

**File:** `RecordingManagerImpl.kt` — `updateRunProgress()`  
**Severity:** LOW

```kotlin
val run = recordingRunDao.getById(recordingId) ?: return
recordingRunDao.update(run.copy(...))
```

Progress is reported via a flow of callbacks. If two `onProgress` calls arrive with small delay (possible if the coroutine resumes twice before the DB write completes), the second call reads the un-updated row and writes a stale `retryCount`. Room does not serialize these reads/writes. In practice this is low frequency, but `retryCount = maxOf(run.retryCount, progress.retryCount)` could revert a fresh retry count to a stale cached value.

---

## 4. Storage Issues

### 4.1 `retentionDays` Is Never Enforced

**Files:** `RecordingStorageConfig`, `RecordingStorageEntity`, `RecordingStorageState`  
**Severity:** HIGH — Feature gap masquerading as working functionality

`retentionDays` is stored in the DB, exposed in the UI, included in backup/restore — but **no code anywhere deletes recordings older than this threshold**. `DatabaseMaintenanceManager` purges EPG programs, not recording files or recording run rows. The periodic `RecordingReconcileWorker` calls only `reconcileRecordingState()`, which handles status checks and alarm rescheduling, not file retention.

Users who configure a 30-day retention policy will accumulate recordings indefinitely.

**Fix:** Add a `pruneExpiredRecordings()` step inside `reconcileRecordingState()` (or as a dedicated `DatabaseMaintenanceManager` step) that:
1. Queries all COMPLETED/FAILED/CANCELLED runs where `terminalAtMs < (now - retentionDays * 86_400_000)`.
2. Calls `deleteOutputTarget()` for each.
3. Deletes the `recording_runs` row.

---

### 4.2 SAF (DocumentFile) Storage Doesn't Report Available Bytes

**File:** `RecordingSupport.kt` — `resolveStorageDetails()`  
**Severity:** MEDIUM

When a SAF tree URI is configured, `availableBytes` is always `null`:

```kotlin
return Triple(documentTree?.name ?: treeUriString, null, isWritable)
```

The UI shows "available bytes" and the storage card displays the value — but it will always be empty for SAF paths. There is also no pre-flight disk-space check before starting any recording (SAF or plain file), meaning a recording can start even if only 10 MB remain.

**Fix:** Use `DocumentsContract.getDocumentUri()` + `ContentResolver.query(COLUMN_SIZE)` to get available space for a SAF tree, and add a minimum-space guard in `startManualRecording` / `promoteScheduledRecording`.

---

### 4.3 SAF File Collision Silently Overwrites Existing Recording

**File:** `RecordingSupport.kt` — `createOutputTarget()`  
**Severity:** MEDIUM

```kotlin
val existing = documentTree.findFile(fileName)
val document = existing ?: documentTree.createFile("video/mp2t", fileName.removeSuffix(".ts"))
```

If a file with the computed name already exists, it is silently reused as the output target. A SAF `OutputStream` opened in `"w"` mode truncates the file. This means re-recording the same channel in the same minute (exact minute matches pattern `yyyy-MM-dd_HH-mm`) overwrites the previous recording without warning.

**Fix:** Append a counter suffix when a filename collision is detected (`_1`, `_2`, etc.), or generate a UUID suffix for SAF paths.

---

### 4.4 Partial Files Not Cleaned Up on Capture Failure

**Severity:** LOW

When `TsPassThroughCaptureEngine` or `HlsLiveCaptureEngine` throws during capture (network error, storage full, etc.), `markRunFailed()` updates the DB status but does not delete the partially written file. Subsequent retries create a new output file anyway (from the retry's `createPendingRun` → eventual promote). The partial file is left orphaned on storage with no DB reference.

---

## 5. Logic Issues

### 5.1 Retry Against Expired Schedule

**File:** `RecordingManagerImpl.kt` — `retryRecording()`

As noted in §2.2, there is no guard preventing retry when `schedule.requestedEndMs < now`. Beyond the crash risk, even if defensive code were added and an end-time extension were applied, the alarm for the new `scheduledEndMs` would fire at whatever extension time is used — creating ambiguity about what "retry" means for finished programs.

---

### 5.2 Manual Recording End Time Falls Back to 30 Minutes Without UI Notice

**File:** `PlayerViewModelActions.kt` — `startManualRecording()`

```kotlin
scheduledEndMs = currentProgram.value?.endTime ?: (now + 30 * 60_000L),
```

If there is no EPG data (common for M3U providers), the recording silently ends at exactly 30 minutes. The user gets a brief toast but no mention of the 30-minute cap. Channels with live events (sports, concerts) would be cut arbitrarily.

---

### 5.3 `seenSegments` Set Grows Unboundedly in HLS Engine

**File:** `RecordingCaptureEngine.kt` — `HlsLiveCaptureEngine`

```kotlin
val seenSegments = linkedSetOf<String>()
```

For a 4-hour HLS recording at 10-second segment intervals, `seenSegments` accumulates ~1,440 segment URI strings. Each URI can be 200–300 bytes. This is manageable, but for very long recordings (overnight, 8+ hours) on devices with limited memory, this set should be bounded. A sliding window or a bloom filter would cap memory at a fixed size.

---

### 5.4 `cancelRecording` Disables the Schedule but `retryRecording` Ignores That

**File:** `RecordingManagerImpl.kt`

`cancelRecording` sets `schedule.enabled = false`. `retryRecording` reads the schedule entity but creates a new run with `scheduleEnabled = true` regardless. A user who cancels a recurring recording (intending to stop the series) and then retries it will inadvertently re-enable the schedule. There is no guard in `retryRecording` checking whether the parent schedule has been disabled by an explicit cancel.

---

### 5.5 `cancelRecording` Does Not Cascade to Future Pending Occurrences

**Severity:** MEDIUM

For a recurring recording (`DAILY` / `WEEKLY`), `spawnNextRecurringRunIfNeeded` inserts future occurrences into `recording_runs` as they are promoted. `cancelRecording` disables the schedule and the one run, but pending child runs for future occurrences are **not cancelled**. Each will be promoted to RECORDING when their alarm fires, even though the user cancelled the series. `setScheduleEnabled(..., false)` has the same gap: it only toggles a single run's alarm, it does not query and cancel all pending runs sharing the same `recurringRuleId`.

**Fix:** In `cancelRecording` and `setScheduleEnabled(..., false)`, also query `recording_runs WHERE recurring_rule_id = ? AND status = 'SCHEDULED'` and cancel all matching runs.

---

### 5.6 `validateRecordingWindow` Silently Skips Provider Limit for Missing Provider

**File:** `RecordingManagerImpl.kt` — `validateRecordingWindow()`

```kotlin
val providerMaxConnections = providerDao.getById(providerId)?.maxConnections ?: Int.MAX_VALUE
```

If the provider was deleted between scheduling and the alarm firing, `maxConnections` defaults to `Int.MAX_VALUE`, effectively disabling the per-provider connection limit. An expired provider's recording would proceed regardless of account limits. A more defensive behavior would be to fail the recording with `RecordingFailureCategory.PROVIDER_LIMIT`.

---

## 6. Thread Safety

### 6.1 `activeJobs` Map Is Properly Guarded

`activeJobs` uses `activeJobsMutex.withLock { ... }` consistently through `registerActiveJob`, `removeActiveJob`, `isActiveJob`, and `cancelActiveJob`. This is correct. The mutex is a `Mutex` (kotlinx coroutines), which is fair and non-reentrant — no risk of deadlock given the usage pattern.

### 6.2 DB State vs. In-Memory Job State Are Not Synchronized

`observeActiveRecordingCountSync()` counts live coroutine jobs (`activeJobs.values.count { it.isActive }`). `onCaptureFinished` uses this count to decide whether to stop the foreground service. But `observeActiveRecordingCount()` (from `RecordingManager` interface) counts DB rows with `status == RECORDING`. These two counts can diverge:
- A zombie DB row (job died, no `markRunFailed()` cleanup) keeps the DB count high, keeping the notification alive and the service running forever.
- An unexpected process death and restart clears `activeJobs` entirely; reconcile then re-enqueues via the service, but there is a window where the service runs with zero in-memory jobs and a stale DB count.

The foreground service stops on `count == 0` from the DB-backed flow (`observeActiveRecordingCount()`), while `onCaptureFinished` uses the in-memory count. The inconsistency means the service keeps running until a DB observation fires — which is correct long-term, but the `onCaptureFinished` check for `remaining == 0` to call `stopIfIdle()` fires prematurely when the in-memory count reaches 0 while DB rows are still RECORDING. The service then receives the DB update and `collectLatest` stops it cleanly anyway, so in practice this is benign — but it represents conceptual inconsistency.

---

## 7. Error Handling

### 7.1 `inferFailureCategory` Is String-Matching Based

**File:** `RecordingSupport.kt`

```kotlin
"connection" in normalized || "http 401" in normalized || "http 403" in normalized ...
```

Failure classification depends on the human-readable exception message. If OkHttp or a capture engine changes its error message wording, the category silently falls back to `UNKNOWN`. The categories `NETWORK`, `AUTH`, `TOKEN_EXPIRED` etc. are important for UI guidance and retry logic. Using structured exception types (sealed class hierarchy) or explicit HTTP status codes would be more reliable.

---

### 7.2 HLS AES-128 IV Falls Back Silently to All-Zero IV

**File:** `RecordingCaptureEngine.kt` — `decryptAes128()`

```kotlin
val iv = ivHex?.removePrefix("0x")
    ...
    ?.takeIf { it.size == 16 }
    ?: ByteArray(16)  // silent fallback — all zeros
```

If the `#EXT-X-KEY IV=` attribute is absent or has an unexpected format, the IV is silently set to 16 zero bytes. For HLS streams that rely on explicit per-segment IVs (RFC 8216 §4.3.2.4), this produces garbage decryption output. The file is written but cannot be played back. A clear `UnsupportedRecordingException` should be thrown when an IV is expected but cannot be parsed.

---

### 7.3 DASH Detection Falls Through to `RecordingSourceType.TS`

**File:** `RecordingSourceResolver.kt` — `probeAdaptiveType()`

If the network probe fails (`runCatching` returning empty string) and the body prefix is empty, the function returns `RecordingSourceType.TS`. A DASH stream that happened to produce a connection error during sniffing would be passed to `TsPassThroughCaptureEngine`, which would fail silently writing non-TS bytes to the output file. After the full `scheduledEndMs` window, the recording would be marked COMPLETED with a corrupted file.

---

### 7.4 `migrateLegacyStateIfNeeded` Imports "RECORDING" Status as-is

**File:** `RecordingManagerImpl.kt`

Legacy recordings with `status = RECORDING` are inserted into `recording_runs` at their original status. `reconcileRecordingState()` then calls `RecordingForegroundService.startCapture()` for each, trying to resume recordings that were already complete or interrupted. The run has no `resolvedUrl` populated (the legacy JSON didn't carry resolved URLs), so `startCapture` would fail at `run.resolvedUrl ?: return Result.error(...)`. The run is then left dangling in `RECORDING` state without automatic marking as failed. Consider clamping migrated RECORDING entries to `FAILED` during import.

---

## 8. Android Platform Issues

### 8.1 `SCHEDULE_EXACT_ALARM` Permission Revocable on Android 12+

**File:** `RecordingAlarmScheduler.kt`

The app declares `SCHEDULE_EXACT_ALARM`. On Android 12+ (API 31+), users can revoke this permission from Special App Access. The scheduler catches `SecurityException` and falls back to `setAndAllowWhileIdle`, which is **inexact** — it can fire minutes or hours late. For live TV recordings (timed to the minute), this silently degrades scheduling accuracy with no user-visible warning.

Additionally, Android 13 (API 33) introduced `USE_EXACT_ALARM` as an alternative that does not require user grant for certain calendar/alarm applications. Since this is an IPTV player with an explicit DVR use case, that permission may be a better choice. At minimum, the app should check `alarmManager.canScheduleExactAlarms()` before relying on exact timing and surface a prompt if the permission is missing.

---

### 8.2 `foregroundServiceType="dataSync"` May Require Additional Permission on Android 14+

**File:** `data/src/main/AndroidManifest.xml`

Android 14 (API 34) requires `FOREGROUND_SERVICE_DATA_SYNC` permission for `dataSync`-type foreground services. This permission is not declared in either `data/AndroidManifest.xml` or `app/AndroidManifest.xml`. The service will fail to start on Android 14+ without it.

```xml
<!-- Missing from manifests -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

---

### 8.3 `LOCKED_BOOT_COMPLETED` Triggers Room DB Access Before Decryption

**File:** `RecordingRestoreReceiver.kt`

`LOCKED_BOOT_COMPLETED` fires before the user has unlocked the device. If the app uses device-encrypted storage (the default), the Room database is in the DE (device-encrypted) area and may not be accessible via CE (credential-encrypted) paths. Calling `reconcileRecordingState()` at this point could trigger DB access failures. The receiver should check whether CE storage is available or limit the `LOCKED_BOOT_COMPLETED` response to starting minimal bootstrap only, deferring full reconcile to `BOOT_COMPLETED`.

---

### 8.4 `RecordingReconcileWorker` Requires Network for Non-Network Tasks

**File:** `RecordingReconcileWorker.kt`

```kotlin
.setRequiredNetworkType(NetworkType.CONNECTED)
```

The reconcile worker's primary purpose is to mark expired recording rows as FAILED and reschedule alarms. Neither of these operations requires network. The `NetworkType.CONNECTED` constraint prevents the worker from running (for example, after boot in airplane mode), which means recordings that expired while offline keep their stale `SCHEDULED` or `RECORDING` status indefinitely. The network constraint should be removed from the periodic reconcile worker (it's appropriate only if the worker fetched remote data, which it does not).

---

### 8.5 `PendingIntent` Request Code Hash Collision Risk

**File:** `RecordingAlarmScheduler.kt`

```kotlin
private fun requestCode(action: String, recordingId: String): Int =
    31 * action.hashCode() + recordingId.hashCode()
```

UUID strings are large but their `String.hashCode()` is a 32-bit integer. Two different UUIDs can share a hash. If two recordings produce the same `requestCode` for the same action, the second `PendingIntent.getBroadcast` call (with `FLAG_UPDATE_CURRENT`) overwrites the first alarm's extra data, silently redirecting the alarm to start/stop the wrong recording. With `FLAG_UPDATE_CURRENT`, only the extras are replaced — so the wrong `recording_id` is delivered to `RecordingAlarmReceiver`.

**Fix:** Pre-allocate monotonically incrementing alarm request codes persisted in the DB (`alarm_request_code` column), or use a stable hash with lower collision probability (e.g., `abs(recordingId.hashCode() xor action.hashCode() * 31)`). Even better: use two separate action strings no longer and separate PendingIntent buckets to reduce the keyspace shared between start and stop alarms.

---

## 9. Consistency Issues

### 9.1 `RecordingItem.outputPath` and `outputDisplayPath` Are Identical

**File:** `RecordingSupport.kt` — `toDomain()`, `RecordingManagerImpl.kt` — `toStandaloneDomain()`

```kotlin
outputPath = outputDisplayPath,
outputDisplayPath = outputDisplayPath,
```

`RecordingItem` has both `outputPath` and `outputDisplayPath` populated from the same source. `outputPath` appears to be a legacy field (from the old JSON-file state model). In `deleteOutputTarget`, **both** the SAF URI and the `outputDisplayPath` are used for deletion. For SAF recordings, `outputDisplayPath` is a human-readable label like `"Recordings/ChannelName_...ts"`, not a real filesystem path: `File(outputDisplayPath).delete()` is a silent no-op since the path doesn't exist on the filesystem. This is not harmful (the SAF deletion succeeds), but it's confusing and leaves dead code.

---

### 9.2 Backup Import Re-schedules Expired Recording Windows

**File:** `BackupManagerImpl.kt`

```kotlin
if (scheduled.scheduledEndMs <= System.currentTimeMillis()) {
    return@forEach
}
```

Historical recordings with past end times are correctly skipped. But the check is coarse: a recording scheduled in the past with daily recurrence is entirely dropped. There is no attempt to roll the recurrence forward to the next occurrence. After a backup restore, recurring recording series are lost if their last scheduled occurrence was before the restore time.

---

### 9.3 Manual Recording Has No Conflict Checking for Provider Connection Limit

**File:** `RecordingManagerImpl.kt` — `validateRecordingWindow()`

The connection-limit check `overlapping.count { it.providerId == providerId } >= providerMaxConnections` runs for both `startManualRecording` and `scheduleRecording` (both call `validateRecordingWindow`). This is correct. However, the conflict check for scheduled recordings uses `RecordingConflictDetector.findRecordingRunConflict`, while `validateRecordingWindow` uses a raw DB query. These two paths could reach different conclusions if the `scheduleEnabled` flag on a run has been toggled between the check and the query, since `getOverlapping` does not filter by `scheduleEnabled` but `findRecordingRunConflict` does:

```kotlin
// In validateRecordingWindow — does NOT filter by scheduleEnabled:
val overlapping = recordingRunDao.getOverlapping(startMs, endMs)
    .filter { it.status == ... }
    .filter { it.scheduleEnabled }  // ← manual filter applied after fetch

// In spawnNextRecurringRunIfNeeded:
conflict = activeRuns.findRecordingRunConflict(...)  // checks scheduleEnabled too
```

The `validateRecordingWindow` does filter `scheduleEnabled` on the Kotlin side after the DB query, so the semantics match. This is correct but fragile — adding `scheduleEnabled` filtering to the SQL query would be more explicit.

---

## 10. Missing Test Coverage

| Area | Test Status |
|---|---|
| `RecordingConflictDetector` | Covered (`RecordingConflictDetectorTest`) — 4 cases |
| `ScheduleRecording` use case | Covered (`ScheduleRecordingTest`) |
| `RecordingManagerImpl` (all paths) | **Not tested** |
| `TsPassThroughCaptureEngine` | **Not tested** |
| `HlsLiveCaptureEngine` (segment fetch, AES, timeout) | **Not tested** |
| `RecordingSourceResolver` (DASH fallback, probe logic) | **Not tested** |
| `RecordingAlarmScheduler` (hash collisions, fallback) | **Not tested** |
| Retention enforcement | **Not tested** (no enforcement exists) |
| `migrateLegacyStateIfNeeded` | **Not tested** |
| Recurring rollover (`spawnNextRecurringRunIfNeeded`) | **Not tested** |

The conflict detector tests are well-structured and cover the boundary (adjacent window = no conflict), terminal-status ignoring, and recurring skip cases. The use-case tests cover the main error paths. However, the `RecordingManagerImpl` core — the most complex component — has zero unit tests. Given the threading concerns and state transitions documented above, this is the most urgent gap.

---

## 11. Security Notes

### 11.1 Gson Still Used for Header Serialization

**Files:** `RecordingManagerImpl.kt`, `RecordingSupport.kt` — `headersToJson()` / `headersFromJson()`

The project has migrated to `kotlinx.serialization` for Xtream API responses, but `Gson` is still injected into `RecordingManagerImpl` for recording header persistence. `Map<String, String>` serialization via Gson doesn't expose gadget-chain deserialization risks, but it's inconsistent and keeps a Gson dependency. The header map is trivially representable with `kotlinx.serialization`.

---

### 11.2 AES-128 IV Fallback Produces Silent Garbage Output

Covered in §7.2. From a security standpoint, silently falling back to an all-zero IV when the stream provides an explicit IV may produce output that leaks partially decrypted segment data or, more practically, produces a corrupt but not-empty file that appears to have succeeded.

---

### 11.3 `sanitizeRecordingFileName` Allows Dots

Pattern: `[^a-zA-Z0-9._ -]` — dots are allowed. While not a path traversal risk (the filename is resolved against a fixed directory), a channel name of `"..ts"` produces a filename of `"..ts"`, which `File.getName()` would return as `"..ts"` and might confuse directory traversal checks in other tooling. Excluding dots from the allowlist (or limiting to a single dot before the extension) would be more robust.

---

## 12. Summary Table

| # | Area | Finding | Severity |
|---|---|---|---|
| 2.1 | Race | TOCTOU on concurrent recording start | HIGH |
| 2.2 | Logic | `retryRecording` crashes on expired schedule | HIGH |
| 4.1 | Storage | `retentionDays` stored but never enforced | HIGH |
| 8.2 | Android | Missing `FOREGROUND_SERVICE_DATA_SYNC` permission on API 34+ | HIGH |
| 2.3 | Error | Orphan RECORDING row after `startCapture` failure | MEDIUM |
| 2.4 | Logic | `stopRecording` misclassifies based on stale `bytesWritten` | MEDIUM |
| 3.1 | Race | `promoteScheduledRecording` not serialized per recording | MEDIUM |
| 3.2 | Race | `spawnNextRecurringRunIfNeeded` not transactional | MEDIUM |
| 4.2 | Storage | SAF `availableBytes` always null, no disk-space pre-check | MEDIUM |
| 4.3 | Storage | SAF file name collision silently overwrites existing recording | MEDIUM |
| 5.5 | Logic | Cancel does not cascade to future recurring occurrences | MEDIUM |
| 7.1 | Error | Failure category classification is string-match based | MEDIUM |
| 7.3 | Error | Failed DASH probe falls through as TS, produces corrupt file | MEDIUM |
| 7.4 | Error | Migrated RECORDING-status rows left dangling after legacy migration | MEDIUM |
| 8.1 | Android | `SCHEDULE_EXACT_ALARM` revocable, fallback is silent | MEDIUM |
| 8.3 | Android | `LOCKED_BOOT_COMPLETED` triggers DB access before decryption | MEDIUM |
| 8.4 | Android | Reconcile worker requires network for non-network task | MEDIUM |
| 3.3 | Race | `updateRunProgress` read-modify-write is non-atomic | LOW |
| 4.4 | Storage | Partial files not cleaned up on capture failure | LOW |
| 5.1 | Logic | `retryRecording` has no guard against expired end time (pre-crash) | LOW |
| 5.2 | Logic | Manual recording silently caps at 30 min with no UI notice | LOW |
| 5.3 | Logic | `seenSegments` set unbounded for very long HLS recordings | LOW |
| 5.4 | Logic | `cancelRecording` + `retryRecording` re-enables disabled schedule | LOW |
| 5.6 | Logic | Missing provider silently bypasses connection limit | LOW |
| 7.2 | Error | AES-128 IV fallback to all-zeros is silent | LOW |
| 8.5 | Android | PendingIntent request code hash collision risk | LOW |
| 9.1 | Consistency | `outputPath` and `outputDisplayPath` are redundant | LOW |
| 9.2 | Consistency | Backup import drops recurring schedules instead of rolling forward | LOW |
| 11.1 | Security | Gson still used for header serialization | LOW |
| 11.3 | Security | `sanitizeRecordingFileName` allows dots in pattern | LOW |
| 13.1 | Feature | No in-app recordings playback or library screen | GAP — HIGH |
| 13.2 | Feature | EPG-level scheduling requires user to be watching the channel | GAP — HIGH |
| 13.3 | Feature | No pre/post recording padding | GAP — HIGH |
| 13.4 | Feature | No stalled-capture watchdog (`lastProgressAtMs` never checked) | GAP — MEDIUM |
| 13.5 | Feature | No automatic retry on transient network failure | GAP — MEDIUM |
| 13.6 | Feature | No proactive low-disk-space warning before scheduled recording | GAP — MEDIUM |
| 13.7 | Feature | No WiFi-only recording restriction | GAP — MEDIUM |
| 13.8 | Feature | HLS always picks highest-bandwidth variant regardless of user pref | GAP — MEDIUM |
| 13.9 | Feature | HLS multi-audio and subtitle tracks silently dropped | GAP — MEDIUM |
| 13.10 | Feature | Output always raw `.ts`, no remux to MP4/MKV | GAP — MEDIUM |
| 13.11 | Feature | No series/keyword-based recurring recording rule | GAP — MEDIUM |
| 13.12 | Feature | No "cancel this occurrence" vs. "cancel entire series" UI | GAP — MEDIUM |
| 13.13 | Feature | Completed recordings not integrated with Continue Watching | GAP — LOW |
| 13.14 | Feature | No recording status indicator in channel cards or home screen | GAP — LOW |
| 13.15 | Feature | No push notification on recording failure | GAP — LOW |
| 13.16 | Feature | No search or filter in recordings list | GAP — LOW |
| 13.17 | Feature | No live rewind → recording integration | GAP — LOW |
| 13.18 | Feature | Conflict resolution surfaces an error string, no resolution UI | GAP — LOW |
| 13.19 | Feature | No DLNA/media-server exposure of recordings folder | GAP — LOW |

---

## 13. Feature Gaps & Missing Functionality

### 13.1 No In-App Recordings Playback or Library Screen

**Severity:** GAP — HIGH

Completed recordings are files on disk or SAF storage, but there is no player integration and no dedicated "My Recordings" screen reachable from the main navigation. The only recording management UI is buried inside Settings. Users cannot browse or watch what they have recorded without leaving the app and using a file manager or external player. This is the most fundamental feature gap for a DVR.

---

### 13.2 EPG-Level Recording Scheduling Not Available

**Severity:** GAP — HIGH

`ScheduleRecording` and `startManualRecording` are only reachable from `PlayerViewModelActions`, requiring the user to navigate to the channel and begin watching before scheduling. A standard DVR allows browsing the EPG grid and pressing Record on any future program without navigating to it first. Additionally, the EPG grid shows no visual indicator (red dot, recording icon) for programs that are already scheduled, so the user has no way to confirm a recording is set without returning to Settings.

---

### 13.3 No Pre/Post Recording Padding

**Severity:** GAP — HIGH

There is no option to start recording N minutes before the scheduled program start or to continue recording N minutes after the scheduled end. EPG program times from IPTV providers are frequently off by 1–5 minutes, and live events (sports, concerts) routinely run over. This is a baseline DVR feature expected by users. The `RecordingRequest` model would need `paddingStartMs` and `paddingEndMs` fields, applied before conflict detection and alarm scheduling.

---

### 13.4 No Stalled-Capture Watchdog

**Severity:** GAP — MEDIUM

`lastProgressAtMs` is written to the DB by `updateRunProgress()` on every progress callback from the capture engines. However, nothing reads this field to detect a stalled capture. If a stream continuously delivers data at an extremely slow rate (a hung IPTV server sending 1 byte/minute), the recording stays in `RECORDING` state, `bytesWritten` increments slowly, and the recording is eventually marked `COMPLETED` with a useless file. A watchdog timer that fires if `lastProgressAtMs` has not advanced for a configurable threshold (e.g., 60 seconds) would catch this class of failure.

---

### 13.5 No Automatic Retry on Transient Network Failure

**Severity:** GAP — MEDIUM

`retryCount` is stored in `RecordingRunEntity` and surfaced in `RecordingItem`, implying retry behavior — but neither `TsPassThroughCaptureEngine` nor `HlsLiveCaptureEngine` has a retry loop. Any network interruption (transient blip, brief server restart) immediately fails the recording permanently via `markRunFailed()`. A backoff retry policy (e.g., 3 attempts with 15-second backoff) inside the capture engines would recover the vast majority of real-world IPTV interruptions without user intervention.

---

### 13.6 No Proactive Low-Disk-Space Warning Before Scheduled Recordings

**Severity:** GAP — MEDIUM

There is no threshold check before a scheduled recording fires. If available bytes have dropped below a safe minimum (e.g., < 1 GB) since the recording was scheduled, the alarm fires, the foreground service starts, and the capture engine writes until storage is exhausted — failing mid-recording. A pre-flight check inside `promoteScheduledRecording` comparing `storage.availableBytes` against a configurable minimum (or a fixed conservative default like 512 MB) would allow failing with a clear `RecordingFailureCategory.STORAGE` and a user notification before any bytes are written.

---

### 13.7 No WiFi-Only Recording Restriction

**Severity:** GAP — MEDIUM

Recordings run on mobile data as readily as on WiFi. A high-bitrate IPTV stream can easily consume several GB per hour. There is no "record only on WiFi" setting, which users with mobile data plans would expect. The `RecordingStorageConfig` model is a natural place to add a `requireWifi: Boolean` flag, with enforcement in `promoteScheduledRecording` via `ConnectivityManager` before starting capture.

---

### 13.8 HLS Capture Always Picks Highest-Bandwidth Variant

**Severity:** GAP — MEDIUM

`HlsLiveCaptureEngine` selects the best variant unconditionally:

```kotlin
val bestVariantUrl = variants.maxByOrNull { it.first }?.second
```

The app already has a per-network quality-cap system for the player (`preferredVideoQuality` persisted per network type). Recording ignores this entirely. A user on a constrained connection who has set a quality cap for WiFi would still record at maximum bitrate, potentially filling storage faster than expected or straining a shared network connection. Recording quality should respect the same preference or expose an independent cap.

---

### 13.9 HLS Multi-Audio and Subtitle Tracks Are Silently Dropped

**Severity:** GAP — MEDIUM

The HLS capture engine processes only the video/audio segment stream selected from the master playlist. `#EXT-X-MEDIA TYPE=AUDIO` alternative renditions (e.g., secondary language audio) and `TYPE=SUBTITLES` tracks are never downloaded or muxed. For multi-language IPTV providers, this means every recording loses secondary audio and subtitles. At minimum a warning should be surfaced; ideally, the engine should download and mux the preferred audio rendition alongside the video stream.

---

### 13.10 Output Always Written as Raw `.ts` — No Container Remux

**Severity:** GAP — MEDIUM

All recordings are written as raw MPEG-TS (`.ts`) files. While VLC and most IPTV players handle `.ts` natively, most consumer devices (Android gallery, smart TVs, DLNA renderers) expect `.mp4` or `.mkv`. Even a passthrough remux using Android's `MediaMuxer` (copying A/V tracks without transcoding) after the capture completes would significantly improve playback compatibility outside the app. This could be offered as an optional post-processing step in `RecordingStorageConfig`.

---

### 13.11 No Series/Keyword-Based Recurring Recording Rule

**Severity:** GAP — MEDIUM

Current recurrence supports `DAILY` and `WEEKLY` at a **fixed time slot**. There is no mechanism to record every episode of a named program regardless of when it airs (a "series link" or "keyword timer" as found in conventional DVRs). EPG data with `Program.title` is already stored; a rule engine that matches program titles against a configured keyword and auto-schedules matching future programs would complete the DVR feature set. The domain model for `RecordingScheduleEntity` would need a `matchRule` field alongside the existing `recurrence`.

---

### 13.12 No "Cancel This Occurrence" vs. "Cancel Entire Series" UI

**Severity:** GAP — MEDIUM

`cancelRecording` cancels a single run and disables its parent schedule. For a recurring series this is the equivalent of cancelling the whole series (once the schedule is disabled, `spawnNextRecurringRunIfNeeded` stops spawning). There is no UI distinction between "skip this one episode" and "stop the entire recurring recording". A "skip occurrence" action would mark just the one run as `CANCELLED` while leaving the schedule enabled and future runs untouched. See also §5.5 which covers the related technical gap of cancellation not cascading to already-spawned future runs.

---

### 13.13 Completed Recordings Not Integrated with Continue Watching

**Severity:** GAP — LOW

If a user starts watching a completed recording and pauses midway, the resume position is not tracked anywhere. The existing `PlaybackHistoryRepository` infrastructure (used for VOD continue-watching) is sufficient to track recording playback if recordings were treated as a playable content type with a stable content ID. Without this, every re-open of a recording starts from the beginning.

---

### 13.14 No Recording Status Indicator in Channel Cards or Home Screen

**Severity:** GAP — LOW

There is no visual indicator on channel cards or in the home screen showing that a recording is currently in progress for a channel, or that a future recording is scheduled. Every mainstream DVR shows at least a red dot on an actively recording channel. `RecordingManager.observeRecordingItems()` is available as a flow; the home and live channel list screens could filter it to decorate the relevant channel cards.

---

### 13.15 No Push Notification on Recording Failure

**Severity:** GAP — LOW

When a scheduled recording silently fails at 2 AM (network dropped, storage full, token expired), the only discovery mechanism is the user opening Settings and checking the recordings list. The foreground service notification disappears when recording ends. A separate `NotificationManager` notification posted by `markRunFailed()` — outside the foreground service channel — with the failure reason and a "Retry" action would make failures visible without requiring the user to actively check.

---

### 13.16 No Search or Filter in the Recordings List

**Severity:** GAP — LOW

The recordings list in Settings renders a flat `LazyColumn` of all `RecordingItem` entries with no search input, status filter, or sort options. With any meaningful number of recordings (a daily recording rule over weeks produces dozens of entries) the list becomes difficult to use. Status-based filtering (`Scheduled` / `Completed` / `Failed`) and a simple title search would be standard.

---

### 13.17 No Live Rewind → Recording Integration

**Severity:** GAP — LOW

`docs/LIVE_REWIND_PLAN.md` describes a live rewind buffer. When a user is watching live TV and realizes mid-program they want to record it from the start, there is no way to "record from the beginning of the buffer". The recording simply starts from now. Integrating the rewind buffer write path with `RecordingCaptureEngine` would allow capturing already-buffered content into the output file before switching to the live stream, a feature called "catch-up recording" on commercial DVRs.

---

### 13.18 Conflict Resolution Shows an Error String, No Resolution UI

**Severity:** GAP — LOW

When `validateRecordingWindow` detects a conflict, the result is a plain error string surfaced as a player notice or settings toast. There is no dialog offering the user choices such as: keep the existing recording, replace it with the new one, or adjust timing. A conflict resolution dialog — similar to `BackupImportPreviewDialog` — would give the user agency instead of a dead end.

---

### 13.19 No DLNA / Media-Server Exposure of Recordings Folder

**Severity:** GAP — LOW

Recorded files are stored in the app-private external movies directory or a user-chosen SAF folder. Neither is automatically accessible to network media renderers (smart TVs, Kodi, etc.) via DLNA/UPnP. Exposing the recordings folder through an embedded media server (or at minimum documenting that the SAF path should be set to a publicly accessible location) would allow users to watch recordings on other devices without transferring files manually.
