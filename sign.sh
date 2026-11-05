#!/usr/bin/env bash
# Signs build/tailscale-droidtop-unsigned.apk into build/tailscale-droidtop.apk with APK Signature Scheme v2 and v3
# (droidtop checks an app_interface plugin by its APK signature and refuses a v1-only APK; docs/plugin-api.md 1.3).
# The key is the repository's own Android signing key, which droidtop's "Keys you trust" or its pinned official
# certificate names; it is never droidtop's plugin bundle key.
#
#   TAILSCALE_ANDROID_KEYSTORE           path to a PKCS#12 keystore
#   TAILSCALE_ANDROID_KEYSTORE_PASSWORD  its password
#   TAILSCALE_ANDROID_KEY_ALIAS          the key's alias (default: tailscale-droidtop)
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?set ANDROID_HOME to an Android SDK}"
: "${TAILSCALE_ANDROID_KEYSTORE:?set TAILSCALE_ANDROID_KEYSTORE}"
: "${TAILSCALE_ANDROID_KEYSTORE_PASSWORD:?set TAILSCALE_ANDROID_KEYSTORE_PASSWORD}"
alias="${TAILSCALE_ANDROID_KEY_ALIAS:-tailscale-droidtop}"
tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

"$tools/zipalign" -f -p 4 build/tailscale-droidtop-unsigned.apk build/aligned.apk
"$tools/apksigner" sign --ks "$TAILSCALE_ANDROID_KEYSTORE" --ks-type PKCS12 --ks-key-alias "$alias" \
  --ks-pass env:TAILSCALE_ANDROID_KEYSTORE_PASSWORD \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out build/tailscale-droidtop.apk build/aligned.apk
rm -f build/aligned.apk
"$tools/apksigner" verify --print-certs build/tailscale-droidtop.apk
