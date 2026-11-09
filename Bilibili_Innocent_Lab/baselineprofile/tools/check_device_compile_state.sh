#!/usr/bin/env bash
# Print the installation and ART compilation state on an explicitly selected device.
# Usage: check_device_compile_state.sh DEVICE_SERIAL [package]
# All operations are read-only; no serial is selected implicitly.
set -u
serial="${1:-}"
package="${2:-com.Bilibili_Innocent_Lab.xposedmodule}"
if [[ -z "$serial" ]]; then
  echo "refusing: first argument must be an explicit adb device serial" >&2
  exit 2
fi
adb_() { adb -s "$serial" "$@"; }
echo "== device"
adb_ shell getprop ro.build.fingerprint
adb_ shell getprop ro.product.cpu.abilist
adb_ shell getprop ro.kernel.qemu
echo "== package"
adb_ shell pm list packages -i "$package"
adb_ shell pm path "$package"
echo "== dexopt state (status/reason per ISA)"
adb_ shell dumpsys package dexopt | grep -A8 -F "[$package]"
echo "== package dump (versionName/versionCode/installer/flags)"
adb_ shell dumpsys package "$package" | grep -E "versionName|versionCode|installerPackageName|installInitiatingPackageName|flags=|Dexopt state|status=|reason=" | head -20
echo "== reference profiles on disk"
adb_ shell ls -l "/data/misc/profiles/ref/$package" "/data/misc/profiles/cur/0/$package" 2>&1 | head -10
