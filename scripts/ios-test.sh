#!/bin/sh
# Builds the iOS example and runs its unit tests on an available iPhone simulator.
# Usage: scripts/ios-test.sh [extra xcodebuild arguments]
# Set IOS_SIMULATOR_ID to pick a specific simulator.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
command -v xcodebuild >/dev/null 2>&1 || { echo "xcodebuild not found: install Xcode 16 or newer." >&2; exit 1; }
id=${IOS_SIMULATOR_ID:-$(xcrun simctl list -j devices available | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
runtimes = sorted((r for r in devices if "iOS" in r), reverse=True)
ids = [d["udid"] for r in runtimes for d in devices[r] if d["name"].startswith("iPhone")]
print(ids[0] if ids else "")
')}
[ -n "$id" ] || { echo "No available iPhone simulator; create one in Xcode." >&2; exit 1; }
echo "Using simulator $id"
xcodebuild -project "$root/ios-example/MapRouletteExample.xcodeproj" -scheme MapRouletteExample \
  -destination "platform=iOS Simulator,id=$id" -derivedDataPath "$root/tmp/ios-dd" \
  -only-testing:MapRouletteExampleTests "$@" test
