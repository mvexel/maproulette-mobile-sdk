# Challenge and task kinds: SDK model design

> Status: design record; implemented in 0.1.0. The consumer docs are [docs/guide/](../guide/getting-started.md).

Phase 1 design (2026-10-05), implemented with the changes below. This document goes
with [task-completion.md](task-completion.md). Sources are the backend
public fork `feat/mobile-oauth` at `a31e073` and `maproulette3` from
January 2026.

Direction change (2026-10-06): mobile is moving to tasks completable in
place. See [mobile-choice-challenges.md](mobile-choice-challenges.md) for
the proposed multiple-choice `cooperativeWork` type 3 and the mobile
eligibility rule that replaces §4. Implemented in the SDK: `MobileSupport` is now
`IN_PLACE | UNSUPPORTED` (standard, tag-fix and change-file tasks are
unsupported) and `allowedResolutions()` was removed in 0.1.0, so the §4
`FULL`/`RESOLVE_WITHOUT_FIX` rules below are historical.

The aim is to let a mobile client decide, from SDK types alone, **how to
present a task** and **which resolutions it can offer without editing
OSM**.

## 1. What the backend actually has

MapRoulette has no single "challenge type" field. A task's kind comes from
several independent fields:

| Source | Field | Values / meaning |
| --- | --- | --- |
| Challenge `cooperativeType` (general) | Int | `0` none (standard), `1` tags ("Quick Fix" / tag fix), `2` change file. These are the backend constants `COOPERATIVE_NONE/TAGS/CHANGEFILE`. |
| Task `cooperativeWork` | JSON or absent | The per-task payload. **This field, not the challenge's `cooperativeType`, decides how the web UI behaves** (`AsCooperativeWork.isCooperative()` checks whether the field is present). |
| `cooperativeWork.meta.version` | 1 or 2 | v1 has no `type` and is always a tag fix. v2 has `meta.type` 1 (tags) or 2 (change file). |
| Tag fix payload (`type` 1) | `operations[]` | Each item: `{operationType: "createElement" \| "modifyElement" \| "deleteElement", data: {id: "way/123", operations: [{operation: "setTags", data: {k: v}}, {operation: "unsetTags", data: [k]}]}}`. The web UI throws an error on an unknown `operationType`. Only `modifyElement` with set/unset tags is ever applied. |
| Change file payload (`type` 2) | `file{format, encoding, content}` | Base64 OSC/XML. The backend serves it as XML at `GET /task/:id/cooperative/change/:filename`. **The web UI allows only JOSM** for these tasks. The backend has no route that applies a change file. |
| Challenge `instruction`, task `instruction` | Markdown | The task's instruction overrides the challenge's if it is not empty (`TaskInstructions.jsx`). |
| Challenge `requiresLocal` | Bool | Needs local knowledge or a survey. Filtering already exists (`LocalSurvey`). |
| Challenge `difficulty` | 1 easy, 2 normal, 3 expert | |
| Challenge `reviewSetting` | 0 not required, 1 requested | |
| Challenge `requireConfirmation` | Bool | The web UI always shows the confirmation step, even if the user turned it off. |
| Challenge `osmIdProperty` | String (may be empty) | The feature property that holds the OSM id. The defaults are `@id`, `osmid`, `osmIdentifier` and `id`, with values such as `node/123`. |
| Challenge `checkinComment`, `checkinSource` | String | The OSM changeset comment and `source` tag. These matter only for OSM upload. |
| Task `geometries` | GeoJSON FeatureCollection | Any mix of Point, LineString, Polygon and Multi* geometries. Properties are arbitrary key/value pairs. |
| Task `completionResponses` | JSON string or absent | The answers to instruction form fields (see §2). |
| Task `bundleId`, challenge `taskBundleIdProperty` | | Bundles are out of scope for mobile v1. |

The backend has **no "mobile-friendly" flag**, either on challenges or on
tasks. Whether a task suits mobile has to be inferred (see §4).

The staging bench challenge 1 has `cooperativeType 0`, `requiresLocal
false`, `reviewSetting 0`, `requireConfirmation false`, an empty
`taskWidgetLayout` and an empty `osmIdProperty` (read live).

## 2. Instruction markdown and templating (web behavior)

`MarkdownContent` with property replacement, short codes and form fields:

- **Property substitution:** `{{name}}` is replaced by the feature property
  (or MapRoulette property) `name`. A missing value becomes `""`. The regex
  is `(^|[^{])\{\{([^{][^}]*)}}`, so `{{{` is not a substitution.
- **MapRoulette properties:** `#mrTaskId`, `#osmId`, `#osmType`, and map
  state such as `#mapZoom`, `#mapLat`, `#mapLon`, `#mapBBox` and
  `#mapWest/South/East/North`. The map-state properties depend on the
  client's own map viewport.
- **Short codes** are either `{{{ ... }}}` or `[ ... ]` not followed by `(`,
  so markdown links are excluded:
  - `[select "Label" name="prop" values="a,b,c"]`: a form field that
    records `completionResponses.prop`.
  - `[checkbox "Label" name="prop"]`: a form field (boolean).
  - `[copyable "text"]`: copy-to-clipboard text.
  - OSM element links: `n123`, `way 456`, `relation/789`, and so on.
  - Map viewport short codes and `@user` mentions.
- If any form field is present, the web UI sets `needsResponses`. When the
  user confirms with no responses (and is not skipping), the UI shows the
  instructions again. This is a **soft requirement**: submitting without
  responses is still possible.

## 3. What each kind needs to be resolved

| Kind | Present | Resolve Fixed | Other resolutions | Needs OSM write? |
| --- | --- | --- | --- | --- |
| **Standard** (no `cooperativeWork`) | Instruction + geometry. | `PUT /task/:id/1`: the user made the fix in OSM themselves | 2, 5, 6, skip | No. MapRoulette only records what the mapper reports. |
| **Standard with form fields** | Same. The form is shown read-only. | Same bare `PUT /task/:id/1`. **v1 never submits `completionResponses`** (decided). | Same | No |
| **Tag fix** (`cooperativeWork` type 1 or v1) | A diff of current OSM tags against the proposed tags. The web UI fetches the current element from OSM to build the diff. | **Web "Confirm" = `POST /task/:id/fix/apply`**. The backend uploads the change to OSM with the user's stored OSM token and then sets status 1. | "Reject" = 2. Also 5, 6 and skip, as plain status writes. | **Yes for Confirm.** Without OSM upload, mobile can offer only Not an issue, Already fixed, Too hard and Skip. |
| **Change file** (type 2) | An OSC file for JOSM | The mapper uploads with JOSM, then sets status 1 | 2, 5, 6, skip | **Yes** (an external editor). Mobile can show the task and offer Not an issue, Already fixed, Too hard and Skip, but no Fixed. |
| **Unknown cooperative** (other version/type, or an unknown `operationType`) | Read-only | none | Release only | Unknown, so treat as unsupported. |

Resolution meanings (user decision, 2026-10-05):

- **Fixed (1)** on a *standard* task means the user made the fix in OSM
  themselves, for example in another editor. Recording Fixed is not an OSM
  write. Suggested UI wording: "I fixed this in OSM". On tag-fix and
  change-file tasks, Fixed is **reserved for the future upload flow** and
  is not offered on mobile. Mobile does not copy the web's plain-status
  Fixed for tag fixes.
- **Already fixed (5)** means someone else had already resolved the problem
  in OSM, between task creation and the user seeing the task. Example: the
  bench gained a `backrest` tag in the meantime. It does **not** mean "I
  fixed it in another editor". Suggested wording: "Already fixed in OSM by
  someone else".
- **Not an issue (2)**: the reported problem is not real.
  **Too hard (6)**: the user cannot resolve the task.

Future idea: for tag-fix tasks, the backend (or the SDK, reading OSM
without writing) could compare the current OSM element with the suggested
tags before serving or resolving the task. If every suggested change is
already present, it could detect Already fixed automatically, or offer it
as the default. This needs one OSM API read per element.

## 4. Mobile feasibility (derived, not stored)

```text
mobileSupport(task, challenge):
  bundle (bundleId != null)                     → Unsupported(bundle)
  cooperativeWork unknown/unparseable           → Unsupported(unknownKind)
  change file                                   → ResolveWithoutFix   (no Fixed)
  tag fix                                       → ResolveWithoutFix   (Fixed needs OSM upload: future)
  standard + form fields                        → Full   (form shown, responses not submitted in v1)
  standard                                      → Full
```

`requiresLocal` and `difficulty` are presentation hints only, not limits.
Geometry handling: draw every feature. Center on the bounding box of the
collection. Pick out the OSM element from the first feature that has an
identifiable id; `osmIdProperty` takes precedence, then the default fields.
A geometry type the client cannot draw becomes a "not shown" marker, never
a decoding error.

## 5. Proposed SDK types

Both platforms decode leniently. Unknown enum values are preserved, never
thrown away, so new backend kinds degrade to `Unknown` with the raw value.

Kotlin:

```kotlin
enum class CooperativeType { NONE, TAGS, CHANGE_FILE, UNKNOWN }   // challenge-level hint
data class ChallengeKind(val cooperativeType: CooperativeType, val rawCooperativeType: Int,
                         val requiresLocal: Boolean, val difficulty: Int?, val reviewRequested: Boolean,
                         val requireConfirmation: Boolean, val osmIdProperty: String?)

sealed interface TaskWork {
    data object Standard : TaskWork
    data class TagFix(val version: Int, val edits: List<ElementTagEdit>) : TaskWork
    data class ChangeFile(val format: String?, val encoding: String?) : TaskWork   // content fetched on demand
    data class Unknown(val raw: JsonElement) : TaskWork
}
data class ElementTagEdit(val element: OsmElementRef, val kind: EditKind,     // MODIFY, CREATE, DELETE, UNKNOWN
                          val setTags: Map<String, String>, val unsetTags: List<String>)
data class OsmElementRef(val type: OsmType, val id: Long)                    // NODE, WAY, RELATION

data class Instruction(val markdown: String, val formFields: List<FormField>)
sealed interface FormField {
    val name: String
    data class Select(override val name: String, val label: String, val values: List<String>) : FormField
    data class Checkbox(override val name: String, val label: String) : FormField
}

enum class MobileSupport { FULL, RESOLVE_WITHOUT_FIX, UNSUPPORTED }
fun Task.work(): TaskWork
fun Task.instruction(challenge: Challenge): Instruction        // task overrides challenge
fun Instruction.render(properties: Map<String, String>): String // {{prop}} substitution only
fun mobileSupport(task: Task, challenge: Challenge): MobileSupport
fun allowedResolutions(task: Task, challenge: Challenge): Set<TaskResolution>
```

Swift:

```swift
public enum CooperativeType: Sendable, Equatable { case none, tags, changeFile, unknown(Int) }
public enum TaskWork: Sendable, Equatable {
  case standard
  case tagFix(version: Int, edits: [ElementTagEdit])
  case changeFile(format: String?, encoding: String?)
  case unknown(JSONValue)
}
public struct ElementTagEdit: Sendable, Equatable {
  public enum Kind: Sendable, Equatable { case modify, create, delete, unknown(String) }
  public let element: OSMElementRef, kind: Kind, setTags: [String: String], unsetTags: [String]
}
public struct OSMElementRef: Sendable, Hashable { public enum ElementType: Sendable { case node, way, relation }; public let type: ElementType, id: Int64 }
public enum FormField: Sendable, Equatable {
  case select(name: String, label: String, values: [String])
  case checkbox(name: String, label: String)
}
public struct Instruction: Sendable { public let markdown: String; public let formFields: [FormField] }
public enum MobileSupport: Sendable { case full, resolveWithoutFix, unsupported }
extension MapRouletteTask { public func work() -> TaskWork; public func instruction(challenge: Challenge) -> Instruction }
public func mobileSupport(task: MapRouletteTask, challenge: Challenge) -> MobileSupport
public func allowedResolutions(task: MapRouletteTask, challenge: Challenge) -> Set<TaskResolution>
```

Decoding rules (shared):

1. No `cooperativeWork` (or JSON `null`) → `Standard`, whatever the
   challenge's `cooperativeType` says. The task payload is authoritative,
   as in the web UI.
2. `meta.version == 1` → `TagFix`. `version == 2` and `type == 1` →
   `TagFix`. `version == 2` and `type == 2` → `ChangeFile`. Anything else →
   `Unknown(raw)`.
3. Tag fix operations: an unknown `operationType` becomes
   `EditKind.UNKNOWN`. If any edit is unknown, `mobileSupport` returns
   `UNSUPPORTED`. A malformed element id (anything other than
   `node|way|relation/<digits>`) also makes the whole work `Unknown`.
4. `cooperativeType` integers other than 0, 1 or 2 → `UNKNOWN` (Kotlin
   keeps the raw value in `rawCooperativeType`; Swift uses
   `.unknown(Int)`).
5. Form fields are parsed with the same regexes as the web UI. Only
   `select` and `checkbox` count as form fields. They are modelled for
   display. v1 does not submit them: there is no `completionResponses`
   write. Other short codes stay in
   the markdown. The SDK does **not** render markdown. It offers `{{prop}}`
   substitution and lists the form fields, and the app chooses a markdown
   renderer.
6. The `#map*` properties depend on the viewport and are left to the app.
   The SDK supplies `#mrTaskId`, `#osmId` and `#osmType` plus the feature
   properties.
7. `allowedResolutions` applies both the kind (§3) and the status
   progression rules (task-completion.md §1) to the current status, so a
   completed task offers nothing.

## 6. Fixtures needed (shared by Kotlin and Swift)

- `challenge_standard.json` (staging bench shape).
  `challenge_coop_tags.json`, `challenge_coop_changefile.json`, and
  `challenge_coop_unknown.json` (`cooperativeType: 7`).
- `task_standard.json`, plus a task with a JSON-null `cooperativeWork`.
- `task_tagfix_v1.json` (no `meta.type`) and `task_tagfix_v2.json`, with
  modify/setTags/unsetTags and a create.
- `task_changefile_v2.json` (small base64 OSC).
- `task_coop_unknown_version.json`, `task_tagfix_unknown_operation.json` and
  `task_tagfix_bad_element_id.json`.
- `instruction_forms.md` (select plus checkbox, a markdown link that must
  *not* parse as a short code, `{{{ }}}`, `{{prop}}` and a missing property)
  with expected `formFields` and rendered output.
- A geometry collection with Point, LineString, Polygon, MultiPolygon and
  one unknown type. Feature id variants: `@id` at the top level and in
  properties, `osmid`, a custom `osmIdProperty`, and a bare number.
- A bundled task, which must give `UNSUPPORTED`.
- Table tests for `mobileSupport` and `allowedResolutions` over
  kind × current status.

Real examples of tag-fix and change-file tasks should be captured
**read-only** from public `maproulette.org` challenges, with anything
user-identifying removed. A disposable staging challenge can be used
instead.

## 7. Decisions (user, 2026-10-05)

1. The task's `cooperativeWork` decides the kind, over the challenge's
   `cooperativeType`. This matches the web UI.
2. On a standard task, Fixed means "I fixed this in OSM" (the user made
   the fix themselves). Already fixed means someone else fixed it in OSM
   before the user saw the task.
3. Tag-fix tasks without upload offer Not an issue, Already fixed, Too
   hard and Skip. Fixed is reserved for the future upload flow (see
   task-completion.md §12).
4. No `completionResponses` in v1. Form fields are parsed and shown but
   not submitted.
