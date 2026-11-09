#!/usr/bin/env python3
"""Inspect the profile-related content of a built APK (no device needed).

Usage: python check_apk_profile.py <app.apk> [--aapt2 <path to aapt2>]

Reports what the package contains; it does NOT tell whether ART will use it. Whether the profile
is installed and the code compiled has to be read from the device (check_device_compile_state.sh).
Checks: assets/dexopt/baseline.prof(m) presence/header/hash, classes*.dex layout, ProfileInstaller
classes in the dex files, and (with aapt2) the manifest's profileable / startup-provider entries.
"""
import hashlib
import subprocess
import sys
import zipfile


def header(data):
    return data[:8].decode("ascii", "replace").replace("\x00", "\0")


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    apk = argv[0]
    aapt2 = argv[argv.index("--aapt2") + 1] if "--aapt2" in argv else None
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        print(f"apk: {apk}\nentries: {len(names)}")
        for name in names:
            if name.startswith("/") or ".." in name.split("/"):
                print(f"WARN absolute or out-of-bounds entry: {name}")
        for wanted in ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"):
            if wanted in names:
                data = archive.read(wanted)
                print(f"{wanted}: present size={len(data)} header={header(data)!r} "
                      f"sha256={hashlib.sha256(data).hexdigest()}")
            else:
                print(f"{wanted}: ABSENT")
        dex = sorted(name for name in names if name.startswith("classes") and name.endswith(".dex"))
        print("dex files: " + ", ".join(f"{name}={archive.getinfo(name).file_size}" for name in dex))
        needles = {
            "ProfileInstallerInitializer": b"androidx/profileinstaller/ProfileInstallerInitializer",
            "ProfileInstallReceiver": b"androidx/profileinstaller/ProfileInstallReceiver",
        }
        for label, needle in needles.items():
            where = [name for name in dex if needle in archive.read(name)]
            print(f"{label} in dex: {where or 'ABSENT'}")
        manifest_bytes = archive.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else b""
    if aapt2:
        result = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                                capture_output=True, text=True, check=False)
        lines = result.stdout.splitlines()
        wanted = ("profileable", "debuggable", "ProfileInstall", "InitializationProvider", "package=")
        print("manifest highlights (aapt2):")
        for line in lines:
            if any(key in line for key in wanted):
                print("  " + line.strip())
        if result.returncode != 0:
            print(f"aapt2 failed ({result.returncode}): {result.stderr.strip()}")
    else:
        print(f"manifest size {len(manifest_bytes)} bytes (pass --aapt2 for a readable dump)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
