#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build

echo 'Checking Xcode project and secure configuration'
python3 scripts/check-project.py
xcodebuild -version
plutil -lint DictaLab.xcodeproj/project.pbxproj DictaLab/Info.plist

echo 'Selecting an available iPhone simulator'
xcrun simctl list devices available --json > build/simulators.json
SIMULATOR_ID=$(python3 - <<'PY'
import json
from pathlib import Path
devices = json.loads(Path('build/simulators.json').read_text())['devices']
iphones = [d for runtime, rows in devices.items() if 'iOS' in runtime
           for d in rows if d.get('isAvailable') and d['name'].startswith('iPhone')]
if not iphones:
    raise SystemExit('No iPhone simulator is installed on this runner')
print(iphones[0]['udid'])
PY
)

echo 'Building SwiftUI app and running iOS tests'
xcodebuild test \
  -project DictaLab.xcodeproj -scheme DictaLab \
  -configuration Debug -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath build/DerivedData -resultBundlePath build/Tests.xcresult \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- \
  2>&1 | tee build/test.log

echo 'Building the arm64 iPhone application without signing credentials'
xcodebuild build \
  -project DictaLab.xcodeproj -scheme DictaLab \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath build/DeviceData CODE_SIGNING_ALLOWED=NO \
  2>&1 | tee build/device.log

echo 'Packaging an unsigned IPA for local signing with AltStore'
mkdir -p build/package/Payload
ditto build/DeviceData/Build/Products/Release-iphoneos/DictaLab.app build/package/Payload/DictaLab.app
ditto -c -k --keepParent build/package/Payload build/DictaLab-unsigned.ipa
python3 scripts/check-project.py build/DictaLab-unsigned.ipa
shasum -a 256 build/DictaLab-unsigned.ipa > build/DictaLab-unsigned.ipa.sha256

# Simulator screenshot verifies the app can launch without an Apple signing account.
xcrun simctl boot "$SIMULATOR_ID" 2>/dev/null || true
xcrun simctl bootstatus "$SIMULATOR_ID" -b
xcrun simctl install "$SIMULATOR_ID" build/DerivedData/Build/Products/Debug-iphonesimulator/DictaLab.app
xcrun simctl launch "$SIMULATOR_ID" com.dictalab.personal
sleep 5
xcrun simctl io "$SIMULATOR_ID" screenshot build/DictaLab-iPhone.png

echo 'Unsigned IPA is ready. It must be signed on the owner device before installation.'
