# Changelog

## Unreleased

### Added
- Tailscale for droidtop (`dev.droidtop.tailscale`, Droidtop/tracker#645): upstream tailscale-android 1.104.5 with
  droidtop's app_interface plugin layer. While droidtop holds it, the tailnet's online devices' addresses go to
  droidtop's `peers.set_addresses`, so KDE Connect reaches them away from home. droidtop draws its status, an on/off
  switch and, on its page, this device's address and the tailnet's devices.
