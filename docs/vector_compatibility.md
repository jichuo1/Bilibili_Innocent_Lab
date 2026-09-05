# Vector compatibility

This is a module-side compatibility plan and acceptance record. Source inspection,
JVM tests and APK validation do not establish that a framework/device combination
works. Vector device acceptance remains pending until its matrix below is run.

## Source baselines (2026-09-05)

| Component | Fixed baseline |
| --- | --- |
| Vector stable | [v2.2 / 3080](https://github.com/JingMatrix/Vector/releases/tag/v2.2), `88f8e1faa8b4e7ce20aefabe9c295cd746ea038e` |
| Vector canary | [3110](https://github.com/JingMatrix/Vector/releases/tag/canary-3110), `c4a701aadbf9b4c7a7a65046fe4b9be322a909da` |
| Service API | `3318940876192e29cf6ab07637e899e22a87ebf0`, the published libxposed `102.0.0` tag |
| Module API used by Vector | `39cac0845771547c9c67a3e3ce255af110a54a0e` |

Vector's module API adds only a Java-entry documentation clarification over the
published `102.0.0` tag. Keep the existing published dependencies, with module API
`compileOnly`; its extra test dependency is only for the JVM Hook contract tests.
Do not bundle framework API classes in the production APK.

## Module-side behavior

- Keep the single Modern entry, fixed `tv.danmaku.bili` / `system` scope, API 102,
  protective exception mode and disabled automatic hot reload. Framework names
  select manager guidance only; capability and validated configuration govern
  operation.
- `RemoteHookConfigCommitter` deduplicates only against a successful commit on
  the same service connection, with a matching generation, digest and target
  document. A failed write, failed cache validation or connection change requires
  another real commit. A new publisher also commits even if the SDK cache looks
  current. Reconfirmation of an identical valid document retains its generation,
  avoiding an unnecessary host-restart prompt on every module cold start.
  No private preference access or alternate authorization channel is added.
- Service 102 updates its client cache **before** its Binder call. `getAll()` and
  repeated `getRemotePreferences()` calls read that cache. The standard path
  therefore reports commit acknowledgement plus complete client-cache validation;
  it does not report an independent framework-database read-back. There is no
  public refresh method in this SDK. NPatch keeps its existing direct protocol
  read-back, and pending consent retains the existing completion order on each path.
- The host still validates the whole Remote document once in attach and consumes
  an immutable snapshot. An unauthorized running process does not acquire hooks
  when settings are later published; restart it. The existing authenticated host
  diagnostic receipt independently reports which generation it consumed.
- Host RecyclerView hooks and type checks use the host ClassLoader. Boot classes
  remain ordinary Android references; module-owned UI classes remain module-owned.
  No per-view class lookup or global class-loading hook is added.
- Recognized Vector and LSPosed services use their own standalone manager and
  parasitic category. Unidentified frameworks may expose installed standalone
  managers, but do not cause a guessed shell redirect. Enablement, export status
  and component permission are checked before displaying a launch action.
- Diagnostic report format 4 adds framework build, capability bits, connection
  generation, bounded metadata errors, submission evidence and host generation
  comparison. These fields do not change authorization or feature health. No
  setting values, host member names, UID lists or exception messages are exported.

## Framework and platform limits

Remote Preferences are keyed by module package, Android user and group in
[Vector's PreferenceStore](https://github.com/JingMatrix/Vector/blob/v2.2/daemon/src/main/kotlin/org/matrix/vector/daemon/data/PreferenceStore.kt).
Install the module and official Bilibili package in the same user and complete
consent there. A missing binder can mean module enablement, user routing, framework
delivery failure or a process lifecycle problem; it does not identify one cause.
Do not copy user 0 private configuration to another user.

`system_server` is a separate device-wide target. Its existing narrow roaming
hooks use `onSystemServerStarting`, not the Bilibili attach authorization path.
Verify that chain independently; do not introduce per-profile settings there
without an explicit ownership policy.

Stable 3080 predates the [provider-reference and service-delivery fix](https://github.com/JingMatrix/Vector/commit/e8bec6bd714d0f277c8134096501ce36902b32fd)
and the [delivery ownership race fix](https://github.com/JingMatrix/Vector/commit/ac0d4da7ac0dd4fe9ae115832d779b0ca7441860).
The first also fixes app-side service delivery on Android 8.1 and 9. Build 3110
contains both fixes. The UI identifies the exact known 3080 baseline; it does not
guess that every older or newer build has the same behavior.

Android 8.1 remains unverified: both inspected Vector baselines gate modern
`onPackageReady` dispatch behind SDK 28, while this module needs that callback.
Do not infer module support from Vector's overall Android support range. Keep
module minSdk unchanged for other frameworks and resolve this upstream lifecycle
gap before claiming Vector/API 27 support.

Hot reload is intentionally unsupported. Its API does not replay package
callbacks, and the module owns handlers, receivers, listeners, adaptation state
and native DexKit state that require an explicit retirement design. It is not a
configuration synchronization mechanism.

## Acceptance matrix

Record framework version/build/hash, Android/ROM, module APK hash, host version,
ABI, Android user, API protection setting, and observed evidence for each run.

| Test | Required observation | Device status |
| --- | --- | --- |
| 3080 and 3110 on the same supported test device | Distinguish known upstream delivery behavior from module regressions | Pending |
| Existing LSPosed API 102 and NPatch | Existing activation, consent, configuration and feature behavior preserved | Pending |
| First launch without config, then accept/publish/restart host | No unauthorized hooks; restarted host consumes a complete generation | Pending |
| Commit failure then retry; accept/decline/reconnect races | No cached false success; last decision wins | JVM coverage; device pending |
| Rapid settings changes during host cold start | Valid whole snapshot or bounded fail-closed reason; no attach polling | Pending |
| Bilibili main and existing secondary processes | Correct lifecycle and consistent generation after publication settles | Pending |
| Comment scroll, detach, long press and nested lists | Actual host RecyclerView callbacks; no stale selection or binding/frame regression | Pending |
| Vector API protection on/off | Same public-API functionality without framework-name reflection | Pending |
| Owner and one profile; module installed on only one side | Correct service/config isolation and honest unavailable state | Pending |
| Standalone/parasitic/permission-denied manager | Working action or hidden action with usable guidance | Pending |
| Debug and fixed-signed Release; arm64 and required armv7 | Modern metadata, valid ZIP entries, native loading, independent DexKit fallback result | Pending |
| MIUI roaming and other modules; module upgrade/restart | Separate system and host evidence, no duplicate callbacks | Pending |

The module packages arm64-v8a and armeabi-v7a native libraries. A stock x86
emulator cannot certify its DexKit path. Use matching hardware or an appropriate
ARM environment. Do not replace the user's installed framework to run this matrix
without an agreed device migration. Local gates remain
`assembleDebug testDebugUnitTest lintDebug minifyReleaseWithR8 --console=plain --no-daemon`.

JVM coverage exercises SDK-like cache-before-IPC failures, connection changes,
host-loader identity, manager selection/permission policy, Hook argument/result/
exception/constructor behavior and independent host-receipt comparisons. It does
not run Vector's ART engine or certify its protective fallback and ID replacement
implementation; those remain integration checks.

## Local verification record (2026-09-05)

- `assembleDebug testDebugUnitTest lintDebug minifyReleaseWithR8` completed
  successfully in 5m 56s with `--no-daemon`.
- JVM: 117 suites, 644 tests, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 170 warnings. This is not a zero-warning claim.
- Debug APK: 12,864,255 bytes; SHA-256
  `D2D0D7EF81B970DE82ABF301E4CEB5622F8ADF0EB207485CDFA2FD4559244E0D`.
  Its timestamp is later than the production source/configuration inputs.
- APK inspection: Modern entry metadata present, Legacy entry absent, 19 DEX
  files, 0 bundled libxposed API class definitions, 0 absolute or parent-traversal
  ZIP entries. Debug signature verification succeeded; it is a debuggable build,
  not a fixed-signed release artifact. Release validation here is the R8 task.
- ADB device inventory was empty. No APK was installed and no device framework
  was replaced. The device matrix above remains pending.
