# LSPosed-Irena compatibility

## Inspected baseline

The module-side compatibility baseline is [Irena 2.0.0 / 7316](https://github.com/re-zero001/LSPosed-Irena/releases/tag/2.0.0),
commit `cd1a1d96ab5e4bd33597888ade59eed06bf1872a` (2026-05-14).
The inspected default `dev` branch points to the same commit. This is a source
and ABI baseline, not an Android device acceptance claim.

| Boundary | Irena implementation | Module adaptation |
| --- | --- | --- |
| Module API | `edeb8379c067b16b91af3cb526f5f04db25c06b6`, reports 101 | Minimum Modern API 101, target 102 |
| Service API | `11f8945de4e24efc0eb0e2e87a2dd8284d8f7b66`, reports 101 | Existing Service 102 client uses the shared public calls |
| Hook registration | Interceptor chain and protective mode; no `HookBuilder.setId` | Local logical-point replacement on 101; native IDs retained on 102+ |
| Remote preferences | Processes explicit `delete` and `put`, ignores `clear` | Explicit obsolete-key deletion plus a complete document in one commit |
| Manager identity | Reports `LSPosed`; package `org.lsposed.manager` | Preserve reported identity and existing LSPosed routing; accept Irena name aliases |
| Parasitic entry | `org.lsposed.manager.LAUNCH_MANAGER` on shell host | Existing export/enablement/permission checks still apply |

The pinned module API is visible in [Irena's API gitlink](https://github.com/re-zero001/LSPosed-Irena/tree/cd1a1d96ab5e4bd33597888ade59eed06bf1872a/libxposed/api/api),
and its Service revision in [the Service gitlink](https://github.com/re-zero001/LSPosed-Irena/tree/cd1a1d96ab5e4bd33597888ade59eed06bf1872a/libxposed/service/service).
The module loader [rejects a minimum API above its own version](https://github.com/re-zero001/LSPosed-Irena/blob/cd1a1d96ab5e4bd33597888ade59eed06bf1872a/daemon/src/main/java/org/lsposed/lspd/service/ConfigFileManager.java),
so leaving `minApiVersion=102` would prevent this module from loading at all.
`targetApiVersion=102`, static scope, protective mode and disabled hot reload remain.
API 100 and Legacy modules are not enabled by this change. Other implementations
that report 101 still need their own lifecycle/ABI validation; a version number
alone is not a device certification.

## Hook and lifecycle behavior

Irena instantiates a no-argument `XposedModule`, calls `attachFramework`, then
`onModuleLoaded`. Its package loader dispatches `onPackageReady`, and system-server
startup has its own callback. The module keeps its existing entry and final host
ClassLoader, synchronously validates configuration in attach, and fails closed
on invalid or unavailable state. It does not add a Legacy or private-file fallback.

`ModernHookRuntime` queries the API lazily: HookEntry constructs it before the
framework is attached. On 101, one native registration is retained per
`(Executable, logical id)` within this runtime. Replacement atomically changes
its callback reference; an invocation already in progress completes its original
before/after pair. Different logical IDs and executables remain independent.
Failed native registration does not claim the point. These are internal module
registration semantics, not a backport of public framework IDs or hot reload.

On 102+, the existing native ID path is retained. `ModernHookIdsApi102` isolates
the newer `setId` reference, and its R8 rule prevents optimization or class merging
from moving that call into the API 101 path. Framework objects are accessed through
the public API; there is no reflective framework lookup in production code.

Host AndroidX classes still use the host ClassLoader. Hook callbacks gain no IPC
or class lookup; the 101 dispatcher adds one atomic reference read. Cached slots
retain executable/callback metadata only, with the same process lifetime as the
existing Hook handles. No new production dependency is introduced.

## Configuration delivery

Both inspected Service AIDL versions assign preference read/update/delete IDs
20/21/22 (Binder transaction codes 21/22/23). The descriptor, arguments and
`map`/`delete`/`put` payloads used by this module are compatible. The 102-only
running-target and hot-reload calls are not used. See [Irena's service implementation](https://github.com/re-zero001/LSPosed-Irena/blob/cd1a1d96ab5e4bd33597888ade59eed06bf1872a/daemon/src/main/java/org/lsposed/lspd/service/LSPModuleService.java).

The publisher no longer relies on `clear`: it deletes only obsolete keys from its
dedicated `hook_config` group and puts every current allowlisted value and metadata
field in the same commit. The Service 102 client includes explicit puts even when
their values are unchanged, so retries still issue IPC. This also avoids Vector's
separate clear-before-update step for ordinary publication.

Pending obsolete-key removals survive a failed commit. Otherwise the SDK could
already have removed the keys locally while Irena still retained them remotely,
causing the retry to forget its cleanup. Removals are cleared only after successful
submission and cache validation, or when moving to a new service connection whose
SDK instance reads its own group. They never target module-private preferences or
another group. The existing generation/digest acknowledgement and identical-document
generation retention remain in force.

The wire schema, catalog, consent state machine and NPatch gateway are unchanged.
Commit acknowledgement, client-cache validation, host receipt and feature execution
remain separate evidence. Neither API version turns a local cache read into an
independent remote database read-back.

Irena keys Remote Preferences and update subscriptions by Android user and group.
Complete setup in the user's own module installation. Service delivery may be
asynchronous or fail; pending consent is retained without polling from Hook callbacks.
The reported name `LSPosed` and manager package do not uniquely identify Irena, so
the module does not relabel an unrelated fork using only a name/build heuristic.

## Verification and remaining acceptance

JVM coverage includes 101 capability gating, metadata admission, manager aliases,
registration replacement/in-flight behavior/failure retry, and explicit obsolete-key
cleanup including a cache-before-IPC failure. All existing 102 tests remain enabled.

`tools/compat/IrenaApi101Smoke.java` runs the actual compiled module runtime against
the seven public Java API sources from Irena's exact API commit. Compile those
sources into an isolated directory using an Android SDK jar and AndroidX annotations;
compile/run the smoke program with that directory, the module compile jar, Kotlin
stdlib and the SDK jar. Do **not** include the API 102 AAR on that runtime classpath.
The program checks that the real loaded interface reports 101 and has no `setId`,
then verifies before/after behavior, in-flight replacement and one native registration.
JVM class-loading logs additionally confirm the 102 bridge class was not loaded.
This tests Java linkage and adapter behavior, not the Irena ART/native engine.

Device acceptance must cover Irena 7316 Debug and Release, the existing 102
frameworks, and NPatch separately. Record the framework artifact/build, module APK
hash, host version, Android/ROM, ABI, user and API protection setting. Verify module
discovery, service delivery, consent transitions, host generation, comment scrolling,
home/player features, upgrade/restart, profile isolation and the separate system-server
roaming chain. Irena's README advertises Android 8.1-16; this does not establish
Android 17 support. The Vector/API 27 callback warning is not reused for Irena:
Irena dispatches package-ready outside its SDK-28 AppComponentFactory guard.

## Local verification record (2026-09-05)

- Final `assembleDebug testDebugUnitTest lintDebug minifyReleaseWithR8` passed in
  5m 2s with `--no-daemon`. JVM: 118 suites, 655 tests, no failures/errors/skips.
  Lint: 0 errors and 170 warnings. The initial XposedNewApi finding was resolved
  with an explicit API check inside the isolated bridge, not a lint suppression.
- The binary smoke test was rerun against the final module jar and the actual
  pinned API 101 classes: PASS, no setId member, one native registration, and no
  API 102 bridge class loaded. The R8 mapping retains the isolated bridge.
- Debug APK: 12,864,359 bytes, SHA-256
  `63970221475B71ABA0CA5C04149A45637D768457703D7CBB7D193B7DCB82066C`.
  It is newer than the production inputs; Modern metadata is 101/102 with fixed
  scope and hot reload off. All 19 DEX files were checked: no bundled framework
  API definitions, no Legacy entry, no absolute/parent-traversal ZIP entries.
  Signature verification passed with Android Debug signing. This is a debuggable
  artifact; Release verification here is R8, not a fixed-signed Release package.
- The connected device was inspected read-only: Android 13/API 33, LSPosed
  2.1.0/7769, module 1.1.3/14. It is not the inspected Irena baseline. No new APK
  was installed, and no framework was switched or rebooted. Irena ART/device
  acceptance and new-build device regression remain pending.
