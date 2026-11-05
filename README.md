# droidtop-plugin-tailscale

Tailscale for droidtop (`dev.droidtop.tailscale`): Tailscale's own Android app, built from upstream
[tailscale-android](https://github.com/tailscale/tailscale-android) with droidtop's plugin layer, as an
**Application Interface plugin** (droidtop `docs/plugin-api.md` 1.3). Decided on Droidtop/tracker#645. The owner,
2026-11-05: "Tailscale applications do it too. Look at the tailscale code, ktailctl ... We're building a local
tailscale control plugin, after all. Build it as ab Application Interface plugin".

## Why an app of its own

On Android only an app with its own `VpnService` can be a tailnet node, and the official app keeps its LocalAPI
inside its own process: other apps cannot read its peers. So this is that app, with three additions:

- **Its own package**, `dev.droidtop.tailscale`, labelled "Tailscale for droidtop", so it installs beside the official
  app. Android runs one VPN at a time.
- **The plugin manifest** `assets/droidtop-plugin.json` (`kind: app_interface`): `ui.facets` for its page and the
  Quick Menu, and the permission `peers.addresses`.
- **The service** `dev.droidtop.tailscale.DroidtopInterfaceService` (action `dev.droidtop.plugin.APP_INTERFACE`),
  implementing `IPluginRuntime` from Droidtop/lib-plugins' AIDL (`vendor/lib-plugins`, compiled with the build). It
  answers droidtop's package signed with droidtop's pinned certificate and nothing else.
  - While droidtop holds it, the addresses of the tailnet's online devices (from upstream's `Notifier`, the IPN bus)
    go to droidtop's `peers.set_addresses` whenever they change, and an empty list when the tailnet goes down.
  - `ui.facets get` answers the state, a switch (`set_on` starts or stops upstream's VPN service) and, on its page,
    this device's tailnet address and the devices. Signing in, and allowing the VPN the first time, happen in the app.

Upstream's code is not changed: `apply-overlay.py` adds the overlay's files and makes five edits, each to text that
must occur exactly once upstream, so moving the pin to an upstream that changed it stops the build.

## Building

`.github/workflows/build.yml` runs `build.sh`: it clones upstream at `tailscale.pin`, checks the commit, applies the
overlay, builds libtailscale with upstream's Makefile (its own Go toolchain and gomobile), and runs Gradle's
`test` (the overlay's `TailnetPeersTest` among them) and `assembleRelease`. On `plugin-core` and `plugin-v*` tags,
`sign.sh` signs the APK with APK Signature Scheme v2 and v3 using the repository's Android key
(`TAILSCALE_ANDROID_KEYSTORE_BASE64`, `TAILSCALE_ANDROID_KEYSTORE_PASSWORD`); without the key it stays unsigned.

## Branches

The owner, 2026-11-05: "ALL plugins should keep the recipe on plugin-core". Work lands on `plugin-core` from `ci/*`
branches once CI is green.

## Licence

Upstream tailscale-android is BSD-3-Clause; see `NOTICE`. This repository's own files are GPL-3.0-or-later.
