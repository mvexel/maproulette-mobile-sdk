#!/bin/sh
# Drives the main flows on an iOS Simulator (Screenshots scheme, in-process -demo backend: no
# network writes) and saves the named screenshots to tmp/shots/ios/.
# Usage: [DESTINATION=…] scripts/screenshots.sh [extra xcodebuild settings…]
# Live read-only staging shots: LIVE=1 scripts/screenshots.sh MAPROULETTE_BASE_URL=https://mr-api.osm.lol
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
root=$(dirname "$here")
destination=${DESTINATION:-platform=iOS Simulator,name=iPhone 17,OS=26.2}
out="$root/tmp/shots/ios"
result="$root/tmp/ios-screenshots.xcresult"
mkdir -p "$out"
rm -rf "$result"
TEST_RUNNER_LIVE="${LIVE:-0}" xcodebuild -project "$here/MapRouletteExample.xcodeproj" -scheme Screenshots \
  -destination "$destination" -derivedDataPath "$root/tmp/ios-dd" \
  -resultBundlePath "$result" "$@" test
attachments=$(mktemp -d)
xcrun xcresulttool export attachments --path "$result" --output-path "$attachments" >/dev/null
python3 - "$attachments" "$out" <<'PY'
import json, shutil, sys, re
src, out = sys.argv[1], sys.argv[2]
for test in json.load(open(f"{src}/manifest.json")):
    for a in test.get("attachments", []):
        name = a.get("suggestedHumanReadableName", "")
        m = re.match(r"(\d\d[a-z]?-[a-z0-9-]+)", name)
        if m:
            shutil.copy(f"{src}/{a['exportedFileName']}", f"{out}/{m.group(1)}.png")
            print(f"{out}/{m.group(1)}.png")
PY
rm -rf "$attachments"
