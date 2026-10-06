#!/bin/sh
# Runs the Kotlin and Swift SDK test suites. Java comes from mise (mise.toml pins JDK 17);
# Swift comes from Xcode (Swift 6 / Xcode 16 or newer).
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if command -v mise >/dev/null 2>&1; then
  run_java() { mise exec --cd "$root" -- "$@"; }
elif [ -n "${JAVA_HOME:-}" ]; then
  run_java() { "$@"; }
else
  echo "JDK 17 not found: install mise (https://mise.jdx.dev) and run 'mise install' in $root," >&2
  echo "or set JAVA_HOME to a JDK 17 (for example Android Studio's bundled JBR)." >&2
  exit 1
fi
command -v swift >/dev/null 2>&1 || { echo "swift not found: install Xcode 16 or newer." >&2; exit 1; }

run_java "$root/kotlin/gradlew" -p "$root/kotlin" test --console=plain
swift test --package-path "$root"
