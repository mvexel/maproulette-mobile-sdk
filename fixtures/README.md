# Shared behavioral fixtures

`contract.json` contains synthetic, nonsecret examples of the deployed API shapes.
Both native suites load the same file directly; no generated or copied fixture variants.
IDs and API key text here are invented. These fixtures complement, rather than replace,
the read-only live probes documented in `docs/api-probes.json` and backend-source evidence.

The contract tests cover:

- Integer project references in direct challenge reads versus embedded projects in search.
- Null/missing optional fields, unknown fields/statuses and strict required identities.
- Full GeoJSON and cooperative-work preservation without interpreting OSM identity.
- Challenge offset pagination versus task page-number pagination; continuation binding.
- ANY-tag encoding, local-survey inclusion, enabled/archive and spatial challenge filters.
- Spatial summary envelopes and distinction from full task details.
- Spatial summary `point` objects preserve the deployed `{ "lat": number,
  "lng": number }` shape. This field is not a GeoJSON Point; consumers must read
  named latitude/longitude fields rather than GeoJSON `coordinates`. Task-detail
  `geometries` remains a GeoJSON FeatureCollection and is a separate contract.
- All-challenge spatial search (empty/default challenge IDs), preserving other filters,
  versus selected-challenge membership validation and separate continuation scopes.
- Minimal identity extraction, credential validation and nonsecret error descriptions.
- Per-user credential isolation across clients sharing a transport, anonymous requests,
  key rotation and logout without retaining an earlier key.
- Empty 404 bodies, invalid successful JSON, rate limits and Retry-After.
- Cancellation preserved as cancellation rather than a network/protocol error.

Kotlin additionally exercises its real OkHttp transport against local servers to verify
redirect refusal and cancellation. Swift's shared suite uses the injected transport seam.
No test contacts production or changes MapRoulette data.

## All-challenge spatial search evidence (2026-10-05)

A read-only anonymous GET to
`https://maproulette.org/api/v2/tasks/box/144.98/-37.83/145.00/-37.81?ca=true&cLocal=1&tStatus=-1&limit=2&page=0&includeTotal=true&sort=id&order=ASC`
returned HTTP 200 in about three seconds: total 1704, task IDs 253249 and 253250,
both from challenge 227. This query intentionally omitted `cid`. These mutable
IDs/counts are evidence only, not permanent test assertions.

At inspected backend commit `b9b2e69b0115cbfb7a1f997a29dfc3b2ca32512a`,
`SearchParameters.scala` defines challenge IDs as `None` by default and preserves
that value when `cid` is absent (lines 26 and 367–370). The framework
`TaskController.getTasksInBoundingBox` forwards those search parameters plus
bounding box and paging (lines 94–128).

Consequently empty SDK challenge IDs mean no challenge-ID restriction, rather
than an empty result. Bounds, status/archive filters and server visibility rules
still apply; `cLocal=1` includes local-survey challenges. Selected IDs retain
strict returned-membership validation. This operation searches task locations,
not exact geometry intersections, and makes no completeness guarantee beyond
its explicit page. Broad bounds and requesting totals may be expensive.

## Spatial response latency and Android transport (2026-10-05)

Paired anonymous SLC reads for bounds `-111.91/40.75/-111.87/40.78`, statuses
`0,3,6`, `ca=false`, `cLocal=1`, `sort=id`, `limit=2` took 18.18 seconds with
`includeTotal=true` and 18.29 seconds with `includeTotal=false`. Both returned
task IDs 17607509 and 17607886; the total was 1712. Later repeats completed in
about 1.2 seconds, so these observations do not isolate the database bottleneck.

Backend `TaskClusterRepository.queryTasksInBoundingBox` computes a count before
fetching the page regardless of `includeTotal`; the controller's flag only
changes the response envelope. The SDK therefore retains its existing query
shape and stable sorting.

OkHttp's default ten-second read timeout was shorter than the SDK's explicit
30-second whole-call deadline. The transport now sets read timeout to 30 seconds
as well; the whole-call deadline continues to bound the complete request.
A local HTTP regression test delays response headers for 11 seconds and verifies
successful completion, reproducing the failure without depending on production.

## Bounded map markers

`findTaskMarkers(filter, limit)` uses the frontend's read-only
`PUT /markers/box/{west}/{south}/{east}/{north}` route with JSON `{}`. The default
limit is 100, accepted range 1–1000. It returns an unordered bounded list of
`TaskSummary` values, without totals or continuation; reaching the limit may mean
truncation. This is separate from sorted, paginated `findTasksInBounds`.

All-challenge marker discovery sends `ce=true&pe=true` to include only enabled
challenges/projects. Explicit challenge IDs bypass those two filters, matching
the frontend. The SDK deliberately uses `cLocal=1` to include survey challenges;
archive and task-status settings come from `TaskFilter`. Selected IDs retain
membership validation. `excludeLocked=true` follows the frontend: despite the
parameter name, the source passes it as `ignoreLocked`, and
`TaskFilterMixin.filterOutLocked` returns the unfiltered query when true
(lines 67–70 at inspected backend commit above). Markers may therefore include
tasks locked by another user; this read does not acquire any lock.

An anonymous probe with SLC bounds `-111.91/40.75/-111.87/40.78`, `cLocal=1`,
`ca=false`, statuses `0,3,6`, limit 100 returned 100 summaries in 0.41 seconds,
versus 6.76 seconds for the sorted/counting task-list route in a paired probe.
These timings are observations, not a latency guarantee. Source confirms the
marker repository performs a single bounded SELECT without counting or explicit
sorting. The PUT request is read-only despite its HTTP verb.

Both suites assert explicit PUT, JSON body/content type, filter policy and limit
validation. Kotlin additionally sends a real PUT through OkHttp to a local
server and checks its received method, body and headers. Existing reads retain
GET as the transport default, and request descriptions do not expose bodies or
credentials.
