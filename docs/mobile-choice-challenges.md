# Multiple-choice challenges: v1 spec

Status: implementable spec (2026-10-06). It builds on
[challenge-types.md](challenge-types.md) and
[task-completion.md §12](task-completion.md#12-future-osm-upload-design-only-kept-separate-from-resolution).

Sources:

- Backend fork `~/dev/maproulette-mobile-backend-public`,
  `feat/mobile-oauth` at `15bc9f8`. Backend paths below are relative to it.
- Web frontend `~/dev/maproulette3` at `f6241e50`.

## 0. Decisions (user, 2026-10-06)

| # | Decision |
| --- | --- |
| Base | Stay on the fork. Every change is additive and keeps stock MapRoulette behavior for existing clients. An upstream rewrite is coming, and the user will engage there. |
| D1 | `cooperativeWork.meta.type = 3` ("choice"), to be proposed upstream. |
| D2 | Pilot on staging `mr-api.osm.lol` against **dev OSM**. Production OSM comes later, with a new OSM OAuth app. |
| D3 (revised; replaces "user picks on conflict") | If the OSM element has changed since the payload was written (it is gone or invisible, `match` fails, or **any** guarded key no longer equals its `expect`), the task is **not offered at all**. There is no conflict UI and no user choice. The app checks freshly when a task opens. At submit the server checks again; a mismatch → `409 task_ineligible`, no status. A stale task is put in the fork-side `choice_stale` table, which removes it from mobile discovery for everyone. No MapRoulette status is written, because no user acted (§5a). |
| Dev OSM | The user authorizes creating disposable bench nodes on `master.apis.dev.openstreetmap.org` and editing them through the app and tests. The user enables `write_api` on the dev-OSM OAuth app. Never `api.openstreetmap.org`. |
| D4 | One OSM element per task. **Several questions** about that element. The answers are applied as **one changeset**. |
| D5 | Deleting the element ("gone") is a task-level outcome. It is **opt-in in the SDK** and off by default. Without opt-in, "gone" maps to Not an issue (2). |
| D6 | Mobile hides every task that is not a choice task. The fork also gets the cheap `cct` filter on the marker, box and cluster endpoints. |

## 1. Background (evidence)

**How the backend stores `cooperativeWork`:**

- The column is JSONB (`conf/evolutions/default/39.sql:24`, `61.sql:3`).
- `TaskDAL.extractCooperativeWork` (`app/org/maproulette/models/dal/TaskDAL.scala:471-532`)
  finds `cooperativeWork` at any depth of the task GeoJSON (`:481`).
  - It validates only `meta.version`.
  - For version 2 it copies `meta.type` without checking it into
    `challenges.cooperative_type` (`:504-513`). The last task processed
    wins, the value is never reset, and the challenge JSON cannot set it
    (`ChallengeDAL.scala:712-736, 1036-1079`).
  - A collection-level key is extracted and then removed from the stored
    geometry (`:518-528`).

**What the upload routes do with it:**

- `addTasks` rebuilds each feature, which drops `cooperativeWork`
  (`provider/ChallengeProvider.scala:784-812`).
- Line-by-line `addFileTasks` keeps each line verbatim
  (`controllers/api/ChallengeController.scala:1629-1725`). The multipart
  field is `json`, and the route returns 204.
- Task creation errors are swallowed and only logged
  (`ChallengeProvider.scala:814-830`).

**How the stock web UI degrades for type 3** (maproulette3):

- Mappers get the normal completion step with all five buttons.
- Fixed is a plain `PUT /task/:id/1`.
- The tag diff is hidden and there is no badge
  (`src/interactions/Task/AsCooperativeWork.js:20-69`,
  `ActiveTaskControls.jsx:487-516`, `WithCurrentTask.jsx:249-252`).
- Nothing throws and nothing is uploaded.

**What the current apply path does wrong** (`TaskController.applyTagFix`,
`controllers/api/TaskController.scala:868-940`):

- It trusts the changes the client sends.
- It uploads to OSM before writing the status, and does not check the
  lock.
- Its element reads go through a possibly stale cache
  (`provider/osm/objects/ObjectProvider.scala:68-80`).
- It can leave a changeset open (`ChangesetProvider.scala:85-133`).
- It supports only `<create>` and `<modify>` (`:150-189`).

**What the mobile pieces look like today:**

- **Gate:** `MobileWriteRoutes` (`auth/mobile/MobileBearerFilter.scala:42-51`)
  rejects every write that has a body or a query (`:78-79`).
- **Scopes:** `MobileScopes` (`auth/mobile/MobileOAuthSettings.scala:15-31`).
- **OSM token:** mobile authorize asks OSM only for `read_prefs` and
  throws the token away (`controllers/MobileOAuthController.scala:96`).

**Staging OSM:** staging uses dev OSM
(`compose.mobile.yml:40`, `MR_OSM_SERVER=https://master.apis.dev.openstreetmap.org`).
Real SLC bench node ids do not exist there.

**Example of the stock payload shape** (maproulette.org task 319631865, a
v2 tag fix, read anonymously):
`{"meta":{"version":2,"type":1},"operations":[{"operationType":"modifyElement","data":{"id":"way/267832139","operations":[{"operation":"setTags","data":{"drive_through":"yes"}}]}}]}`.

## 2. Payload v1

The payload is stored as the task's `cooperativeWork`.

```json
{
  "meta": {"version": 2, "type": 3, "choiceVersion": 1},
  "element": "node/123",
  "match": {"amenity": "bench"},
  "questions": [
    {
      "id": "backrest",
      "prompt": "Does the bench have a backrest?",
      "description": "Optional help text.",
      "expect": {"backrest": null},
      "options": [
        {"id": "yes", "label": "Yes", "description": "Seat has back support.", "setTags": {"backrest": "yes"}},
        {"id": "no", "label": "No", "setTags": {"backrest": "no"}}
      ]
    }
  ],
  "outcomes": [
    {"id": "not-a-bench", "label": "Not a bench", "description": "Something else is here.", "status": 2},
    {"id": "gone", "label": "Bench is gone", "delete": true}
  ]
}
```

### Meaning

- **`match`** is an identity guard for the whole task. The element must
  still carry these tags, or the task is ineligible. A delete is only
  allowed while `match` holds.
- **`questions`** are answered independently.
  - `expect` lists the keys this question guards, with the value each key
    must currently have. `null` means the key must be absent.
  - Each option is one tag change, and it may touch only those guarded
    keys.
  - "Can't tell" is not listed in the payload. The client always offers
    it, and it means the question is left out of the submission.
- **`outcomes`** are task-level results that are not edits.
  - A plain outcome has a `status` of 2 or 6.
  - A `delete: true` outcome is "the element is gone". It never has a
    `status`. With deletion enabled it deletes the element and resolves to
    Fixed (1). Without deletion it resolves to Not an issue (2).
- **Built-in outcomes** are never declared in the payload:
  - `too-hard` → 6
  - Skip, which uses the existing `POST /task/:id/skip` route.
- **Already fixed (5) is never offered.** A change made by someone else in
  OSM makes the task ineligible (D3).
- **Eligibility** is all-or-nothing. A task is eligible only when all
  of these hold:
  - the element exists and is visible
  - `match` holds
  - **every** question's `expect` still holds

  Anything else makes the whole task stale (D3).

### Validation rules

The backend checks these at ingest and again at submit. The SDK checks the
same rules when decoding, and any failure makes the task unsupported.

1. `meta.version == 2`, `meta.type == 3` and `meta.choiceVersion == 1`.
   Any other `choiceVersion` is rejected.
2. `element` matches `^(node|way|relation)/[1-9][0-9]{0,15}$`.
   `match` is optional, with 1 to 4 keys whose values are non-null
   strings. Its keys must not overlap any question's `expect` keys.
3. `questions`: 1 to 8 entries.
   - `id` matches `^[a-z0-9][a-z0-9-]{0,31}$` and is unique within the
     task.
   - `prompt` is 1 to 200 characters. `description` is up to 500.
   - `expect` has 1 to 4 keys. Its values are a string or `null`.
   - **The `expect` keys of different questions must not overlap.**
   - `options`: 2 to 12 entries. Each `id` uses the same pattern as a
     question id and is unique within its question. `label` is 1 to 60
     characters, `description` up to 300.
   - Every option has a non-empty `setTags` and/or `unsetTags`, and every
     key it touches is one of its question's `expect` keys.
   - No option may equal its question's `expect` state, because that
     would be a no-op.
4. `outcomes`: 0 to 4 entries.
   - `id` uses the question-id pattern, must be unique, and must not be
     `too-hard`.
   - Each outcome has exactly one of `status` (in {2, 6}) or
     `delete: true`.
   - At most one outcome may have `delete`. It is allowed only when
     `element` is a **node** and `match` is present.
5. Tag keys and values are 1 to 255 characters (OSM limits), with no
   control characters.
6. Unknown fields are **rejected** at ingest. The SDK ignores them when
   decoding. The whole serialized payload is at most 16 KiB.
7. Challenge consistency: a choice task may not be added to a challenge
   that already has non-choice tasks, and the reverse is also refused.

## 3. Example: SLC bench challenge

There is one line per bench in the line-by-line file. The task generator
includes a question **only if all of its keys are absent** in the source
data. Each question's `expect` is therefore all `null`. Example line,
wrapped here for reading:

```json
{"type": "FeatureCollection",
 "features": [{"type": "Feature",
   "geometry": {"type": "Point", "coordinates": [-111.8910, 40.7608]},
   "properties": {"@id": "node/123", "amenity": "bench"}}],
 "cooperativeWork": {
  "meta": {"version": 2, "type": 3, "choiceVersion": 1},
  "element": "node/123",
  "match": {"amenity": "bench"},
  "questions": [
   {"id": "backrest", "prompt": "Does the bench have a backrest?",
    "expect": {"backrest": null},
    "options": [
     {"id": "yes", "label": "Yes", "setTags": {"backrest": "yes"}},
     {"id": "no", "label": "No", "setTags": {"backrest": "no"}}]},
   {"id": "material", "prompt": "What is the seat mainly made of?",
    "expect": {"material": null},
    "options": [
     {"id": "wood", "label": "Wood", "setTags": {"material": "wood"}},
     {"id": "metal", "label": "Metal", "setTags": {"material": "metal"}},
     {"id": "concrete", "label": "Concrete", "setTags": {"material": "concrete"}},
     {"id": "stone", "label": "Stone", "setTags": {"material": "stone"}},
     {"id": "plastic", "label": "Plastic", "setTags": {"material": "plastic"}}]},
   {"id": "capacity", "prompt": "How many adults can sit on it?",
    "expect": {"capacity": null},
    "options": [
     {"id": "c1", "label": "1", "setTags": {"capacity": "1"}},
     {"id": "c2", "label": "2", "setTags": {"capacity": "2"}},
     {"id": "c3", "label": "3", "setTags": {"capacity": "3"}},
     {"id": "c4", "label": "4", "setTags": {"capacity": "4"}},
     {"id": "c5", "label": "5", "setTags": {"capacity": "5"}},
     {"id": "c6", "label": "6", "setTags": {"capacity": "6"}}]}],
  "outcomes": [
   {"id": "not-a-bench", "label": "Not a bench", "status": 2},
   {"id": "gone", "label": "Bench is gone", "delete": true}]}}
```

Challenge settings:

- `checkinComment`: "Add bench details (backrest, material, capacity) #maproulette".
- `checkinSource`: "survey".
- `instruction`: written for web mappers too, for example "Add `backrest`,
  `material` and `capacity` in your editor".

## 4. Ingest

- **Route:** `PUT /api/v2/challenge/:id/addFileTasks?lineByLine=true`,
  multipart field `json`, using the stock route.
- **Placement:** `cooperativeWork` sits at the collection level of each
  line. The `@id` property gives the task its name, so a re-upload updates
  the task instead of duplicating it.
- **Validation:** a new `ChoiceWork.validate(json): Either[List[String], ChoiceWork]`
  runs inside `extractCooperativeWork` when `meta.type == 3`. A failure
  throws `InvalidException` listing every broken rule. Types 0, 1 and 2
  behave exactly as before.
- **Rejection reporting** is opt-in so that stock callers keep their
  behavior. `&report=true` makes the route return
  `200 {"created": n, "updated": n, "rejected": [{"line": 1, "errors": ["..."]}]}`
  instead of 204.
  - Lines are numbered from 1.
  - To support this, `createTaskFromJson` and `_createNewTask` gain an
    optional error collector instead of logging and returning `None`.
  - Without `report` the route still returns 204.
  - When `report=true` and at least one line is rejected, the challenge is
    not marked `FAILED`. Accepted lines stay.
- **Consistency check** (rule 7): the backend queries the challenge's
  existing tasks. If it holds any task without `cooperative_work_json`, or
  any task with a type other than 3, the new choice line is rejected. The
  same check runs in reverse for a non-choice task added to a challenge
  that holds choice tasks.

## 5. Submit endpoint

`POST /api/v2/task/:id/choice` with `Content-Type: application/json`.

The route accepts only mobile bearer credentials in v1. A web session or
API key gets `403 {"error": "mobile_only"}`.

### Request

Exactly one of these two forms:

```json
{"answers": {"backrest": "yes", "capacity": "c3"}}
{"outcome": "gone", "delete": true}
```

- `answers` maps question id to option id, with 1 or more entries. It is
  never combined with `outcome`.
- `outcome` is a declared outcome id or `too-hard`.
- `delete` may be present only on a `delete` outcome. The SDK sends `true`
  only when deletion is enabled. Otherwise it leaves `delete` out (or sends
  `false`), and the outcome resolves to 2.
- Unknown fields, duplicate keys and both forms together get
  `400 invalid_request`. An id that is not in the stored payload gets
  `422 invalid_submission` with the details.

### Gate changes

These go in `MobileBearerFilter` and `MobileOAuthSettings`.

- **New route:** add `POST /api/v2/task/[0-9]+/choice` to
  `MobileWriteRoutes`. It requires `tasks:write`.
- **New read route:** add `GET /api/v2/task/[0-9]+/choice/check` to
  `MobileReadRoutes`. It requires `tasks:read` and takes no query or body.
- **Body exception:** this route is the only write that may carry a body,
  and only when all of these hold:
  - there is no query string
  - `Content-Type` is `application/json`
  - `Content-Length` is present and at most **2048**
  - there is no `Transfer-Encoding`

  Anything else gets `400 invalid_request`. Every other write keeps the
  no-body, no-query rule.
- **Strict parsing in the controller:**
  - The body is checked against the schema above, with at most 8 answers.
  - Ids are re-checked against the patterns.
  - The parse is strict: unknown fields are rejected.
- **New scope `osm:tagfix`** in `MobileScopes.supported`. It is only valid
  together with `tasks:write`, and it is configured per client like
  `tasks:write`.
- **Passing scopes on:** the filter attaches the grant's scopes to the
  request as a new attribute, `MobileBearerIdentity.ScopesKey`.
- **Scope check in the controller:** a submission that would write to OSM
  (answers, or `delete: true`) without `osm:tagfix` gets
  `403 {"error": "insufficient_scope", "scope": "osm:tagfix"}`.
  Submissions that do not edit need only `tasks:write`.

### OSM token

- **Requesting it:** when a grant includes `osm:tagfix`, mobile authorize
  asks OSM for `read_prefs write_api` instead of the hard-coded
  `read_prefs`.
- **Storing it:** the OSM token goes into a new table,
  `mobile_osm_tokens(grant_family_id PK, user_id, ciphertext, nonce, osm_scope, created_at)`.
  - It is encrypted with AES-GCM under the key in env
    `MR_MOBILE_OSM_TOKEN_KEY`.
  - The startup check fails only when mobile OAuth is enabled and a client
    is configured with `osm:tagfix`.
- **What does not change:** `users.oauth_token` is never read or written.
- **Lifecycle:**
  - Refresh rotation keeps the row.
  - Revoking the family, or replaying a refresh token, deletes it.
  - If OSM later rejects the token → `401 {"error": "osm_reauth_required"}`.
    The row is deleted and the MapRoulette grant stays.

### Server steps

All steps run for a single task.

1. **Lock and status.**
   - The caller must hold the task lock: `locked_by == user`, and the lock
     has not been cleaned up. Otherwise → `409 {"error": "lock_required"}`.
   - The task status must allow the target status under the existing
     progression rules. Otherwise → `409 {"error": "invalid_transition"}`.
2. **Payload.** Re-validate the stored `cooperativeWork` (§2). If it fails →
   `422 {"error": "unsupported_task"}`. Resolve the submission against it.
3. **Idempotency.**
   - Compute `key = sha256(task_id, user_id, canonical submission)`. The
     canonical form has answers sorted by question id.
   - The row lives in a new table, `mobile_choice_submissions(task_id,
     user_id, submission_key, state, changeset_id, result_json,
     created_at, PK(task_id, user_id, submission_key))`. `state` is one of
     `started`, `uploaded` or `done`.
   - A `done` row → return the stored `result_json`.
   - An `uploaded` row → go to step 7.
   - A `started` row that has a `changeset_id` → read that changeset from
     OSM.
     - If `changes_count > 0`, mark it `uploaded` and go to step 7.
     - Otherwise continue from step 4. The old changeset is already
       closed, or gets closed now.
   - Any other row for this task that is not `done`, from any user or
     with any key → `409 {"error": "submission_pending"}`. The task stays
     in that state until the pending row is finished or cleaned up by an
     admin.
4. **Non-editing results** (a plain outcome, or `delete` absent or false):
   go to step 7 with no OSM work. The status is the declared one; `gone`
   gives 2 and `too-hard` gives 6. These results need no OSM read. They
   resolve what the user saw, and the precheck already ran when the task
   opened.
5. **Fresh eligibility check (§5a).** Run the same check as
   `choice/check`, uncached, using the user's OSM token.
   - **Stale** (the element is gone or invisible, `match` fails, or any
     question's `expect` key differs) → insert into `choice_stale` and
     return `409 {"error": "task_ineligible", "reason": "...", "detail": [...]}`.
     No status is written and nothing is uploaded. The lock is released
     as for any failed submit.
   - **Otherwise** the change is the union of the answered questions' tag
     changes, applied to the fetched tags.
   - **Delete:**
     - Eligibility was already checked above, which includes `match`.
     - `GET /api/0.6/node/{id}/ways` and `.../relations` must both return
       nothing. Otherwise → `409 {"error": "element_in_use"}`. The app
       then offers to send the same outcome without deletion (Not an
       issue), which the user confirms. This is not an OSM change by
       someone else, so it is not a staleness case.
     - The change is then `<delete><node id version changeset/></delete>`
       at the fetched version, without `if-unused`, so that problems show
       up as errors.
6. **Upload.**
   - Create the changeset with these tags:
     - `comment` = the challenge's `checkinComment`, or
       "MapRoulette task {id}" if it is empty
     - `source` = `checkinSource`, if it is not empty
     - `created_by` = `MapRoulette`
   - Store the `changeset_id` with state `started`.
   - Upload an osmChange with one `<modify>` (the full fetched element
     with the merged tags, at the fetched version) or one `<delete>`.
   - Close the changeset on **every** branch. The new
     `ChangesetProvider.deleteChange` and a fixed `submitOsmChange` share
     that close step.
   - Errors:
     - OSM 409 on upload (the element changed between the read and the
       upload) → re-run step 5 once, then return its result. A delete
       that hits 412 → `element_in_use`. The row is deleted in both cases.
     - OSM 401 → `401 osm_reauth_required`.
     - OSM 5xx or timeout → `502 {"error": "osm_unavailable"}`. The row
       stays `started` for the step 3 check.
   - On success, the state becomes `uploaded`.
7. **Status.** In one DB transaction:
   - Write the status through the same path as `customTaskStatus`,
     including the stale-cache fix. The status is 1 when step 6 uploaded
     anything, otherwise the outcome's status.
   - Set `tasks.changeset_id` when there is one.
   - Mark the row `done` with `result_json`.
   - Release the lock (the status write already does this).
   - Complete the HTTP promise on every branch. If the transaction fails
     after an upload →
     `500 {"error": "status_pending", "changesetId": n}`. A retry of the
     same submission finishes it through step 3.

### Responses

| Code | Body |
| --- | --- |
| 200 | `{"status": 1, "changesetId": 42, "applied": {"set": {"backrest": "yes"}, "unset": [], "deleted": false}}` (`changesetId` is `null` when nothing was edited) |
| 400 | `invalid_request` |
| 401 | `invalid_token` (MapRoulette), or `osm_reauth_required` (OSM) |
| 403 | `insufficient_scope` (with `scope`), or `mobile_only` |
| 409 | `lock_required`, `invalid_transition`, `submission_pending`, `element_in_use`, or `task_ineligible` (with `reason`, and `detail` for diagnostics only) |
| 422 | `invalid_submission`, or `unsupported_task` |
| 500 | `status_pending` (with `changesetId`) |
| 502 | `osm_unavailable` |

## 5a. Eligibility check and stale tasks (D3)

### `GET /api/v2/task/:id/choice/check`

Route rules:

- Added to `MobileReadRoutes`. Mobile bearer with `tasks:read`, no lock,
  no query and no body.
- It never changes the task's status, lock or history. Its only side
  effect is inserting into `choice_stale` when it observes a stale
  element. That records a system observation of OSM, not a user action.

What it does:

- Reads the element fresh with `GET /api/0.6/{type}/{id}`, bypassing the
  object cache. The read is anonymous, so it needs no OSM write scope.
- When a delete outcome is declared, it also reads `.../ways` and
  `.../relations`, which feed `deleteAllowed`.
- Per task, results are reused for 60 seconds. Requests use a descriptive
  `User-Agent`.
- An OSM read failure → `502 osm_unavailable`, and nothing is recorded.
  The app treats the task as not checked: it skips the task in the pilot.

Response:

```json
{"eligible": true, "deleteAllowed": true, "elementVersion": 7}
{"eligible": false, "reason": "key_changed",
 "detail": [{"question": "backrest", "key": "backrest", "expected": null, "current": "yes"}]}
```

- `reason` is one of `element_gone`, `match_failed` or `key_changed`.
- `detail` exists for diagnostics and logs only. Clients never build UI
  from it.
- A task that already has a `choice_stale` row returns
  `{"eligible": false, "reason": ...}` from that row without reading OSM.

### Removal from discovery (recommended): `choice_stale` table

- **Table:** `choice_stale(task_id PK, reason, detail jsonb,
  element_version, observed_at)`. It is fork-only and system-owned.
- **Who writes it:** `choice/check`, submit step 5 and the optional sweep
  below. Rows are only inserted, never by a user action.
- **Clearing:** a re-upload that replaces the task's payload deletes the
  row. Nothing else clears it, and no "eligible again" path exists in v1.
- **Exclusion:**
  - New search parameter `excludeStale=true` (§6) adds
    `NOT EXISTS (SELECT 1 FROM choice_stale s WHERE s.task_id = tasks.id)`
    to `/tasks/box`, `/markers/box`, `/taskCluster` and `/tasksInCluster`.
  - Mobile always sends `cct=3&excludeStale=true`.
  - The parameter is kept separate from `cct` on purpose. `cct` stays
    generic and upstreamable, while `excludeStale` depends on a fork
    table.
- **No status is written.** A system-written Already fixed (5) would claim
  a resolution nobody made: an element that is gone or retagged is not
  necessarily "fixed". It would also skew stats and `completedBy`, and
  status progression would block a correct status later. The task stays
  status 0 and is only hidden from mobile.
- **How web users see stale tasks:** as ordinary open tasks (stock
  maproulette3 does not know the table). The tag-diff widget is hidden
  for type 3, and the standard buttons work (§1). Challenge progress on
  the web counts them as open. A web mapper may still resolve one
  normally. Optionally, later, a challenge admin can close stale tasks in
  bulk under the admin's own account.
- **Coverage:**
  - In v1, flags are set **on demand**: the first mobile user to open or
    submit a stale task hides it for everyone. Nobody is ever shown one,
    because the open-time check filters it first.
  - An **optional revalidation job** is deferred: an admin-triggered
    `POST /api/v2/challenge/:id/choice/revalidate` that batch-reads
    elements (`/api/0.6/nodes?nodes=…`, single reads on a non-200) at a
    polite rate and inserts rows the same way. It never writes a status.

## 6. Discovery filter (`cct`)

- **`SearchChallengeParameters`** (`session/SearchParameters.scala:25-36`)
  gets `challengeCooperativeTypes: Option[List[Int]]`.
  - It is parsed from `cct`, a comma-separated list of integers, in
    `withSearch` (`:432-459`).
  - A non-integer value → 400.
- **`SearchParametersMixin`** gets `filterChallengeCooperativeType`,
  modelled on `filterChallengeDifficulty` (`:581-596`). It adds
  `c.cooperative_type IN (...)`, with bound values, to
  `filterOnSearchParameters` (`:20-58`).
- **Endpoints covered:**
  - `PUT|GET /tasks/box`
  - `PUT /markers/box`
  - `PUT|GET /taskCluster`
  - `/tasksInCluster`

  These all go through `TaskClusterService` (`:43, 63, 150`).
- `excludeStale` (a boolean, default false) is added the same way. It
  becomes a `NOT EXISTS` against `choice_stale` (§5a). It is fork-specific.
- Tests go in `SearchParametersMixinSpec`.
- This is an upstreamable, generic change of about 40–60 lines. Mobile
  sends `cct=3&excludeStale=true` on every map, box and cluster read.

## 7. SDK (Kotlin and Swift, matching)

```kotlin
data class MapRouletteConfig(/* existing */ val allowElementDeletion: Boolean = false)

sealed interface TaskWork {                       // existing cases remain, plus:
    data class Choice(val element: OsmElementRef, val questions: List<ChoiceQuestion>,
                      val outcomes: List<ChoiceOutcome>) : TaskWork
}
data class ChoiceQuestion(val id: String, val prompt: String, val description: String?,
                          val expect: Map<String, String?>, val options: List<ChoiceOption>)
data class ChoiceOption(val id: String, val label: String, val description: String?,
                        val setTags: Map<String, String>, val unsetTags: List<String>)
data class ChoiceOutcome(val id: String, val label: String, val description: String?,
                         val resolution: TaskResolution,      // what it will set: 2/6, or 1 for an enabled delete
                         val deletesElement: Boolean)         // true only if declared AND allowElementDeletion
sealed interface ChoiceSubmission {
    data class Answers(val byQuestion: Map<String, String>) : ChoiceSubmission   // non-empty
    data class Outcome(val id: String) : ChoiceSubmission     // declared id or "too-hard"
}
enum class MobileSupport { IN_PLACE, UNSUPPORTED }            // replaces FULL / RESOLVE_WITHOUT_FIX
fun Task.mobileSupport(): MobileSupport    // IN_PLACE iff a valid Choice, not bundled, actionable
fun Task.choiceOutcomes(): List<ChoiceOutcome>  // declared outcomes plus built-in too-hard

suspend fun MapRouletteClient.submitChoice(taskId: Long, submission: ChoiceSubmission): ChoiceResult
data class ChoiceResult(val status: TaskStatus, val changesetId: Long?)
data class ChoiceEligibility(val eligible: Boolean, val deleteAllowed: Boolean,
                             val reason: IneligibleReason?)   // server `detail` is not modelled (diagnostics only)
enum class IneligibleReason { ELEMENT_GONE, MATCH_FAILED, KEY_CHANGED, UNKNOWN }
suspend fun MapRouletteClient.checkChoice(taskId: Long): ChoiceEligibility   // read-only for the caller
sealed interface ChoiceProblem : WriteProblem {
    data class TaskIneligible(val reason: IneligibleReason) : ChoiceProblem   // no UI choice: move on
    data object ElementInUse : ChoiceProblem
    data object OsmReauthRequired : ChoiceProblem   // re-consent with osm:tagfix; keep MR session
    data object OsmUnavailable : ChoiceProblem
    data object SubmissionPending : ChoiceProblem
    data class StatusPending(val changesetId: Long) : ChoiceProblem
    data class InvalidSubmission(val detail: String) : ChoiceProblem
}
```

Swift uses the same names and enums with associated values. Its config is
`MapRouletteConfiguration.allowElementDeletion`.

**Decoding (shared rules):**

- A task is a choice task when `meta.type == 3`. Every rule in §2 is
  applied. Any failure gives `TaskWork.Unknown`, which is `UNSUPPORTED`.
- Unknown fields are ignored.
- Delete outcomes:
  - With `allowElementDeletion == false`, the delete outcome is exposed
    with `resolution = NOT_AN_ISSUE` and `deletesElement = false`, and is
    sent without `delete`.
  - With deletion enabled, it is exposed as `FIXED` and
    `deletesElement = true`, and is sent with `"delete": true`.

**`submitChoice` flow (late locking):**

1. `start`. A 403 or 409 there uses the existing `LockedByOtherUser` /
   `AlreadyHoldingTask` handling.
2. `POST /choice`.
3. On success, the server has already released the lock.
4. On any failure other than `StatusPending` or an unknown outcome, call
   `release` (best effort).

**Retries:**

- `StatusPending` is retried with the same submission. The server-side
  idempotency makes this safe.
- When the outcome is unknown (a network error after sending), re-read
  the task. If its status shows the submission landed, report success.
  Otherwise resend the same submission once.

**Transport allowlist:** the write allowlist adds `POST …/task/{id}/choice`.
It is the only write sent with a body, and its JSON is built by the SDK,
never taken from the app.

**App rules:**

- Show only `IN_PLACE` tasks. Reads send `cct=3`, and tasks are also
  filtered on the client.
- Show each option's exact tag change below its label.
- Offer "Can't tell" per question.
- Call `checkChoice` when a task opens.
  - If the task is ineligible, or the check fails, it is not shown. The
    app says "This one no longer needs answering" and moves on.
  - The "gone" outcome is shown only if `deleteAllowed`, unless deletion
    is off, in which case it is shown as Not an issue.
- On `TaskIneligible` at submit: show the same message, drop the task and
  move on.
- There is no conflict UI, no per-key choice and no Already fixed.
- Never offer "I fixed this in OSM".
- `allowedResolutions()` stays for the web statuses only.

**Fixtures:** `fixtures/choice.json` holds:

- the SLC example (valid)
- one case per validation rule (invalid)
- `choiceVersion 2` (unsupported)
- a delete outcome decoded with deletion off and with it on
- request bodies for each submission kind
- every error response

Both suites run over these fixtures with mock transports only.

### As implemented in the SDK (2026-10-06)

The SDKs follow this section, with these differences:

- `submitChoice(task, submission)` takes the decoded task, not just its id,
  and `ChoiceSubmission.Outcome` carries the decoded `ChoiceOutcome`. Answers
  and outcomes are validated against the payload before any request. An
  outcome decoded with a different `allowElementDeletion` than the client's
  is rejected, so a "gone" shown as Not an issue can never send
  `delete: true`.
- `allowElementDeletion` is a client constructor/initializer parameter.
  There is no separate config type. Pass it to
  `work(allowElementDeletion)` / `choiceOutcomes(allowElementDeletion)`.
- `TaskWork.Choice` also exposes `match`. `ChoiceEligibility` is
  `(eligible, deleteAllowed, reason)`.
- The extra problems are `OsmScopeRequired` (403 for `osm:tagfix`) and
  `UnsupportedTask` (422). `lock_required` maps to `LockLost` and
  `invalid_transition` to `InvalidTransition`. `StatusPending.changesetId`
  and `InvalidSubmission.detail` are nullable.
- Retries are narrower than above:
  - After an unknown outcome, only a **non-editing** outcome is resent, and
    only when a fresh read shows the caller still holds the lock. Answers and
    deletes are never auto-resent, because the first request may still be
    uploading. They throw `OutcomeUnknown` with the lock kept.
  - If a resend fails without proving that nothing was applied (for example
    `lock_required` because the first attempt finished meanwhile), the first
    error is kept (`OutcomeUnknown`, or `StatusPending` with its changeset)
    and the lock is not released.
  - "Applied" from a fresh read needs the target status, no lock, not
    completed by someone else, and a status that differs from the one
    before submitting.
- Lengths count Unicode code points. Tag strings compare by code units
  (Swift compares UTF-8 bytes, not canonical equivalence). The 16 KiB size
  rule is server-only.
- `TaskFilter.choiceOnly` sends `cct=3&excludeStale=true`. `allowedResolutions()`
  is always empty.

## 8. Required user and ops actions

1. **Dev-OSM OAuth app:** the app used by staging must allow `write_api`
   in addition to `read_prefs`, on `master.apis.dev.openstreetmap.org`.
   Update the Komodo variables only if the client changes.
2. **Staging secrets:** set `MR_MOBILE_OSM_TOKEN_KEY` (32 random bytes,
   base64). Add `osm:tagfix` to the staging Android client's configured
   scopes.
3. **Test benches on dev OSM** (authorized by the user, dev OSM only):
   create disposable nodes with a dev-OSM account, for example 5 nodes tagged `amenity=bench`:
   - some with no `backrest`
   - one already with `backrest=yes`, and one later retagged or deleted in
     another editor (to test ineligibility)
   - one that is a member of a way (to test `element_in_use`)
4. **Pilot challenge:** a new disposable choice challenge on staging that
   points at those node ids. Upload it with `addFileTasks?report=true`
   through a temporary super-key, removed afterwards, as in earlier
   probes.
5. **Backup and deploy:** take a Restic snapshot before deploying the fork
   change (evolution: two new tables), then deploy through Komodo.
6. **Production (later):** a new production-OSM OAuth app with
   `write_api`, a production deployment, and an account that is
   accountable for the edits. Not part of v1.
7. **Upstream:** propose meta.type 3, the `cct` filter, and the
   addFileTasks `report` option (user).

## 9. Implementation order

1. **Backend:**
   - `ChoiceWork` model and validator, plus ingest hooks and `report`
   - the `cct` filter
   - `osm:tagfix` scope, OSM token storage and the gate body exception
   - `choice/check`, `choice_stale` and `excludeStale`
   - the `/choice` endpoint, tested against a fake OSM server
     (including ineligible-at-submit and an OSM 409 re-check):
     - success
     - each 409 path
     - OSM 409 or 412 after the read
     - 401
     - 5xx after the changeset is created (still closed)
     - status write failing after the upload, then a retry with no
       second upload
     - delete with in-use and with tags that no longer match the guard
     - a grant without `osm:tagfix`
     - a body over 2048 bytes or a chunked body
     - web session or API key → `mobile_only`
2. **SDK:** types and decoding, `submitChoice`, the problems, and the
   deletion flag, plus shared fixtures.
3. **Android demo:** a choice UI with "Can't tell" and the tag preview,
   and with the other task kinds hidden.
4. **Live:** the §8 steps, then device acceptance on dev OSM.
