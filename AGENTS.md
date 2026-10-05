# Agent instructions

See ~/.agents/AGENTS.md.

Native Kotlin and Swift SDKs for the deployed MapRoulette API. No Rust/FFI.
Keep behavioral contracts and fixtures aligned on both platforms; idiomatic public APIs may differ.
Only read-only API operations are currently supported. GET does not guarantee read-only: never
probe task start/release, locks, skip, comments or status writes as retrieval operations.
Never log credentials or raw whoami responses (which include an API key).
Plan and append-only work log: ~/obsidian/agent/maproulette-mobile-sdk/TASKS.md.
No commits or publishing without explicit user instruction.
