# Agent instructions

See ~/.agents/AGENTS.md.

Native Kotlin and Swift SDKs for the deployed MapRoulette API. No Rust/FFI.
Keep behavioral contracts and fixtures aligned on both platforms; idiomatic public APIs may differ.
Supported writes are only the bare task lifecycle: start, refreshLock, release, POST skip and
status 1/2/5/6 (see docs/task-completion.md). GET does not guarantee read-only: never probe
start/release/refreshLock, locks, skip, comments or status writes as retrieval operations.
During development, writes may target only a disposable staging deployment, never production
MapRoulette; contract tests use mock transports.
Never log credentials or raw whoami responses (which include an API key).
Plan and append-only work log: ~/obsidian/agent/maproulette-mobile-sdk/TASKS.md.
No commits or publishing without explicit user instruction.
