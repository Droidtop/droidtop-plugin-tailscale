#!/usr/bin/env bash
# Builds Tailscale for droidtop (dev.droidtop.tailscale) into build/tailscale-droidtop-unsigned.apk: upstream
# tailscale-android at the tag and commit tailscale.pin names, unchanged but for apply-overlay.py's checked edits and
# added files (droidtop's app_interface service, its facets and its plugin manifest asset). Unsigned: sign.sh is the
# only script that touches the key, and CI runs it in a job of its own.
#
# Upstream's own Makefile builds libtailscale (gomobile, with the Go toolchain upstream pins in go.toolchain.rev) and
# the version file; its Gradle build then runs the unit tests, the overlay's among them, and assembles the release.
#
# Prerequisites: ANDROID_HOME with platform android-36 and NDK 23.1.7779620 (upstream's ndkVersion), JDK 17, git,
# make, python3, curl.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=tailscale.pin
. ./tailscale.pin
: "${ANDROID_HOME:?set ANDROID_HOME to an Android SDK}"

if [ -e build ]; then
  echo "build/ already exists; start from a clean checkout" >&2
  exit 1
fi
test -f vendor/lib-plugins/android/aidl/dev/droidtop/pluginhost/IPluginRuntime.aidl \
  || { echo "vendor/lib-plugins is missing: git submodule update --init" >&2; exit 1; }
mkdir -p build

git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$TAILSCALE_TAG" \
  https://github.com/tailscale/tailscale-android.git build/upstream
actual=$(git -C build/upstream rev-parse HEAD)
if [ "$actual" != "$TAILSCALE_COMMIT" ]; then
  echo "tailscale-android $TAILSCALE_TAG is $actual, not the pinned $TAILSCALE_COMMIT" >&2
  exit 1
fi
python3 apply-overlay.py build/upstream vendor/lib-plugins

(
  cd build/upstream
  make libtailscale version
  cd android
  ./gradlew --no-daemon test assembleRelease
)
apk=$(find build/upstream/android/build/outputs/apk/release -name '*-unsigned.apk' | head -1)
test -n "$apk" || { echo "no unsigned release APK was built" >&2; exit 1; }
cp "$apk" build/tailscale-droidtop-unsigned.apk
echo "Built build/tailscale-droidtop-unsigned.apk"
ls -l build/tailscale-droidtop-unsigned.apk
