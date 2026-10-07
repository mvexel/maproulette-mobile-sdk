# Completing a choice task

Back to [Getting started](getting-started.md).

A multiple-choice task asks a few questions about one OSM element, for example
"Does this bench have a backrest?". Each answer is an exact tag change. The
server applies the answers to OSM as one changeset, writes the task status and
releases the lock. The SDK itself never talks to OSM.

What you need:

- The staging environment. Before 1.0 the SDK refuses writes elsewhere, and the
  choice routes exist only on the fork backend.
- A bearer token with `tasks:write` and `osm:tagfix`
  (`getCurrentUser().canEditOsm`). See [Authentication](authentication.md).
- A task where `task.mobileSupport()` is `IN_PLACE` / `.inPlace`.

## The supported write path

Mobile apps need three calls:

| Call | When |
| --- | --- |
| `checkChoice(id)` | A read, when the task opens. Tells you whether it still needs answering. |
| `submitChoice(task, submission)` | The user submits answers or picks an outcome. |
| `skipTask(id)` | The user skips. Offer it only when `task.canSkip()`. |

Opening a task takes no lock. `submitChoice` locks the task, submits, and the
server releases the lock: late locking. The lock lives only for the seconds of
the submission.

The low-level lifecycle calls (`startTask`, `refreshTaskLock`, `releaseTask`,
`resolveTask`, `commitResolution`) are hidden behind an opt-in: Kotlin
`@OptIn(LowLevelTaskLifecycle::class)`, Swift
`@_spi(LowLevelTaskLifecycle) import MapRoulette`. They exist for tooling and
future flows. A mobile app should not need them.

## The flow

1. **Open.** Read the task with `getTask`. Check `mobileSupport()`.
2. **Check.** Call `checkChoice(task.id)`.
   - Not `eligible`: the OSM element changed since the task was made. Say "This
     one no longer needs answering" and offer the next task. Do not offer a
     conflict choice. The server hides the task from mobile discovery.
   - A failure (for example `OsmUnavailable`): say you could not check right
     now, and offer Retry and Next task. Do not show actions until a check
     succeeds.
   - Eligible: remember `deleteAllowed` for step 3.
3. **Show.** Take the questions from `client.work(task)` (a `Choice`) and the
   outcomes from `client.choiceOutcomes(task)`. Under each option, show its exact
   tag change (`setTags`, `unsetTags`). Add a "Can't tell" choice to every
   question; it is the default.
4. **Submit** answers or one outcome with `submitChoice`.
5. **Re-read** the task. Show its status and changeset.

Kotlin:

```kotlin
val client = MapRouletteClient(
    environment = MapRouletteEnvironment.STAGING,
    transport = transport,
    allowElementDeletion = settings.allowDeletion,
    accessToken = { session.currentAccessToken() },
)

val task = client.getTask(taskId)
if (task.mobileSupport() != MobileSupport.IN_PLACE) return showNotAvailable()
val check = client.checkChoice(task.id)
if (!check.eligible) return showNoLongerNeeded()

val work = client.work(task) as TaskWork.Choice
// Render work.questions, each with a "Can't tell" choice.
val outcomes = client.choiceOutcomes(task).map {
    if (it.deletesElement && !check.deleteAllowed) it.withoutDeletion() else it
}

// The user answered one question and left the others as "Can't tell".
val result = client.submitChoice(task, ChoiceSubmission.Answers(mapOf("backrest" to "yes")))
// Or picked an outcome:
// client.submitChoice(task, ChoiceSubmission.Outcome(outcomes.first { it.id == "gone" }))
println("Status ${result.status.knownName}, changeset ${result.changesetId}")
```

Swift:

```swift
let client = MapRouletteClient(
    environment: .staging,
    allowElementDeletion: settings.allowDeletion,
    accessToken: { try await session.currentAccessToken() })

let task = try await client.getTask(taskID)
guard task.mobileSupport() == .inPlace else { return showNotAvailable() }
let check = try await client.checkChoice(task.id)
guard check.eligible else { return showNoLongerNeeded() }

guard case .choice(_, _, let questions, _) = client.work(task) else { return }
// Render `questions`, each with a "Can't tell" choice.
let outcomes = client.choiceOutcomes(task).map {
    $0.deletesElement && !check.deleteAllowed ? $0.withoutDeletion() : $0
}

let result = try await client.submitChoice(task, .answers(["backrest": "yes"]))
// Or: try await client.submitChoice(task, .outcome(outcome))
print("Status \(result.status.knownName ?? "?"), changeset \(result.changesetID.map(String.init) ?? "none")")
```

`submitChoice` checks the submission against the task's payload before it sends
anything. An unknown question or option id, an empty answer set, a bundled task,
or an outcome that does not come from this client fails with a validation error.

## Outcomes and "Too hard"

`client.choiceOutcomes(task)` returns the outcomes the challenge declares (for
example "Not a bench" or "Bench is gone") plus a built-in "Too hard". The
built-in one has the id `ChoiceOutcome.TOO_HARD_ID` (Kotlin) /
`ChoiceOutcome.tooHardID` (Swift). Its English label "Too hard" is a fallback:
match on the id to show your own translation.

Each outcome has a `resolution`: the status it sets. Not an issue (2) and Too
hard (6) do not edit OSM.

## Deleting OSM elements

A challenge may declare a "gone" outcome that deletes the node from OSM. This
is the riskiest edit the SDK can trigger, so it is off by default.

- `allowElementDeletion` on the client is a cap. When it is `false`, the SDK
  never sends a delete. "Gone" is then recorded as Not an issue (status 2),
  with no OSM edit.
- When it is `true`, a "gone" outcome has `deletesElement == true` and sets
  Fixed (1) after the delete.
- Per task, `outcome.withoutDeletion()` gives the same "gone" outcome without
  the delete, recorded as Not an issue. `submitChoice` accepts it from a
  deletion-enabled client. Outcomes that do not delete come back unchanged.

Use `withoutDeletion()`:

- when `checkChoice` reports `deleteAllowed == false` (the node is part of a
  way or relation), and say so in the confirmation;
- after a submission fails with `ChoiceProblem.ElementInUse` (Swift
  `.choice(.elementInUse)`): offer the same outcome without deletion, and submit
  it only after the user confirms.

Always decode outcomes through the client (`client.work(task)`,
`client.choiceOutcomes(task)`). `task.work()` on its own decodes with deletion
off, so its outcomes do not match a deletion-enabled client.

## Partial answers close the task

"Can't tell" leaves a question out of the submission. If the user answers at
least one question, the answers are uploaded and the task becomes Fixed (1),
which closes it. The questions left as "Can't tell" are not offered again from
this task. This follows upstream MapRoulette, where a task has one final status.

Before submitting, tell users which questions are still "Can't tell" and that
submitting closes the task. Challenge maintainers pick up the rest by
re-checking live OSM and creating new tasks for elements whose keys are still
missing. See
[multiple-choice challenges, section 10](../design/mobile-choice-challenges.md#10-partial-answers-d7-decided-2026-10-07).

## Skipping

`skipTask(id)` posts a skip. The status does not change, and the server
releases the caller's lock if it holds one. A skip is not idempotent: the
server counts every one. After `OutcomeUnknown`, do not resend; move on.

## After a failure

See [Handling errors](errors.md) for every problem and what to show. Key rules:

- The SDK releases the lock after a failed submission, except after
  `StatusPending` and `OutcomeUnknown`.
- The SDK resends the identical submission at most once, and only where the
  server makes it safe.
- After `OutcomeUnknown`, never resend an edit automatically. Re-read the task.
  If it is not done, the user may submit the same answers again; the server
  resumes without a second upload.
