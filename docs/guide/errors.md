# Handling errors

Back to [Getting started](getting-started.md).

## Error types

| | Kotlin | Swift |
| --- | --- | --- |
| HTTP and protocol failures | `MapRouletteException(kind, status, retryAfter, problem)` | `MapRouletteError(kind, status, retryAfter, problem, reason)` |
| Bad arguments (page size, bounds, ids, submission) | `IllegalArgumentException` with a short message, e.g. "pageSize must be 1..100" | `MapRouletteError` with kind `.validation` and a `reason` |
| Write on an environment that does not allow writes | `IllegalStateException` with a message | `MapRouletteError(.validation)` with a `reason` |
| Cancellation | `CancellationException` | `CancellationError` |

Error descriptions never contain credentials, request headers or server bodies.
They are safe to log. `retryAfter` is the raw `Retry-After` header value.

A bad argument is a bug in the calling code. Fix the call; do not show it to the
user as a network problem.

## By kind

`ErrorKind` may gain cases in SDK 0.x: keep a default branch.

| Kind (Kotlin / Swift) | Meaning | What the app does |
| --- | --- | --- |
| — / `validation` | Bad argument, or a write refused before sending (Swift only; Kotlin throws the exceptions above) | Fix the calling code |
| `AUTHENTICATION` / `authentication` | 401: credential missing, expired or rejected | Refresh the token in your provider and retry once, or ask the user to sign in. Check `problem` first: `OsmReauthRequired` is also a 401 |
| `PERMISSION` / `permission` | 403: not allowed. See `problem` for locks and scopes | Show "You can't do this"; for scopes, ask to sign in again |
| `NOT_FOUND` / `notFound` | 404: does not exist or is not visible to this user | Show "Not found"; refresh lists |
| `CONFLICT` / `conflict` | 409: see `problem` | Depends on the problem |
| `RATE_LIMIT` / `rateLimit` | 429 | Wait (see `retryAfter`), then let the user retry |
| `SERVER` / `server` | 5xx | "Try again later". For a write, see `OutcomeUnknown` |
| `HTTP` / `http` | Another HTTP status, e.g. 400 or 422 | See `problem`; otherwise a generic error |
| `NETWORK` / `network` | No connection, timeout, TLS failure | "Check your connection". For a write, see `OutcomeUnknown` |
| `PROTOCOL` / `protocolFailure` | The server answered with something the SDK cannot read | Generic error; report it |

The SDK never retries a read. Your app decides when to retry.

## Write problems

Write failures keep their `kind` and add a `problem`. In Kotlin, `problem` is a
`WriteProblem`; `ChoiceProblem` extends it. In Swift, choice problems are wrapped:
`WriteProblem.choice(ChoiceProblem)`. Both may gain cases in SDK 0.x.

| `WriteProblem` | Cause | Tell the user | App action |
| --- | --- | --- | --- |
| `LockedByOtherUser` | 403 at lock time: someone else is working on it | "Someone else is working on this task" | Offer another task |
| `AlreadyHoldingTask` | 409: the user holds a lock on another task | "Finish or leave your other task first" | `submitChoice` recovers a stale own lock once by itself; if it still fails, offer another task |
| `LockLost` | The lock expired or was taken during the write | "This task changed. Try another one" | Re-read; offer another task |
| `InvalidTransition` | The status can no longer be set (completed by someone else, paused challenge) | "This task is already done" | Re-read and show the new status |
| `InsufficientScope` | The bearer grant lacks `tasks:write` | "Sign in again to enable editing" | Start sign-in with the full scopes |
| `OutcomeUnknown` | Network failure, 5xx or unreadable success: the write may have landed | "Couldn't confirm. Check again" | Re-read; never resend blindly (below) |

| `ChoiceProblem` | Cause | Tell the user | App action |
| --- | --- | --- | --- |
| `TaskIneligible(reason)` | 409: the OSM element changed since the task was made. Nothing was uploaded | "This one no longer needs answering" | Move on. No conflict choice |
| `ElementInUse` | 409: a delete was refused; the node is part of a way or relation | "This can't be deleted. Record it as gone without deleting?" | After the user confirms, submit `outcome.withoutDeletion()` |
| `OsmReauthRequired` | 401: OSM rejected the stored OSM token | "Sign in again to edit OpenStreetMap" | Re-run sign-in. The MapRoulette session is still valid; do not sign out |
| `OsmScopeRequired` | 403: the grant lacks `osm:tagfix` | "Sign in again to enable editing" | Re-run sign-in with `osm:tagfix` |
| `OsmUnavailable` | 502/503: OSM unreachable, or the server cannot edit OSM now. Nothing applied | "OpenStreetMap is unavailable. Try again later" | Keep the user's answers; let them retry |
| `SubmissionPending` | 409: another submission for this task is still running | "Still saving. Check again" | Re-read later; do not resend |
| `StatusPending(changesetId)` | 500: the OSM edit is uploaded, but the status write failed. The lock is kept | "Saved to OpenStreetMap, finishing up. Check again" | Submit the identical submission again; the server finishes without a second upload |
| `InvalidSubmission(detail)` | 422: an id is not in the stored payload | Generic error | A bug or a changed task: re-read it |
| `UnsupportedTask` | 422: the server rejects the task's payload | "Not available on mobile" | Offer another task |

The SDK releases the lock after a failed submission, except after
`StatusPending` and `OutcomeUnknown`.

## Matching problems

```kotlin
try {
    client.submitChoice(task, submission)
} catch (e: MapRouletteException) {
    when (val problem = e.problem) {
        is ChoiceProblem.TaskIneligible -> showNoLongerNeeded()
        ChoiceProblem.ElementInUse -> offerWithoutDeletion()
        ChoiceProblem.OsmReauthRequired, ChoiceProblem.OsmScopeRequired -> askToSignInAgain()
        WriteProblem.OutcomeUnknown -> showCheckAgain()
        else -> showError(e.kind)
    }
}
```

```swift
do {
    _ = try await client.submitChoice(task, submission)
} catch let error as MapRouletteError {
    switch error.problem {
    case .choice(.taskIneligible)?: showNoLongerNeeded()
    case .choice(.elementInUse)?: offerWithoutDeletion()
    case .choice(.osmReauthRequired)?, .choice(.osmScopeRequired)?: askToSignInAgain()
    case .outcomeUnknown?: showCheckAgain()
    default: showError(error.kind)
    }
}
```

## Unknown outcomes

`OutcomeUnknown` means the write may or may not have landed. Never resend a skip
or a status write blindly: a skip is counted twice, and an edit may still be
uploading.

1. Read the task again with `getTask`.
2. Apply `task.verifyResolution(target, me)`. `target` is the status you
   expected (Fixed for answers, or the outcome's `resolution`); `me` is
   `getCurrentUser().id`.
3. Act on the result:

| `ResolutionCheck` | Meaning | Action |
| --- | --- | --- |
| `APPLIED` / `applied` | Done | Show success |
| `NOT_APPLIED_LOCK_HELD` / `notAppliedLockHeld` | Not done; the user still holds the lock | The user may submit again |
| `NOT_APPLIED_UNLOCKED` / `notAppliedUnlocked` | Not done; no lock | The user may submit again (it locks again) |
| `LOCKED_BY_OTHER` / `lockedByOther` | Someone else has it | Offer another task |
| `RESOLVED_BY_OTHER` / `resolvedByOther` | Someone else completed it, or another final status | Show the status; stop |

For a choice task, resubmitting the identical submission is safe: the server
resumes it without a second OSM upload. Let the user trigger it ("Check again",
then "Submit again"); do not loop.

## Cancellation

Cancelling the calling coroutine or Swift task cancels the request, and the
cancellation error reaches you unchanged. For a write, the outcome is then
unknown: handle it like `OutcomeUnknown`. The SDK does no cleanup after a
cancel; the server expires stale locks after one to two hours.

To avoid this, let a submission finish even when the screen closes, for
example in a scope that outlives the screen.
