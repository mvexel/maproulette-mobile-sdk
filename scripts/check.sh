#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
"$root/kotlin/gradlew" -p "$root/kotlin" test --console=plain
swift test --package-path "$root/swift"
