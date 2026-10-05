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
- Minimal identity extraction, credential validation and nonsecret error descriptions.
- Per-user credential isolation across clients sharing a transport, anonymous requests,
  key rotation and logout without retaining an earlier key.
- Empty 404 bodies, invalid successful JSON, rate limits and Retry-After.
- Cancellation preserved as cancellation rather than a network/protocol error.

Kotlin additionally exercises its real OkHttp transport against local servers to verify
redirect refusal and cancellation. Swift's shared suite uses the injected transport seam.
No test contacts production or changes MapRoulette data.
