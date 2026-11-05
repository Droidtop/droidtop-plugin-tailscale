#!/usr/bin/env python3
"""Applies droidtop's plugin layer to an upstream tailscale-android checkout (build.sh runs it).

Every edit names text that must occur exactly once in upstream's file, so a pin moved to an upstream that changed
that text stops the build here instead of building something else. Usage: apply-overlay.py <upstream> <lib-plugins>
"""

import os
import shutil
import sys

upstream, lib_plugins = (os.path.abspath(a) for a in sys.argv[1:3])
here = os.path.dirname(os.path.abspath(__file__))


def edit(rel, old, new):
    path = os.path.join(upstream, rel)
    text = open(path, encoding="utf-8").read()
    if text.count(old) != 1:
        sys.exit(f"{rel}: expected one {old!r}, found {text.count(old)}; the overlay needs updating for this upstream")
    open(path, "w", encoding="utf-8").write(text.replace(old, new))


# The overlay's own files: the APP_INTERFACE service, the facets, the plugin manifest asset, the test.
overlay = os.path.join(here, "overlay")
for root, _, files in os.walk(overlay):
    for name in files:
        src = os.path.join(root, name)
        dst = os.path.join(upstream, os.path.relpath(src, overlay))
        if os.path.exists(dst):
            sys.exit(f"{os.path.relpath(dst, upstream)} exists upstream; the overlay only adds files")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)

# Its own package, so it installs beside the official Tailscale app; upstream's code stays in com.tailscale.ipn.
edit("android/build.gradle.kts", "    minSdk = 26\n", '    applicationId = "dev.droidtop.tailscale"\n    minSdk = 26\n')
# lib-plugins' AIDL, compiled with this build (droidtop docs/plugin-api.md 1.3: no copy can drift).
edit("android/build.gradle.kts", "    buildConfig = true\n", "    buildConfig = true\n    aidl = true\n")
edit(
    "android/build.gradle.kts",
    '  namespace = "com.tailscale.ipn"\n',
    '  namespace = "com.tailscale.ipn"\n'
    f'  sourceSets.getByName("main").aidl.srcDir("{os.path.join(lib_plugins, "android", "aidl")}")\n',
)

# Named apart from the official app on the launcher and in Android's VPN settings.
edit("android/src/main/AndroidManifest.xml", 'android:label="Tailscale"', 'android:label="Tailscale for droidtop"')
# The service droidtop binds, exported because droidtop is another app; it answers droidtop's uid alone.
edit(
    "android/src/main/AndroidManifest.xml",
    "    </application>",
    """        <service
            android:name="dev.droidtop.tailscale.DroidtopInterfaceService"
            android:exported="true">
            <intent-filter>
                <action android:name="dev.droidtop.plugin.APP_INTERFACE" />
            </intent-filter>
        </service>
    </application>
    <queries>
        <package android:name="dev.droidtop.app" />
    </queries>""",
)
print("overlay applied")
