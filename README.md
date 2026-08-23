# Bilibili Innocent Lab

Android Xposed/LSPosed module for `tv.danmaku.bili`. The Gradle project lives
in [`Bilibili_Innocent_Lab/`](Bilibili_Innocent_Lab/); the project notes and
device-validation history are in [`AGENTS.md`](AGENTS.md).

## Development baseline

- Build with `gradlew.bat assembleDebug --console=plain --no-daemon`.
- Verify the generated APK timestamp and, on device, compare the installed
  `base.apk` checksum before functional testing.
- Do not commit decompiled APKs, LSPosed exports, device logs, or build output.
- The workspace root is the sole Git repository. The former empty nested Git
  metadata directory was removed without touching source or build files; create
  the initial project commit from this root.

See [`docs/architecture.md`](docs/architecture.md) for runtime boundaries and
[`docs/verification.md`](docs/verification.md) for the focused regression
matrix.
