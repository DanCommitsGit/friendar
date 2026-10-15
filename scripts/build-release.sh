#!/usr/bin/env bash
# Usage: build-release.sh <version>, for example build-release.sh 1.2.3
# Signing settings come from the FRIENDAR_KEYSTORE_* and FRIENDAR_KEY_* environment variables.
set -euo pipefail

version="$1"
export FRIENDAR_VERSION="$version"

./gradlew testDebugUnitTest assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$apk"

mkdir -p release-assets
cp "$apk" "release-assets/friendar-$version.apk"
(cd release-assets && sha256sum "friendar-$version.apk" > "friendar-$version.apk.sha256")
