# Mobile task completion: design

Status: phase 1 design (2026-10-05). No SDK or backend code has changed yet.
The design is based on backend source and on probes against staging that do
not change any data. Probes that change state are still pending (see
[Live verification](#live-verification)).

Sources:

- Backend: public fork `github.com/mvexel/maproulette-mobile-backend`, branch
  `feat/mobile-oauth` at `a31e073`, local checkout
  `~/dev/maproulette-mobile-backend-public`. Staging (`mr-api.osm.lol`) runs
  `e23263b` from the private-history repository. Its lifecycle code
  (`TaskController`, `Locking`, `TaskDAL`) matches the public fork.
- Frontend: `~/dev/maproulette3` (`maproulette/maproulette3`, checkout from
  January 2026).

Scope: this covers MapRoulette task resolution only, meaning the task's
status in the MapRoulette database. It does not cover editing OSM data. The
mobile grant must never reach routes that write to OSM (`applyTagFix`,
`/change/...`). OSM upload needs its own design and its own OSM permission.

## 1. Backend facts

### Status codes (`framework/model/Task.scala`)

| Code | Name | Mobile use |
| --- | --- | --- |
| 0 | Created | never sent |
| 1 | Fixed: the user made the fix in OSM themselves | resolution (standard tasks only; see challenge-types.md) |
| 2 | False positive ("Not an issue") | resolution |
| 3 | Skipped | see the decision on [Skip](#skip) |
| 4 | Deleted | **never** (see the warning below) |
| 5 | Already fixed: someone else fixed it in OSM before the user saw the task | resolution |
| 6 | Too hard ("Can't complete") | resolution |
| 7 | Answered | never sent (questions only) |
| 8 | Validated | never sent |
| 9 | Disabled | never sent |

Review status values: -1 not requested, 0 requested, 1 approved,
2 rejected, 3 assisted, 4 disputed, 5 unnecessary, 6 approved with
revisions, 7 approved with fixes after revisions.

**Warning:** `Task.isValidStatusProgression` returns `true` for a change to 4
(Deleted) or 9 (Disabled) from any status, and `setTaskStatus` does not check
project permissions. Any authenticated user can therefore set a task to
Deleted or Disabled through `PUT /task/:id/4`. The mobile gate must allow
only codes 1, 2, 5 and 6 (and 3 if chosen), using the route pattern.

### Locking model (`framework/mixins/Locking.scala`)

- Locks live in the `locked` table, keyed by item. Bundles use a single row
  whose `bundled_tasks` lists the other member tasks.
- **A user can hold only one edit lock at a time**, enforced by a partial
  unique index. If you start task B while you hold task A, you get a 409 and
  must release A explicitly. The server never swaps the lock silently.
- If you start a task that you already lock, `locked_time` is reset. A
  repeated start is therefore idempotent and also works as a refresh.
- Expiry: `maproulette.task.lock.expiry` (default `1 hour`) is enforced
  only by the `cleanLocks` job, which runs every `1 hour` with an initial
  delay of 1 minute. **An expired lock is still a valid lock until the job
  deletes it.** In practice a lock lasts 1–2 h after its last start or
  refresh. Until the job runs, the owner can still refresh it and other
  users are still blocked.
- The server does not check task status when locking. You can lock a task
  that is already completed or deleted.
- A paused challenge returns 400 from start and from status writes.

### Routes and actual behavior

All paths are under `/api/v2`. Authentication uses the `apiKey` header,
the session cookie or, with the patch, a mobile bearer token. Without
credentials, every route below returns
`401 {"status":"NotAuthorized",...}`; this was confirmed on staging. Guest
users get 403 on status writes.

| Operation | Route | Success | Behavior / errors (from source) |
| --- | --- | --- | --- |
| Start (lock) | **`GET /task/:id/start`** | 200: task JSON + `lockPrimaryTaskId`, `lockBundledTasks` | 404 if the task, challenge or project is missing. 400 if the challenge is paused. **403** `"Task is currently locked by user <displayName>"` if another user holds the lock. **409** `{status:"Conflict", lockedTaskId, parentId, parentName, bundledTasks, startedAt}` if the caller holds a *different* task. A repeat by the owner refreshes `locked_time`. Sends a WebSocket `taskClaimed` event. |
| Refresh lock | **`GET /task/:id/refreshLock`** | 200: task JSON + lock fields | **403** if the caller does not own the lock, including when the lock is gone (`"Lock on item ... does not exist"`) or held by someone else. 404 if the task is missing. |
| Release | **`GET /task/:id/release`** | 200: task JSON (always) | Unlock errors (not locked, locked by another user) are **logged and swallowed, so the route still returns 200**. Effectively idempotent. 404 only if the task is missing. |
| Skip (new style) | `POST /task/:id/skip` | **204** | Increments `skip_count` without any check, leaves status unchanged and releases the caller's lock if it holds one (errors are swallowed). The route is not idempotent because the count goes up on every call. |
| Set status | `PUT /task/:id/:status[?requestReview=bool&tags=a,b]`, optional JSON body = `completionResponses` | **204** (Swagger says 304) | Described below. |
| Comment | `POST /task/:id/comment[?actionId=]` body `{"comment": "..."}` | 201 | A separate write. The web UI posts it *after* the status write. |
| Unlock request | `PUT /task/:taskId/unlock/request` | 200 | **Surprise: this acquires the lock for the caller if the task is free** (it calls `lockItem`) and otherwise notifies the holder. Never expose this route. |

**Status write** (`TaskController.setTaskStatus` →
`customTaskStatus` → `TaskDAL.setTaskStatus`):

1. 400 if the code is not a valid status. 404 if the task is missing. 403
   for a guest user. 400 if the challenge is paused.
2. The progression check (`isValidStatusProgression`, with
   `allowChange = (completedBy == caller)`):
   - Writing the current status again is always valid, **even for a
     different user**.
   - From Created, any status is valid.
   - From Skipped (3) or Too hard (6), the task can move to 1, 2, 3, 5, 6
     or 7.
   - From 1, 2, 5, 7 or 8, the task can move to another completed status
     only if the caller completed it. Otherwise the result is
     `400 "Invalid task status supplied."` (with one exception: from 2 the
     task can move to 1 for anyone).
3. `UPDATE tasks ... WHERE locked by caller OR not locked`. **The write
   does not require a lock.** If another user holds the lock, the result is
   `403 "This task is locked by another user..."`.
4. If you set Skipped (3) on a task that is not Created, the status is left
   unchanged. Only an action is logged and the lock is released.
5. The update sets `mapped_on`, `completed_by = caller`,
   `completion_responses` and `completed_time_spent` (measured from the
   lock's `created`, so a lock taken at start gives an accurate time).
6. Review: if `requestReview` is absent, the server uses the user's
   `settings.needsReview` (or the config default). Any status other than
   3, 4 or 9 then gets review status 0 (requested) plus a history row.
   **`requestReview=false` overrides even a user whose review is
   mandatory**, so the mobile gate must not let it through.
7. The caller's lock is released. A WebSocket release event is sent and the
   challenge popularity and user score are updated. If OSM changeset
   matching is enabled, it runs asynchronously.
8. `tags` (comma-separated) adds MapRoulette task tags. These are not OSM
   tags.

**Possible stale read after completion (to be verified live).**
`setTaskStatus` writes `task.copy(status, modified, review...)` into the
shared task cache (Caffeine, 15 min). That copy does **not** update
`completedBy` or `mappedOn`. If so, `GET /task/:id` right after completion
can show the new `status` but the old `completedBy`/`mappedOn` for up to
15 minutes. `lockedBy` on the single-task read comes from the database, so
it is always fresh. If the live test confirms the stale read, the backend
fork should also cache `completedBy`/`mappedOn` (or invalidate the entry)
as part of this slice. Until then the SDK verifies with `status` and
`lockedBy`, and checks `completedBy` only when it is present and not stale.

### Mutating GET routes (do not call these as reads)

`/task/:id/start`, `/task/:id/release`, `/task/:id/refreshLock`,
`/task/:id/tags/update`, `/task/:id/review/start` (review claim lock),
`/task/:id/review/cancel`, `/tasks/review/next`,
`/challenge/:id/matchChangesets`. In addition, `PUT /task/:id/unlock/request`
locks the task even though its name suggests a request.

### Where Swagger is wrong or incomplete

- The status write returns 204, not the documented "304 No Content".
- The start route does not document that a lock held by another user
  returns 403. The 409 response in Swagger means "the caller already holds
  a different task".
- Swagger says refresh returns 403 only for "not owner". The same 403 also
  covers an expired lock that the job has already deleted.
- Release never fails for lock reasons, but Swagger implies it can.
- Swagger does not mention that the status write works without a lock, nor
  that any user can move a task to 4 or 9.
- Unlock request takes the lock if the task is free.

## 2. How the web frontend sequences calls

From `WithLockedTask`, `TaskPane` and `WithCurrentTask`:

1. When the task view mounts, the UI calls `GET start`. If that fails, the
   UI switches to read-only mode and shows the failure details. A 409 can
   be resolved by releasing the other task.
2. While the task pane is open, the UI calls `GET refreshLock` every
   **10 minutes**. If that fails, it shows a lock-failure dialog.
3. To complete, the UI calls `PUT /task/:id/:status` with
   `requestReview` (from the user or challenge setting), `tags` and a body
   of `completionResponses`. It updates the local store optimistically and
   reloads if there is an error. Cooperative tag-fix tasks with status
   Fixed use `applyTagFix` instead, **which writes to OSM**.
4. After the status write, it runs in parallel: `POST comment` (if a
   comment was entered), refresh the user score and refresh the challenge
   actions. Then it loads the next task.
5. Skip: this frontend checkout sends status 3 through the status route.
   The backend is newer and offers `POST /skip`, which "preserves status".
6. When the user navigates away or switches tasks, the UI calls
   `GET release`. Errors are ignored, and `localStorage` lock markers are
   synchronized across tabs.

## 3. Lifecycle state machine (client side): late locking

**Decision (user, 2026-10-05): mobile locks late.** Opening or viewing a
task never locks it. The lock is taken only when the user commits to a
resolution, and it lasts for that one sequence:

```text
commit(resolution):  start ──▶ PUT status ──▶ (server releases the lock as part of the status write)
                       │            └─ failure ─▶ release (best effort) ─▶ re-read
                       └─ 403 other ─▶ "Someone else is working on this task. Pick another one."
                       └─ 409 own    ─▶ stale lock from an interrupted commit: re-read that task, release it, retry once
skip:                POST /skip (no lock needed; it releases any lock the caller holds)
future OSM upload:   start ──▶ server-side upload ──▶ status ──▶ release        (see §12)
```

States the app tracks for one task:

```text
 Viewing ──commit──▶ Committing ──204──▶ Resolved (fresh re-read)
   ▲                    │ 403 other ──▶ TakenByOther (back to the list)
   │                    │ 400       ──▶ AlreadyResolved / InvalidTransition (re-read)
   │                    │ network/5xx/timeout ──▶ Unknown ── re-read (§5) ──▶ Resolved | Viewing
   └────────────────────┘ 401 ──▶ SessionExpired (refresh the token, else sign in; nothing is locked)
```

Rules:

- The SDK holds no lifecycle state. It exposes plain operations and maps
  each result to a typed result or error. The app owns the sequence above,
  or uses an optional `commitResolution(id, resolution)` helper that runs
  start → status → release-on-failure. This keeps Kotlin and Swift
  identical and easy to test.
- A resolve always runs inside a start that the app has just made, even
  though the server accepts a status write without a lock. Starting first
  is what turns a concurrent mapper into a clean 403 instead of a race.
- Locks live for seconds. In normal operation nothing is left locked when
  the user leaves a task, signs out or backgrounds the app.
- `refreshTaskLock` stays in the SDK, but only for a possible future
  long-edit flow (for example in-app OSM editing). The v1 demo does not
  call it.

Trade-offs of late locking:

- **No early warning while viewing.** A user can read a task that someone
  else is about to finish, and only learns at commit time. Mitigation: show
  `lockedBy` from the single-task read (fresh from the database) as
  "Someone is working on this task". Map and list markers can do the same
  where the data includes lock state. Re-read when the task screen
  resumes.
- **`completed_time_spent` becomes meaningless.** The server measures it
  from the lock's creation, so with a lock of a few seconds the value is
  near zero. Without a lock the server uses `baseCompletedTimeSpent`.
  MapRoulette uses this field in its statistics. Options: accept it, or
  later add a gated optional "started viewing at" hint. That second option
  needs a backend change and is not in v1.
- Real-time WebSocket claim notifications to web users are effectively
  absent, which matches how short the work is.

## 4. HTTP contract per SDK operation

| SDK operation | Request | Success | Outcome mapping |
| --- | --- | --- | --- |
| `startTask(id)` | `GET /task/{id}/start` | 200 → `TaskLock(task, primaryTaskId, bundledTaskIds)` | 403 → `LockedByOtherUser(message)`. 409 → `AlreadyHoldingTask(lockedTaskId, challengeId?, challengeName?, startedAt?)`. 400 → `validation` (paused). 404 → `notFound`. |
| `refreshTaskLock(id)` | `GET /task/{id}/refreshLock` | 200 → `TaskLock` | 403 → `LockLost`. 404 → `notFound`. |
| `releaseTask(id)` | `GET /task/{id}/release` | 200 → `Unit` | Always succeeds unless 404, 401 or a network error. Not a proof of ownership. |
| `skipTask(id)` | `POST /task/{id}/skip` (no body) | 204 → `Unit` | 404 → `notFound`. |
| `resolveTask(id, resolution)` | `PUT /task/{id}/{code}`, no query, no body (v1) | 204 → `Unit` | 403 → `LockedByOtherUser`. 400 → `InvalidTransition` (already completed by someone else, invalid status or paused). 404 → `notFound`. |

`resolution` ∈ {`fixed`=1, `notAnIssue`=2, `alreadyFixed`=5,
`tooHard`=6}. Raw codes are not accepted. Which resolutions to offer for a given task kind (standard, tag fix, change file) is defined in [challenge-types.md](challenge-types.md). Mobile never offers Fixed on cooperative tasks until OSM upload exists (§12). Version 1 leaves out `tags`,
`requestReview`, `completionResponses` and comments. They can be added
later as separate, deliberate extensions (comments need their own gated
route).

Every write sends exactly one credential (an API key or a bearer token),
never both. The bearer filter rejects requests that mix credentials.

Shared error codes (existing `ErrorKind`): 401 → `authentication`, 403 →
`permission` (for the gate's `insufficient_scope`) or a lifecycle-specific
error from the table, 409 → `conflict`, 5xx → `server`, transport failure →
`network`. Bodies are parsed only for 409 (lock details) and the 403 lock
message. Error descriptions never include credentials or the raw body
(this is existing SDK policy).

## 5. Ownership, conflicts, retries and idempotency

The client treats a fresh `GET /task/{id}` as the truth. The fields that
matter are `status`, `lockedBy` (null when unlocked), `completedBy` and
`review.reviewStatus`.

| Operation | Safe to retry blindly? | Outcome uncertain (timeout, connection reset, 5xx after send) |
| --- | --- | --- |
| start | Yes. A repeat by the owner refreshes. | Re-read. If `lockedBy == me`, you hold the lock. |
| refresh (future long-edit flow only) | Yes | Re-read `lockedBy`. |
| release | Yes. Always 200. | Re-read. If `lockedBy != me`, the release is done. |
| skip | **No**, because `skip_count` goes up again | Re-read. If `lockedBy != me`, treat the skip as done. Do not resend. |
| resolve | **No**. Verify first. | Re-read (see below). |

Verify after an interrupted resolve, given a target status `s`:

1. If `status == s` and (`completedBy == me` or `completedBy` is unknown or
   stale) and `lockedBy == null`, the resolve succeeded. Do not resend.
   Resending the same status is accepted by the server, but it logs a
   second status action and rewrites `mapped_on`.
2. If `status` is unchanged and `lockedBy == me`, the write was not
   applied. Resending is safe.
3. If `status` is unchanged and `lockedBy == null`, the write was not
   applied and the lock is gone. Call start again (a 403 or 409 here is a
   real conflict), then resend.
4. If `status` is unchanged and `lockedBy == other`, another user has the
   task (`LockedByOtherUser`). Do not resend.
5. If `status` is a different completed status, or `completedBy == other`,
   someone else resolved the task (`AlreadyResolved`). Stop.

The server lets a different user write the *same* status again and take
over `completed_by`. This is a known weakness of the server. Rule 5 and
the requirement to start the task first keep the mobile client out of
that path.

A rejected write (any 4xx) never changes task status. Source check: every
4xx path throws before or inside the single guarded `UPDATE`. The SDK
never retries a 4xx.

## 6. Cancellation and release rules

- With late locking, a lock exists only during a commit. If any step after
  `start` fails, the app calls `release` once (best effort) before showing
  the error. Failures are ignored, and the server-side expiry (1–2 h) is
  the backstop. Leaving a task without committing needs no call.
- Cancelling the coroutine or Swift `Task` while a write is in flight leaves
  the outcome unknown. Do not assume rollback. On the next screen load,
  re-read and apply the rules above.
- The app does not refresh locks in v1 (see §3).
- If the app is killed between `start` and the status write, the next
  commit on another task returns 409 with `lockedTaskId`. Under late
  locking that lock can only come from an interrupted commit by this same
  user. The commit helper re-reads task N and applies §5:
  - If N was resolved, there is nothing to release.
  - Otherwise it calls `release(N)`.
  It then retries the new start once. The SDK's plain `startTask` never
  releases another task by itself.

## 7. Account switching

Locks and completions belong to the MapRoulette user behind the credential.

1. Under late locking, nothing is locked between commits, so a sign-out or
   switch needs no release. If a commit is in flight, wait for it or cancel
   it, and apply §5 using the *old* account. Then revoke or remove the old
   token.
2. After the switch, discard all lifecycle state. The old account's lock
   persists until its expiry if the release failed. While it lasts, the new
   account gets `LockedByOtherUser` on that task. This is expected.
3. A client built for one account must never send another account's
   pending write. Pending writes carry the account identity (the user id
   from `/oauth/mobile/me` or `getCurrentUser`). If the identity changes
   between when a write is queued and when it is sent, the write is
   dropped.

## 8. Required backend changes (mobile OAuth patch)

Current state: there is one hard-coded scope `tasks:read`, compared with
exact string equality at authorize, at token exchange and refresh, and in
the filter. `MobileReadRoutes` allowlists GET reads plus `PUT markers/box`.
Everything else gets `403 insufficient_scope` (confirmed on staging for
`GET /task/:id/start` and `PUT /task/:id/1`). There is no CSRF filter in the
chain, so a bearer PUT or POST reaches the controller.

Smallest addition:

1. **Scopes as a set.** Parse space-delimited scopes. Supported scopes are
   `tasks:read` and `tasks:write`. A client config lists its allowed scopes
   (staging Android client: both). The authorize request must be a subset
   of those, and `tasks:write` requires `tasks:read`. The consent page
   names the write permission in plain language, for example "Mark
   MapRoulette tasks as fixed, not an issue, already fixed or too hard, and
   lock or skip tasks. Does not edit OpenStreetMap." A refresh can never
   widen the scope. Existing `tasks:read` grants keep working unchanged. The current consent text ("It cannot edit tasks") must change for write grants.
2. **A write allowlist checked against the grant's scopes**
   (`MobileWriteRoutes`, used only if the grant has `tasks:write`):
   - `GET /api/v2/task/[0-9]+/(start|release)`. Add `refreshLock` only
     when a long-edit flow exists; late locking does not need it.
   - `POST /api/v2/task/[0-9]+/skip`
   - `PUT /api/v2/task/[0-9]+/(1|2|5|6)`
   - For all write routes: **reject any query string** (this blocks
     `requestReview=false` and `tags`) and **reject a non-empty body**.
     Version 1 does not use `completionResponses`.

   These start/release/refresh GET routes are deliberately excluded from
   the read allowlist, so a `tasks:read` token still cannot lock a task.
3. Explicitly *not* allowed: `lockBundle`, `taskBundle/*`, `unlock/request`,
   `bundle/unlock`, tags, comments, review routes, `changeset`, any
   `applyTagFix`/`/change` route, bulk routes and status codes 0, 4, 7, 8
   and 9.
4. **Cache freshness** (if the live test confirms the stale read): in
   `TaskDAL.setTaskStatus`, put `completedBy = Some(user.id)` and
   `mappedOn = now` in the cached copy (or invalidate it). This is a small,
   upstream-worthy fix.
5. Tests: filter specs for every allowed and denied route/method/scope
   combination, query and body rejection, an old read-only token denied on
   start, and legacy apiKey/cookie requests unaffected. Add an integration
   test of start → resolve → read with a bearer user.

The API-key and web flows are unaffected because the filter passes through
any request without a bearer token.

## 9. Proposed public API (matched, idiomatic)

Kotlin (suspend functions on `MapRouletteClient`):

```kotlin
enum class TaskResolution(val code: Int) { FIXED(1), NOT_AN_ISSUE(2), ALREADY_FIXED(5), TOO_HARD(6) }

data class TaskLock(val task: Task, val primaryTaskId: TaskId, val bundledTaskIds: List<TaskId>)

suspend fun startTask(id: TaskId): TaskLock
suspend fun refreshTaskLock(id: TaskId): TaskLock
suspend fun releaseTask(id: TaskId)
suspend fun skipTask(id: TaskId)
suspend fun resolveTask(id: TaskId, resolution: TaskResolution)
// Late-locking helper: start → resolve → release on failure; on 409, recover the stale own lock once (§6)
suspend fun commitResolution(id: TaskId, resolution: TaskResolution)

// Lifecycle errors: MapRouletteException with kind + a sealed detail
sealed interface LockProblem {
    data class LockedByOtherUser(val message: String?) : LockProblem
    data class AlreadyHoldingTask(val lockedTaskId: TaskId, val challengeId: ChallengeId?,
                                  val challengeName: String?, val startedAt: String?) : LockProblem
    data object LockLost : LockProblem
    data object InvalidTransition : LockProblem
}
```

Swift (async throws on `MapRouletteClient`):

```swift
public enum TaskResolution: Int, Sendable { case fixed = 1, notAnIssue = 2, alreadyFixed = 5, tooHard = 6 }
public struct TaskLock: Sendable { public let task: MapRouletteTask; public let primaryTaskID: TaskID; public let bundledTaskIDs: [TaskID] }

public func startTask(_ id: TaskID) async throws -> TaskLock
public func refreshTaskLock(_ id: TaskID) async throws -> TaskLock
public func releaseTask(_ id: TaskID) async throws
public func skipTask(_ id: TaskID) async throws
public func resolveTask(_ id: TaskID, as resolution: TaskResolution) async throws
public func commitResolution(_ id: TaskID, as resolution: TaskResolution) async throws

public enum LockProblem: Sendable, Equatable {
  case lockedByOtherUser(message: String?)
  case alreadyHoldingTask(lockedTaskID: TaskID, challengeID: ChallengeID?, challengeName: String?, startedAt: String?)
  case lockLost, invalidTransition
}
// MapRouletteError gains `public let lock: LockProblem?`
```

Read model additions on both platforms (`Task` and `MapRouletteTask`):
`lockedBy: Long?`, `completedBy: Long?`, `mappedOn: String?`,
`reviewStatus: Int?`. Add a convenience
`verifyResolution(task, target, me) -> Applied | NotApplied(lockHeld) | TakenByOther`
that applies the rules in §5 to a fresh read, so apps do not have to
reimplement them. `TaskStatus` keeps its existing names.

The transport gains PUT and POST with an empty body, plus a no-retry flag
for writes. Writes are never retried automatically by the SDK.

**As implemented (2026-10-05).** Both SDKs follow this section with these
differences: the detail type is `WriteProblem` (adds `InsufficientScope` and
`OutcomeUnknown`; HTTP `kind` is unchanged, so a status-write 400 is `http` +
`InvalidTransition`); `verifyResolution` returns one of `applied`,
`notAppliedLockHeld`, `notAppliedUnlocked`, `lockedByOther`, `resolvedByOther`;
`mobileSupport()`, `allowedResolutions()` and `canSkip()` are task members
without a challenge argument, because the task payload decides the kind;
`Challenge` exposes the raw `cooperativeType` plus `cooperativeKind` rather than
a `ChallengeKind`; `ElementTagEdit.element` is null for `createElement`; and
`templateProperties()` follows the web UI's default id fields only (it ignores
`osmIdProperty`, as the web templating does) and does not infer `#osmType` from
geometry. Bearer identities expose their scope set. `verifyResolution` cannot
detect the possibly stale cached `completedBy` (§1); a stale value makes it
answer `resolvedByOther`, which fails safe (no resend). The SDK never resends a
write; OkHttp connection retries are disabled, but iOS URLSession may itself
retry an idempotent PUT on a dropped reused connection, which the server
accepts as a same-status rewrite.

## 10. Fixture and test plan

Shared synthetic fixtures (`fixtures/` used by both suites), with request
expectations (method, path, no query, no body, a single credential
header):

- `start_200.json` (task + lock fields). `start_403_other.json`.
  `start_409_own.json` (all optional fields present and absent).
  `start_400_paused.json`.
- `refresh_200.json`, `refresh_403_lost.json`. `release_200.json`, and
  release returning 200 even when not owned. `skip_204`.
- `resolve_204` for each resolution (asserts path codes 1, 2, 5 and 6 only).
  `resolve_403_locked.json`. `resolve_400_invalid.json`.
- 401 with `invalid_token` and with the legacy `NotAuthorized` body, both
  mapped to `authentication`. A gate `403 insufficient_scope` mapped to
  `permission` (not to a lock problem).
- Read fixtures with `lockedBy` and `completedBy` set and null, used by
  `verifyResolution` table tests for all five rules in §5.
- Transport: a connection reset after the request was sent on resolve
  produces `network` with no automatic retry. Cancellation during a write
  propagates and no retry happens.
- Credential isolation: writes from client A never carry client B's token.
  No error description contains a credential or a body.

Backend tests: see §8 item 5.

## 11. Android demo UX states

On the task detail screen, for a signed-in user only. The actions are
hidden on the production build unless an API key or a write-scoped session
is present.

| State | UI |
| --- | --- |
| Signed out or read-only token | Read-only detail, with "Sign in to work on this task". |
| Viewing | The resolution buttons allowed for this kind (see [challenge-types.md](challenge-types.md)), plus Skip. No lock is held. If the fresh read has `lockedBy` set to another user, a banner says "Someone is working on this task" and the buttons stay enabled. |
| Committing | Spinner on the chosen button, other buttons disabled. Back navigation is blocked until start → status finishes or fails (normally under a second). |
| Taken by other (403 at start) | "Someone else is working on this task. Pick another one." It returns to the map or list. |
| Stale own lock (409) | Handled silently by the commit helper (§6). Only if that fails: "A previous attempt on task N is still open", with a Retry button. |
| Resolved | A fresh read shows the status label, "Completed by you" (user id/name) and the review status if one was requested. |
| Outcome unknown | "Checking result…": the app re-reads automatically, then shows Resolved, or returns to Viewing with "Try again". |
| Session expired | The app tries a token refresh. If that fails, it shows the sign-in prompt and keeps the task view. No lock is outstanding. |
| Account switched | Lifecycle state is cleared and the detail screen reloads as the new user. |

## 12. Future: OSM upload (design only, kept separate from resolution)

Nothing in this section is part of the task-completion slice. MapRoulette
resolution (§1–§11) never writes to OSM. This section records what an OSM
write path for mobile would need. The preferred direction is that **the
MapRoulette backend uploads with the OSM token it already holds**, behind a
separate narrow mobile scope, so there is one sign-in and no OSM token on
the device.

### What the backend does today

**OSM OAuth scopes requested:**

- Web login (`AuthController`): `osm.oauth2Scope`, which defaults to
  `"read_prefs write_prefs write_api"`. Production therefore holds a token
  that can write. Staging sets it through `MR_OSM_OAUTH2SCOPE`, and
  `conf/mobile-staging.conf` says "OSM grants from this environment are
  read-only". The actual value is in the host environment and was not
  checked.
- Mobile login (`MobileOAuthController.authorize`): **hard-coded
  `scope=read_prefs`**. The OSM access token is used once
  (`MobileOSMIdentity` → `/api/0.6/user/details`) and then **discarded**.
  It is never stored.

**Token storage:** one plaintext column, `users.oauth_token`, per
MapRoulette user. Every web login overwrites it (`UserRepository.upsert`).
The web session cookie carries the same token (`SessionManager.KEY_TOKEN`,
matched with `matchByRequestToken`).

**Mobile provisioning** (`MobileUserProvisioner`):

- An existing user (matched by OSM id) is returned unchanged. A mobile login
  never refreshes or replaces `oauth_token`.
- A new mobile-only user is inserted with `oauth_token = ''`.

So the stored token for a mobile user is one of the following:

- The token from their last *web* login. It may be stale or revoked
  (OSM OAuth2 tokens do not expire, but users can revoke them) and may lack
  `write_api` if the environment reduced scopes.
- Empty, if the user has only ever signed in on mobile.

The backend cannot currently tell which scopes a stored token has.

**Apply flow** (`POST /api/v2/task/:taskId/fix/apply?tags=&requestReview=`,
`TaskController.applyTagFix`):

- Body: `TagChangeSubmission {comment, changes: [{osmId, osmType, version?,
  updates{k:v}, deletes[k]}]}`. The web UI builds `changes` from
  `cooperativeWork` plus optional user edits (`tagChangeSummary`). **The
  backend does not check that `changes` match the task's `cooperativeWork`.**
  Any element and any tags can be submitted.
- If `osm.skipOSMChangesetSubmission` is set, nothing is uploaded and the
  status is set to Fixed only.
- Otherwise `ChangesetProvider.submitOsmChange`:
  1. `PUT /api/0.6/changeset/create` with `created_by=MapRoulette`, the
     comment from the request body and `source` = the challenge's
     `checkinSource`.
  2. Fetch the current elements and merge in the tag updates and deletes
     (`conflateTagChanges`).
  3. `POST .../changeset/:id/upload`.
  4. Close the changeset.
  5. Store `changesetId` on the task.
  6. Only then `customTaskStatus(Fixed)`.

**Errors surfaced to the client:**

| Failure | Response |
| --- | --- |
| OSM 401 (revoked or missing token) | `OAuthNotAuthorizedException` → **401 `NotAuthorized` with a new session**. A client cannot tell this apart from its own MapRoulette credential expiring. |
| OSM 409 version conflict | `ChangeConflictException` → 409 `{status:"Conflict"}` |
| Anything else | 500 |

**Ordering hazard:** the OSM upload happens *before* the MapRoulette status
write. If the status write then fails (for example, another user holds the
lock), the exception is thrown inside a `Future.onComplete` callback. From
reading the source, the HTTP promise is then never completed and the request
hangs until it times out, while the OSM edit is already committed. Change
files (`cooperativeType` 2) have **no** backend upload path; the web UI
sends users to JOSM.

### Approved direction (2026-10-05): upload by the backend, with a narrow scope

1. **A new mobile scope, `osm:tagfix`**, separate from `tasks:write` and
   with its own consent wording, for example "Apply this challenge's
   suggested tag changes to OpenStreetMap as you, with a changeset comment".
   It is requested only when the user first chooses to apply a tag fix
   (incremental consent).
2. **A server-derived mobile endpoint** (for example
   `POST /api/v2/task/:id/fix/apply-suggested`, no body, or only a
   `version` echo):
   - The backend builds the change **from `cooperativeWork` itself**,
     modify/setTags/unsetTags only.
   - It uses the challenge's `checkinComment` and `checkinSource`.
   - It requires the caller to hold the task lock and checks the status
     progression **before** creating the changeset.
   - It writes the status in the same request path, with the promise
     completed on every branch.
   - The generic `fix/apply` route stays off the mobile allowlist.
3. **An OSM token the backend controls for mobile users.** The mobile
   OSM login requests `read_prefs write_api` (only when the client asks for
   `osm:tagfix`). The token is stored *encrypted* in a new mobile table
   linked to the mobile grant family, not in `users.oauth_token`. This keeps
   the "insert-only, don't disturb web credentials" property. Revoking the
   mobile grant deletes it.
4. **Distinct errors:**
   - `401 {error:"osm_reauth_required"}` when OSM rejects the token. The
     MapRoulette session must not be cleared.
   - `409 {error:"osm_conflict", element}` for an OSM version conflict.
   - `502` for OSM being unavailable.
   - A result body that includes `changesetId`.
   Retry rule: never resend automatically. Re-read the task. If `status`
   is 1 and `changesetId` is set, the fix was applied. Otherwise check the
   OSM element version before retrying.
5. **Migration and re-consent:** existing grants (`tasks:read`, and later
   `tasks:read tasks:write`) keep working. To use OSM upload, the user
   goes through the browser flow once more, requesting `osm:tagfix`. OSM
   shows its own consent for `write_api`, and the MapRoulette consent page
   lists the new permission. No silent upgrade, and the refresh token never
   widens scope.

### Alternative: direct upload from the client

The app registers its own OSM OAuth client, gets a second consent from the
user, keeps a `write_api` token on the device and uploads changesets itself.

| | Backend upload (preferred) | Direct from the client |
| --- | --- | --- |
| Sign-ins | One (MapRoulette, which asks OSM) | Two (MapRoulette, plus OSM again) |
| Where the token lives | Server, encrypted | Device secure storage |
| Changeset logic | One implementation (backend) | Re-implemented in Kotlin and Swift |
| Abuse surface | Limited to `cooperativeWork` by the server | Any OSM edit the app can make |
| Coupling MapRoulette status to the OSM edit | One request path | Two systems, client-side two-phase commit |
| OSM usage attribution | `created_by=MapRoulette` | The app's own client |

Direct upload is the better fit only if mobile later supports general OSM
editing (geometry, presets). That needs a full editor design and is out
of scope.

### Required backend changes (only if this is pursued)

- Scope-set support (already needed for `tasks:write`) plus `osm:tagfix`.
- An optional OSM `write_api` request in the mobile authorize step, plus
  encrypted mobile OSM token storage tied to grant revocation.
- A server-derived apply endpoint with lock and progression checks first,
  promise completion on every branch, and distinct OSM error codes.
- Tests: a fake OSM server covering success, conflict, 401 and 5xx after
  changeset create (the changeset is still closed), a status-write failure
  after upload, and an `osm:tagfix` token that cannot reach any other
  route.

## Live verification

Done on staging without changing any data: `/ping` returns 200.
Anonymous `start`, `release`, `refreshLock`, status PUT and skip POST all
return 401 `NotAuthorized` (tested on nonexistent task 999999999). A
well-formed bogus bearer on start or a status PUT returns
`403 {"error":"insufficient_scope"}`, because the gate rejects the route
before checking the token. A bogus bearer on a read returns
`401 invalid_token`.

**Blocked: probes that change state.** These need a staging credential
that can write: an `apiKey` for a staging MapRoulette user (and for a
second user to test lock conflicts). A mobile bearer token cannot write
under the current gate. Staging has no web UI where a user could copy an
API key. No credential was minted and nothing was created on staging.

Planned probe script, to run only against `https://mr-api.osm.lol`, on a
new disposable project and challenge with three or more point tasks
(never on bench challenge 1):

1. User A: start T1 → 200. Read T1: `lockedBy == A`.
2. User A: start T2 → 409 with `lockedTaskId == T1`.
3. User B: start T1 → 403. User B: `PUT T1/1` → 403.
4. User A: refresh T1 → 200. User A: `PUT T1/1` → 204. Read T1: status 1,
   `lockedBy` null, check `completedBy` (staleness).
5. User A: `PUT T1/2` (a change by the same user) → 204 or 400. User B:
   `PUT T1/2` → 400. User B: `PUT T1/1` → 204? This last call confirms the
   weakness where another user can rewrite the same status.
6. User A: start T2, then release → 200. Release again → 200. Read:
   `lockedBy` null.
7. User A: start T3, skip → 204. Read: status 0, `skip_count` +1, unlocked.
8. User A: `PUT T3/4` → expected 204, which confirms the Deleted
   escalation. Run this only on the disposable challenge.
9. Clean up: delete the disposable challenge and project, then record
   their IDs in the work log.

## Decisions (user, 2026-10-05)

1. Skip uses `POST /skip`.
   Resolution meanings: Fixed means the user fixed the problem in OSM
   themselves (standard tasks only). Already fixed means someone else had
   already fixed it in OSM. See challenge-types.md §3. Status 3 is not on the mobile allowlist.
2. Staging probes use a temporary staging API key plus a second
   development-OSM account for lock conflicts.
3. Status writes are bare: no `requestReview`, `tags`, `completionResponses`
   or comments (the mobile gate rejects any query or body).
4. The mobile gate blocks the unsafe writes (status 0/4/7/8/9, and
   everything in §8 item 3). The fork fixes the stale `completedBy` cache.
   The Deleted/Disabled escalation and the takeover of the same status by
   another user are written up for upstream rather than changed in the
   fork.
5. Late locking (§3).

6. The OSM upload direction (§12) is approved: upload by the backend,
   behind a separate opt-in `osm:tagfix` scope, with changes built on the
   server. It is not implemented in this slice.
7. The three problems with the web `fix/apply` route are written up for
   upstream only (in the backend fork's upstream-issues document). The
   fork's behavior does not change:
   - it accepts changes supplied by the client;
   - it uploads to OSM before the status write;
   - the request can hang.
8. The staging `MR_OSM_OAUTH2SCOPE` value is left unknown; it is not
   needed now.

The challenge-kind decisions are in [challenge-types.md](challenge-types.md)
§7.
