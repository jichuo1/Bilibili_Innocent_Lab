# Regression verification

## Host APK intake and isolated Android contracts (2026-09-05)

`HostApkCompatibilityInstrumentedTest` loads an explicitly supplied host APK with a
separate `DexClassLoader`, then runs the production `VersionAdapter.quickLocate`.
It does not install Hooks, open Bilibili, or write module preferences. The host APK
must first pass signature/identity checks and be staged read-only directly under
the module's private `cache/host-compat/` directory. Do not replace the user's
installed host merely to run this test. Use the existing same-signer module/test
APK installation procedure below; do not uninstall the module or clear its data.

Select one method using the instrumentation `class` argument:

- `com.Bilibili_Innocent_Lab.xposedmodule.hook.HostApkCompatibilityInstrumentedTest#verifyHostApkContracts`
  validates the current host contracts, cache round-trip, exact member resolution,
  interactive-overlay carriers/default-instance guards, AccountMine data path, and
  the real PGC descriptor/model before and after half-screen filtering.
- `com.Bilibili_Innocent_Lab.xposedmodule.hook.HostApkCompatibilityInstrumentedTest#verifyVersionAnchors`
  checks update/default-quality owner selection on older APKs, including unrelated
  classes that share the newest obfuscated names. It is not a full old-host audit.

Both require `hostApk`, `hostSha256`, `hostVersionCode`, `updateOwner`, and
`qualityOwner` instrumentation arguments; without `hostApk` they skip. Reports are
written to `cache/host-compat/<versionCode>-contracts.json` or `-anchors.json`.
Check the report's `evidence` scope and `passed`, and require `OK (1 test)` from
the runner: the ADB command's exit code alone does not establish test success.

The 9.11.0 (9110200) and 9.10.0 (9100200) runs each resolved 238 members and passed
the PGC model filtering check. Their legacy `comment.low` diagnostic remains
missing; the verified modern Handler supplies the independent high path, as in
`HookEntry`. Raw diagnostics remain in the reports. The two DexKit diagnostics are
not applicable during quick location. The 8.84.0 and 8.90.2 anchor-only runs passed.
An offline scan of 27 local APKs rejected all older unrelated same-name classes
using the production signature constraints. This is not 27-host full validation.

Debug, all JVM tests (121 suites / 677 tests), Lint (0 errors / 170 warnings),
Release R8, and the Android test APK build passed. The module was updated on the
Android 16 device and its installed SHA-256 matched the local Debug APK. The user
chose to keep Bilibili 8.90.2 installed: 9.11.0 evidence covers APK contracts and
isolated model behavior, not LSPosed registration or live page/server responses.

## Settings purpose and region layout (2026-09-05)

`AdvancedCategoryLayoutPolicyTest` verifies that every non-heading child is assigned
exactly once across different category counts and that the last row before a heading
is preserved. Static reordering checks compare preference references and all 62
actual switch callback bodies, excluding indentation.

`SettingsOrganizationInstrumentedTest` exercises primary-card ownership, last-child
submenu placement, independent expansion, rapid toggling and search revelation across
both purposes. It compares all 86 catalog settings before and after menu/search
operations and never toggles feature switches or accepts terms. It requires an already
authorized module installation. Screen-on is a temporary test-window flag only.
Do not use an automatic uninstall/reinstall workflow on a user's existing module;
verify the signer, use `adb install -r`, and install/run the test APK separately.

The Android 16 device run passed both cases, and four Chinese dark-mode screenshots
were reviewed. JVM tests passed 121 suites / 675 tests; Lint had 0 errors / 170 warnings,
and Debug/R8 gates passed. Installation and UI behavior are verified here; this does
not establish new host Hook, skin, or framework compatibility evidence.

## Build integrity

1. Run `gradlew.bat assembleDebug --console=plain --no-daemon` from the Gradle
   project directory.
   On Windows, gradlew.bat derives an 8.3 short path from the current user's
   writable TEMP directory and starts the client with the same heap/encoding
   requirements as the build JVM. With --no-daemon this avoids creating a
   single-use process and its AF_UNIX selector pipe. JavaCompile tasks also use
   the current JDK's command-line javac on Windows instead of a Gradle Worker;
   Linux CI keeps Gradle's default compiler strategy. Never put a Windows
   absolute temp path in the shared gradle.properties, because CI runs on Ubuntu.
2. Confirm `app/build/outputs/apk/debug/app-debug.apk` is newer than modified
   sources.
3. Before device testing, compare its checksum with the installed `base.apk`.

Release packaging is a separate boundary. `assembleRelease` must fail when the
four `INNOCENT_LAB_SIGNING_*` values are absent or incomplete. For an authorized
publication, run `apksigner verify --verbose --print-certs` and require exactly
one signer, the pinned certificate SHA-256, no `application-debuggable` marker,
and the expected package/version identity. `BUILD_INFO.txt` must report
`apk_build_type=release`, `apk_debuggable=false`, and the same signer certificate
digest. Alpha and Stable must use the same digest; a changing APK file SHA-256 is
expected and does not indicate signer drift.

## Unit tests

Run gradlew.bat testDebugUnitTest --no-daemon. The tests cover multi-user
cache-path derivation, bounded merged-output command execution, rich comment
text/emoji mapping, KavaRef lookup, Stable/Preview Release parsing and
update-channel request serialization. Localization tests additionally require
the English, Simplified Chinese, and Traditional Chinese resources to have the
same keys and format placeholders, verify the explicit locale config, and
exercise the injected-UI locale tag normalization and immutable text snapshots.
Settings-backup tests lock the v1 catalog ids, automatic/manual boundary,
legacy-value normalization, deterministic JSON round trips, strict UTF-8 and
JSON structure, file-size and integrity failures, permanent v1 decoder
dispatch, value-version ordering, explicit/default intent, future and removed
records, partial scopes, and catalog migrations. Motion-spec tests additionally
lock the source-card/full-window endpoints, rounded-surface takeover, staged
content reveal, title trajectory, progress clamping, and invalid-geometry
rejection without requiring Android UI stubs.
User-terms tests lock the positive terms revision, valid four-state parsing,
missing-state versus corrupt-state distinction, version-mismatch fail-closed
behavior, upgraded-install and legacy-sentinel migration, and the exact
fixed-rollout-cutoff boundaries and authorization snapshot
(`ACCEPTED/LEGACY_EXEMPT` only). They also lock valid pending-accept metadata,
corrupt/stale pending rejection, non-authorizing pending behavior, sync-status
priority and last-request-wins publisher repetition. Localization tests require every released
locale to provide the complete non-empty terms UI and body, preserve paragraph
structure and the canonical project URL, and keep the maintainer-supplied
Simplified Chinese body and both decision-button labels byte-for-byte equivalent
after Android newline decoding.

The API 102 host-configuration tests lock the catalog allowlist, the two runtime
revision fields, typed defaults and normalization, all four terms decisions,
schema/catalog/generation validation, exact key sets and digest tampering. Run
one fixed-policy suite:

```powershell
.\gradlew.bat testDebugUnitTest --console=plain --no-daemon
```

The packaged APK must contain `META-INF/xposed/java_init.list`, `module.prop`
with `minApiVersion=101`/`targetApiVersion=102`, and `scope.list`. It must not
contain `assets/xposed_init`, `META-INF/yukihookapi_init`, an
`xposedminversion` manifest entry, YukiHookAPI classes, or rovo89 API classes.
JVM protocol success does not prove framework service binding or device Hook
execution; those remain device checks.

The DEX-assist tests lock the adaptation cache's atomic replacement (a valid
payload replaces the cache, a rejected one leaves the previous cache intact),
the candidate selector's bridge-versus-leaf disambiguation and its fail-closed
behavior when several owners match, and the DEX content fingerprint: stable
across archives whose resources differ, changed by a modified secondary DEX,
and rejecting an archive with no DEX. The selector carries two distinct
selection semantics that must not be swapped: block-update needs the unique
leaf, while the reply-topology mapper needs the whole group declared by a single
owner, so its tests also lock ordered single-owner groups, cross-owner
fail-closed behavior and the empty candidate set. They do not prove native
library loading, DexKit query behavior, or the cache audit's runtime effect;
those are device checks.

Reply-topology locator tests pin the host-side stubs against obfuscated owner
drift. The `ReplyInfo -> CommentItem` mapper lives on a Kotlin file facade whose
obfuscated class name moves between single letters across host releases, so the
candidate list is generated from the stable package plus a bounded alphabet and
every decision is made by the structural filter. One test asserts the newest
facade name is reachable and that a same-owner overload whose first parameter is
not `ReplyInfo` is excluded; another hides that facade through a filtering class
loader and asserts the older name still resolves. These tests need the protobuf
and moss stubs (`DetailListReq`, `DetailListReply`, `ReplyMoss`,
`FeedPagination`, `FeedPaginationReply`, `ParentReplyMember`, `CommentItem`) to
keep the exact member shapes the locator requires; weakening a stub silently
weakens the guarantee. They do not prove that the panel renders or that host
paging succeeds, which stay device checks.

Two suites lock the primitive-versus-boxed reflection boundary, which the
KavaRef `ReplaceWithKavaRefExtension` lint suggestions do not distinguish on
their own. `ReflectAccessPrimitiveArgTest` resolves methods whose parameters are
`boolean`, `int`, `long`, `double` and `char`, and additionally pins the two
facts that make those suggestions unsafe to apply blindly: `classOf<T>()`
resolves to the primitive type while `classOf<T>(primitiveType = false)`
resolves to the boxed one, and Kotlin's `X::class.java` is identical to
`X::class.javaPrimitiveType`. A primitive `Class` returns false from
`isInstance` for every argument, so substituting one for the other silently
disables parameter matching rather than failing loudly.
`MineComponentHiddenFlagWriterTest` locks the hidden-flag writer against both
field representations: it first asserts that its own fixture really declares
primitive and boxed fields — otherwise the remaining assertions prove nothing —
then writes and reads every value reflectively, because reading a wrapper field
through Kotlin source maps it back to the primitive type and masks the
difference. Unsupported field types must be left untouched rather than guessed
at.

Diagnostics tests lock the pure severity/evidence matrix and the local report's
version, size limit, privacy exclusions, and read-back schema. The transition test
also locks the dedicated entry/full-window endpoints, staged content reveal, title
trajectory, and the wider predictive-back content profile. A passing JVM test does
not prove LSPosed binding, an NPatch heartbeat, host adaptation, SAF provider behavior,
Liquid rendering, RenderThread timing, or device accessibility.

### Telemetry service and client (2026-09-07)

Server validation consists of TypeScript checking plus 21 Node tests for strict
schema handling, unknown/forbidden fields, byte limits, encoding rejection, hashing,
idempotency, rate limiting, purge and retention aggregation. Staging accepts the
synthetic schema-v1 fixture with HTTP 204 and token-only purge returns 204. Both D1
databases were queried after cleanup and contained zero raw and aggregate rows.
Production was initially retired with HTTP 410. After the maintainer explicitly
authorized the stated telemetry fields to be processed by overseas Cloudflare
Workers/D1, ingestion was enabled in deployment
`7b8777e0-38f1-4ab8-b327-77b728656332`. Two synthetic submissions returned 204 and
produced one row; hash lengths were 64 and the canonical payload contained neither
the raw installation ID nor the deletion token. Purge returned 204, and a subsequent
query found zero raw and aggregate rows. Android and carrier acceptance remain pending.

Android JVM coverage pins terms version 2, old-version re-consent, default-selected
but separately recorded telemetry choice, fail-closed consent composition, endpoint
selection, the 24-hour network-attempt window, identity rotation, HTTP outcome
classification, settings-backup/Remote-config exclusion, bounded payload keys and
filtering of disabled/not-reported features. Locale tests lock all English,
Simplified Chinese and Traditional Chinese disclosure keys and the maintainer text.

Static gates for the implementation: 139 suites / 796 tests, Lint 0 errors / 172
warnings, Debug APK, Release R8 and Debug AndroidTest APK. These checks do not prove
the dynamic GitHub-dialog geometry, terms toggle visibility on small screens,
framework receipt delivery, real Android HTTPS behavior, carrier reachability or
that a Release payload reaches Production.

## Device checks

1. In Bilibili's main process, toggle roaming compatibility on and off while
   the app is running. The module App broadcast must update the local cache.
2. Send the same action from an unrelated package. Android must reject it
   because the sender lacks
   `com.Bilibili_Innocent_Lab.xposedmodule.permission.SET_ROAMING_COMPAT`.
3. Test cold start, `web`, `download`, and `ijkservice` processes with the
   module process both alive and stopped.
4. Test Bilibili 8.90.2, 9.0.0, and the 9.1.0-9.10.0 major-version paths where
   available, including a work profile if one exists.
5. Confirm the injected roaming-settings entry still opens on MIUI and that
   ordinary free-copy, banner, game-card, merchandise, and pause-ad hooks have
   no regression.
6. While a Stable check is still running, switch to Preview. The Stable result
   must be suppressed and the queued Preview check must start immediately
   afterward; repeat in the opposite direction.
7. Query the compatibility provider from Bilibili and ADB shell, then verify an
   unrelated application uid receives SecurityException.
8. Switch the module UI through system, English, Simplified Chinese, and
   Traditional Chinese. Confirm each selection survives Activity recreation,
   force-stop, and cold start; on Android 13+ also change it from the system's
   per-app language page.
9. With Bilibili already running, switch the module language and verify the
   reply-context entry/panel, adaptation toast, roaming-settings title, and
   blocked-update message use the new language without a provider query on a
   comment binding path.
10. Force-stop both apps, then start Bilibili first. Verify the provider
    cold-start refresh restores the explicit module language. With `system`
    selected, set a different Bilibili per-app language and confirm injected UI
    still follows the device system locale.
11. Send the UI-locale action from an unrelated package. Android must reject it
    because the sender lacks
    `com.Bilibili_Innocent_Lab.xposedmodule.permission.SET_UI_LOCALE`.
12. Export settings through at least the system Documents provider and one
    third-party provider. Confirm the page reports success only after reopening
    and decoding the written file, and that no storage permission is requested.
13. Change several Boolean, QN, comment-level, rule-text, and logging settings,
    read the backup, and verify the preview separates writes, unchanged/current
    defaults, new current settings, and attention items before confirmation.
14. Confirm roaming compatibility shows its backup and current values but is
    never changed by import. Confirm language, update channel, launcher icon,
    revisions, sentinels, and caches are absent from the file.
15. Rotate the device while a confirmed import is running and again on each
    result/error page. The transaction must continue once, and the recreated
    page must preserve verified versus possibly-changed outcome semantics.
16. Import a truncated file, invalid UTF-8, valid JSON followed by trailing
    bytes, wrong-product backup, modified-value backup, and future format. Each
    must fail before a settings write and show the corresponding error class.
17. Import a plan that changes either free-copy switch. Verify the Yuki prefs,
    revision, and `FreeCopyConfigStore` snapshot agree. Interrupt the module
    process after preference commit where practical, reopen it, and verify the
    journal completes the same target state idempotently.
18. On Android 14 and 15, open settings backup from several main-page scroll
    positions. Verify the rounded card expands to full screen, its title moves
    into the toolbar, slow left/right-edge back gestures continuously reverse
    the same geometry, page content fades over multiple progress samples instead
    of disappearing on the first sample, cancellation returns to full screen,
    and commit finishes at the source card before MainActivity handles the result.
    Immediately starting another back action during entry, cancellation rebound,
    or commit completion must be consumed without changing content alpha.
19. Repeat backup-page closing with Android 13 or lower, three-button/hardware
    back, the module predictive-back switch disabled, animator scale 0/0.5/2,
    large font, each supported locale, after SAF returns, and after rotation.
    PREVIEW and ordinary ERROR must first return HOME; WORKING/picker states
    must remain blocked; stale geometry must use the neutral fade/scale fallback
    without jumping to an old screen position. A back action during any timed
    morph must be consumed, then work normally after the surface is stable.
20. On a fresh install or after clearing module data, open settings from both
    the launcher alias and LSPosed. Confirm the terms gate appears before the
    settings hierarchy or update check, the body scrolls, links remain usable,
    outside touches cannot dismiss it, and Back exits without recording accept
    or decline.
21. Decline the terms, reopen settings, and confirm only the locked page is
    available. Use “review terms,” accept, and confirm the Activity rebuilds
    once into the normal settings UI. Force-stop and cold-start the module to
    verify both declined and accepted states persist as selected.
22. Before accepting on a fresh install, start Bilibili and confirm no feature
    hooks are installed. Query `/hook_authorization` and the other compatibility
    provider routes: authorization must be false and all derived feature values
    must be safe disabled/default snapshots. Send the explicit ordered
    authorization broadcast and confirm it returns handled=true/authorized=false
    without accepting any state input. Accepting must not modify that
    already-running process; after restarting Bilibili, authorization must be
    true and the normal feature installation chain must resume.
    On Android 14+, an unrelated sender's explicit authorization request must
    remain unhandled. On Android 13 and lower, confirm such a read-only request
    cannot alter the stored decision or trigger either settings Activity.
23. Upgrade an installation whose first-install time predates the fixed terms
    rollout cutoff and separately test a positive `prefs_alive_ts` that also
    predates it; each eligible missing-state case must persist `LEGACY_EXEMPT`
    without prompting. An install exactly at or after the cutoff, a marker at or
    after the cutoff, missing/invalid timestamps, and a post-rollout install that
    is later upgraded must remain undecided. Corrupt decision text and a
    mismatched terms version must become undecided even when old evidence exists.
    Make the private preference read and first migration commit fail where
    practical; both failures must remain unauthorized rather than falling into
    legacy inference.
24. On each supported Modern framework (including the pinned Irena API 101
    baseline), open the module UI and confirm the service status reports connected,
    its actual API version and Remote Preferences capability.
    If the service is unavailable, the host must fail closed and install no
    feature Hook; it must not fall back to the compatibility Provider or an
    ordered broadcast authorization race. Click Accept once while disconnected:
    the UI must retain a non-authorizing pending state, avoid asking for a second
    acceptance, and automatically converge to `ACCEPTED` only after service bind
    and acknowledged commit plus complete client-cache validation. Verify a
    failed commit cannot become a cached success on retry. Without the standard framework service, use the explicit
    NPatch action and confirm it follows the same pending-to-read-back-to-local
    completion order. Repeat in the owner user and one cloned-app/profile user.
25. Repeat the gate, pending-sync page, declined page, save-failure path, and accepted settings page
    in English, Simplified Chinese, and Traditional Chinese with dark mode,
    large font, rotation, and TalkBack. Confirm all text and buttons remain
    reachable and Activity recreation never creates two terms dialogs. On the
    undecided and pending gates, verify the read-only diagnostics show the
    module userId/UID, framework name/API/Remote capability, possible profile
    classification, current-user visibility and identity of `tv.danmaku.bili`,
    same-user result, and the expected bounded failure code. The target must be
    unavailable rather than guessed when it is absent from that user, and a
    non-primary user must be labelled only as a possible clone/work profile.
26. Install the API 102 build over the existing installation and open the module
    once. Confirm the private UI settings remain unchanged and the framework's
    `hook_config` Remote Preferences group contains exactly the catalog values,
    two documented runtime fields and ten metadata fields, with no arbitrary
    default-preference key. Repeat against NPatch Remote storage and confirm the
    group name and exact document are identical apart from delivery/revision and
    generation values.
27. Restart Bilibili main, web, download and player processes. Every process must
    log `Modern Remote Preferences 验证成功` with the same generation and must not
    log an API 82 file or authorization-mirror fallback. Verify free copy, then enable
    reply topology and sample at least one home, player and comment feature.
28. Change representative Boolean, integer and text settings in one session,
    immediately restart Bilibili, and confirm one complete newer generation is
    consumed. Verify free-copy revision and manual adapter-reset timestamp reach
    the host without a private-file or Provider fallback.
29. In a Debug-only fault setup, remove a key, change a type, alter schema/catalog,
    set generation to zero and corrupt the digest.
    Each case must log an exact reason and install no feature Hook. Test ACCEPTED,
    DECLINED, UNDECIDED and LEGACY_EXEMPT separately; decline must publish denial
    before the private decision, and acceptance publication failure must leave
    the private decision non-authorizing with its pending request retained.
30. Open the diagnostics center from the activation card in Material You and Liquid
    modes, in all supported locales, with large font, TalkBack, rotation, and Android
    predictive back. Verify only the dedicated diagnostics row is clickable; its rounded
    bounds expand continuously to the full window and its title moves into the toolbar.
    Drag predictive back slowly, cancel once, then commit once: the same geometry must
    follow every progress sample, cancellation must return to a fully interactive page,
    and commit must finish at the live entry without a framework transition flash.
    During entry/cancel/close, diagnostics result delivery must not rebuild the list and
    Liquid overscroll must not remain attached; rapid repeated Back must be consumed.
    Verify manual refresh converges after the framework service binds;
    a current-version NPatch heartbeat is observed, an old-version heartbeat is not;
    Remote Preferences publication and host adaptation remain separate rows; and the
    adaptation row stays unknown rather than claiming success. Export through the
    system Documents provider and one third-party provider, then confirm success appears
    only after byte-for-byte read-back and parsing. Inspect the JSON to confirm it has no
    setting values, custom rules, paths, logs, exception detail, or host member names.
31. With NPatch support off, confirm opening/resuming the module does not call
    `top.nkbe.npatch.remote`. Turn it on and verify the Provider call is
    `getRemoteService` with this module's package in `modulePackageName`, the
    returned Binder descriptor is API 102 `IXposedService`, transaction 21 reads
    and transaction 22 updates `hook_config`, and the UI reports success only
    after a complete read-back. Make the store read-only, return a wrong
    descriptor, reject the module identity, delay beyond the deadline, and alter
    one read-back field; every case must remain fail-closed with a bounded error.
32. Test a patched host with an NPatch build explicitly documented as fixing
    upstream issue #139. Separately reproduce an affected build: if native JNI
    loading crashes before `HookEntry.onModuleLoaded`, classify it as an upstream
    pre-entry failure rather than a module configuration failure. The module
    switch must not claim to disable or recover native injection, and no module
    Hook/heartbeat may be reported as observed in that case.
33. Force a re-adaptation and confirm the `dex.assist` diagnostic appears
    exactly once. On every currently supported host, `locateBlockUpdate` still
    resolves directly, so the expected detail is
    `block-update:not-required`. Any other detail means the fallback ran and
    its outcome must be read before shipping.
34. Confirm DexKit's native core actually loads inside the host process. The
    `.so` files are stored uncompressed, so this depends on the framework
    adding the module APK's `lib/<abi>` to the module ClassLoader's library
    search path. Verify under LSPosed and separately under NPatch; a failure
    must surface as `block-update:native_unavailable` and must not crash the
    host or disable any other feature. The module ships arm64-v8a and
    armeabi-v7a only, so an x86 emulator is expected to report
    `native_unavailable`.
35. Measure peak native RSS in the Bilibili process while a forced
    re-adaptation runs the DEX query. Host 9.10.0 carries 31 DEX files and
    roughly 291,072 classes, and `MAX_DEX_ENTRIES` bounds fingerprinting only,
    not the query itself.
36. Exercise the cache audit by changing DEX content without changing
    `versionCode` — re-patching the host through NPatch is the realistic case.
    The `BIL-DexAudit` thread must invalidate the cache, the current process
    must keep running on its already-installed hooks, and the next launch must
    re-locate. Make the fingerprint unreadable in a separate run and confirm
    the cache survives.
37. Click a portrait (Story) video from the home feed and read the module log.
    A rewrite logs `Story 视频已在 Activity 启动边界`; a pass-through logs
    `home_vertical_skip_<reason>` exactly once per reason per process, carrying
    the desensitized intent shape. Confirm the reason is one of the bounded
    `HomeVerticalLaunchSkip` values and never a silent absence of both lines.
38. From that skip line's `queryKeys` / `extraKeys`, determine where the host
    actually carries `player_preload` and whether a numeric `cid` is present at
    all. This is the prerequisite for any further change to cid resolution —
    the current multi-source order is a superset built without a live capture,
    not a verified contract. If no cid reaches the boundary in any form, the
    fix is a read-only feed side-channel that records `aid -> cid`, never a
    second route write point.
39. Repeat on a `story_translucent` entry and on an entry whose URI has no path
    token. Both are host-registered routes that previously passed through
    untouched; they must now either rewrite or report a bounded reason.
40. Open the diagnostics center and confirm `home_vertical_detail` now shows an
    evidence line. Installed-but-never-applied must be visible there; the flag
    controls display only and must never mark the feature failed when the user
    simply has not opened a portrait video.
41. After any KavaRef extension migration, confirm `lintDebug` reports zero
    `ReplaceWithKavaRefExtension` issues and that the JVM suite still passes.
    The migration is meant to be a pure syntactic substitution: a diff that
    changes which classes a `when` or a type comparison matches is a behaviour
    change and must be evaluated separately, not folded into the migration.
42. Hide a "mine" page component on a host whose `visible` / `localShow` field
    is declared as a boxed `java.lang.Boolean` or `java.lang.Integer` rather
    than a primitive. The component must actually disappear. Host 9.9.0
    declares these fields as primitives and therefore exercises only the
    pre-existing branch, so this path has JVM coverage but no device evidence
    yet; a host or field shape that uses the boxed form is required to confirm
    it. Before the fix, the boxed form matched no branch at all and the hide
    was silently skipped while the component stayed visible.

## targetSdk 37 (Android 17 behavior set)

The module targets API 37 since 2026-09-03. The module App (settings UI,
backup, diagnostics, receivers) runs standalone without LSPosed, so its
behavior changes can be validated on an Android 17 x86_64 emulator; the
emulator must not be used to claim host-hook verification.

1. On an Android 17 emulator, open MainActivity, SettingsBackupActivity and
   DiagnosticsActivity at tablet size (sw>=600dp), in rotation and in split
   screen. Orientation declarations are ignored on large screens from API 36;
   verify the programmatic UI adapts and the backup/diagnostic transition
   geometry (device check 18/30 contracts) stays correct.
2. With predictive back enforced (no opt-out on API 36+ devices), open each
   confirm dialog in MainActivity and interact with the back gesture:
   - On API 34+ devices (OnBackAnimationCallback registered), dragging the
     gesture must shrink and fade the dialog in real time following
     `BackEvent.progress`, releasing it must continue seamlessly into the
     180ms scale+fade exit from the previewed state, and cancelling the
     gesture must spring the dialog back to full scale/alpha in 260ms
     without dismissing it.
   - On API 33 devices (plain callback, no progress events), releasing the
     back gesture must trigger the 180ms exit animation and `onBackDismiss`.
   - With three-button navigation on any API level, the same animation and
     callback must run through the `KEYCODE_BACK` listener.
   While an exit animation is already running, additional back input
   (gesture or key) must be ignored (dismissing guard), never canceling the
   in-flight animation.
3. On API 36+ emulator devices, confirm the predictive-back settings row is
   hidden entirely; on API 33-35 emulator devices with the switch disabled,
   confirm the dialog back path still falls back to the key listener.
4. Run the update check on both channels (Stable and Preview) and confirm
   GitHub requests still succeed with certificate transparency enabled by
   default (API 37+ targets).
5. Verify the roaming-settings receiver rejects an adb-originated explicit
   broadcast via the sender-package check. The accept path is a device check
   below.
6. On an Android 13 device, repeat the existing device checks plus the
   roaming entry click inside Bilibili. On a device whose host process can
   resolve `me.iacn.biliroaming`, the host opens it directly (host log
   "已打开哔哩漫游设置"). Where package visibility isolates the host, the
   click must reach the module receiver, which starts the activity itself
   (host log "已请求打开哔哩漫游设置（经模块 App 代开）"). The broadcast carries
   no launch PendingIntent; the 2026-09-04 entry in the long-form document
   records why that channel cannot work on the devices that need this path.
7. Pending Android 17 hardware (blocked on LSPosed support): re-verify the
   receiver-side direct start under the new BAL rules, and re-run checks 1-5
   on real hardware.

Known residual risks (documented, not blocking): the receiver-side activity
start on Android 17 hardware. If BAL blocks it there, no PendingIntent
workaround exists — `PendingIntent.send()` resolves the target under the
creator's uid and package visibility, which is the same identity that already
failed. That case needs a different entry design, not a patch to this channel.
Separately, static-final immutability and the lock-free MessageQueue take
effect per the *host app's* targetSdk, so KavaRef field writes in the Bilibili
process may need a re-audit if Bilibili itself moves to API 37.

## Irena API 101 compatibility acceptance

Use [irena_compatibility.md](irena_compatibility.md) for the fixed 2.0.0/7316 API
and Service commits, binary smoke procedure and device matrix. Verify explicit
obsolete-key cleanup after a failed submission, actual old-interface linkage,
replacement without duplicate callbacks, and 102 regression. Keep hot reload
disabled and record Java/ART/device evidence separately.

## Vector compatibility acceptance

Use [vector_compatibility.md](vector_compatibility.md) for the fixed 3080/3110
baselines, upstream delivery issues and device matrix. Local JVM coverage is
independent of Vector ART acceptance. Specifically verify that a failed commit
which updated only the SDK cache is sent again on retry, that reconnecting
reconfirms an identical document without advancing its generation unnecessarily,
and that diagnostic format 4 distinguishes submission from a matching host
receipt. Keep existing LSPosed and NPatch regressions in the same acceptance run.

## Multi-user and cloned-app boundary

The supported boundary is stated in `architecture.md`: only clones that keep the
`tv.danmaku.bili` package name (system multi-user, dual app, work profile) are
supported. Renamed clones and VirtualApp-style containers are out of scope and
must not be "made to work" by relaxing the module scope.

Local builds cannot prove any of the following. All three need real hardware.

1. Install the module in the primary user only, run Bilibili in a cloned user
   (MIUI dual app uses userId 999), and open the module in that user if a copy
   exists there. Record whether the framework delivers the libxposed service to
   the cloned-user module process at all.
   *Field report (2026-09-06, Vector stable 2.2/3080 + MIUI dual app):* a module
   copy does exist in user 999 and the manager lists it under its own user tab,
   but that copy never received the service — `service_not_connected`, with the
   module and host both in user 999. Not reproduced on our own hardware, and 3080
   predates two upstream delivery fixes, so this is one framework build's
   behaviour rather than a general result. See the 2026-09-06 entry in
   `development_experience.md`.
2. Determine whether the framework stores Remote Preferences per Android user.
   Publish `hook_config` from the primary user, then read the host log in the
   cloned user. If the group is empty there, the host must reject with
   `remote_key_set_mismatch` and install no feature Hook — a partially applied
   configuration would be a defect.
   *Answered for Vector at source level (canary-3110, 2026-09-06):* the store is
   keyed by `(module package, user_id, group)` and an injected host reads with
   `callingUid / 100000`, so a cloned host reads only what the module copy in its
   own user published. Device confirmation of the host's rejection path is still
   outstanding, and other frameworks must be checked separately.
3. With `staticScope=true`, record how the framework manager applies the fixed
   scope to secondary users: automatically, per user, or not at all.
   *Partially answered (Vector canary-3110 source):* scope rows are per
   `(app, user)` and a row whose user does not hold the module is dropped;
   a fixed scope is pruned to the claimed packages but is not auto-added, so the
   secondary user's row still has to exist. Whether the 3080 manager offers it
   the same way is unverified.

UI checks that can be run as soon as a secondary user exists:

4. In a non-primary user with no framework service, the activation card must
   read "no framework service for Android user N" with the real user id, not the
   generic "no compatible service" text, and the source line must wrap instead of
   ellipsizing. In the primary user the wording and single-line behavior must be
   unchanged.
5. Install the module and Bilibili in different users. The activation card must
   append the module/target user id pair, and the diagnostics `FRAMEWORK_SERVICE`
   item must show the same mismatch line. `TARGET_APP` must report the target as
   missing rather than guessing an identity from another user.
6. Confirm the multi-user hints never change item severity or the overall status,
   and that an exported diagnostic report still validates at the current format
   version — `DiagnosticReportCodec.CURRENT_FORMAT_VERSION`, 4 as of 2026-09-06 —
   with no user id field in it. The number moves when a report field is added, so
   read it from the codec rather than from this line.
7. Scope a renamed host clone by any means available and confirm the module logs
   the observed package name once and installs nothing.

## PGC automatic activity half-panel (2026-09-05)

The three PGC suites cover descriptor/constructor alignment, shifted slots, renamed
property-array fields, nullable flags, stale cache rejection, missing descriptors,
copy-on-filter semantics, null/short/wrong-type inputs, disabled and secondary
process behavior, registration failure, original construction failure, bounded
diagnostics, and replay of the six minimized activity responses. Shared suites also
cover full adapter cache serialization/merge, catalog 10, v1–v9 import preservation,
the Remote allowlist and first-class diagnostic registration. Raw HAR data must
remain in ignored Temp; only the stripped fixture is checked into test resources.

Run the standard four gates, then verify the APK signature, freshness, all DEX
entries, ZIP safety and Modern entry metadata. Actual framework/UI acceptance still
requires enabling the setting, restarting Bilibili and entering film/TV/anime pages.
Confirm APPLIED evidence with a real populated response, unchanged ordinary playback,
episode switching, rotation, comments and manual activity/VIP purchase routes; turn
the setting off and restart to verify rollback. A response with no half field must
not count as a filter hit. This feature does not grant playback access or change
trial/payment behavior.

An isolated app_process probe against the connected Android 16 device was attempted
without installing or launching the module/host. Both launches exited 137 before
reporting results. It supplies no runtime pass evidence. Device/UI acceptance and
Vector/Irena/NPatch framework-specific checks therefore remain pending.

## JingMatrix/LSPatch Manager POC (2026-09-06)

The detailed source baseline, module-side invariants, artifact hashes, and
device matrix are in [lspatch_compatibility.md](lspatch_compatibility.md).

- Local POC generation passed for the fixed LSPatch v1.2 / 487 release patcher
  and a local Bilibili 9.11.0 single-APK sample. The generated Manager-mode APK
  has `useManager=true`, API 102, v2 signing, and no unsafe ZIP paths.
- This result does **not** certify official split-APK delivery, patched-host
  installation, app launch, module lifecycle, Remote Preferences publication,
  or feature behavior.
- The connected device retains an official differently signed Bilibili package.
  It was not replaced; installation and runtime rows remain pending an explicit
  user-authorized test environment.
- Static coverage now verifies exact LSPatch manager routing, package visibility,
  API/Remote capability independence from the framework name, and the rule that
  a Manager service alone waits for a host configuration/install receipt. It
  also verifies stale-receipt rejection across pause/resume, late service
  binding, foreground reconnect, LSPatch failure precedence, valid NPatch
  heartbeat precedence, and bounded diagnostic report encoding; device timing
  and layout remain pending.

## Experimental appearance, compatibility and alignment (2026-09-05)

The third SettingsOrganizationInstrumentedTest case verifies the static Experimental
primary card, independent Appearance/Compatibility submenus, relocated display
controls, the existing predictive-back guard and search for both a menu title and a
child option. It measures the five appearance title origins and row right edges;
all must align and have nonzero layout dimensions. It also compares launcher
component state and application locale tags in addition to all 86 catalog values.

All three UI cases passed on Android 16 in 19.253 seconds after allowing the framework
module-update heads-up notification to expire. Keep the search editor scoped to the
Dialog root and avoid disabling notifications for tests. The production build passed
121 suites / 675 tests, Lint (0 errors / 170 warnings), Debug and Release R8. Final
screenshots and APK/installed hash evidence are in the experimental-appearance task
folder; settings_organization.md records the current inventory and validation digest.


## Functional submenu insets and enhancement folding (2026-09-05)

SettingsMenuSpacing owns the purification baseline (8dp shell + 12dp content).
Enhancement regions use the same collapsible group builder, with no duplicate root
padding. Compatibility compensates for its own 10dp shell in actual pixels. The
instrumented inset case opens all groups and compares control origins and right
edges relative to each outer submenu card, including all seven enhancement entries.
It also rejects an ellipsized compatibility title, now shortened to “免 Root 支持”.

All four device cases passed on Android 16 in 22.42 seconds. They also verify that
expanding one enhancement group leaves others unchanged and parent-menu toggling
preserves child-group states. Existing appearance and search checks continue to run,
with all 86 stored catalog values unchanged. Final local gates passed 675 JVM tests,
Lint (0 errors / 170 warnings), Debug, Release R8 and the instrumentation APK build.


## Ported BiliRoaming feature batch (2026-09-06)

Eight features were ported: danmaku weight/premium-gradient purification, the
mention-only and author comment judgements, share-link purification with the
mini-program downgrade, external-browser handoff, the system media notification,
the splash dark-mode background, the two live-room widgets, and AV-number display.
Every one of them defaults to off, so an upgrading install keeps its current
behavior until the user opts in.

Static evidence gathered before writing any hook (all from the 9.11.0 APK under
`Temp/host-compat/apks/`, decoded with `Temp/host-compat/dex_reader.py`):

- `DmSegMobileReply#getElemsList`, `getElems`, `getElemsCount` and
  `getColorfulSrcList` are referenced only by the dex that defines them
  (`classes29.dex`). A getter-boundary hook has no call site on that path, so the
  danmaku installer works on the Moss response boundary instead. `dmSegMobile` is
  referenced from `classes7`/`classes23` and `executeDmSegMobile` from
  `classes23`.
- `ShareClickResult` getters are referenced from `classes4.dex`, so the getter
  boundary is genuine for the share feature.
- `com.bilibili.droid.BVCompat.a(String, String)` is referenced from five other
  dex files; its decompiled body is
  `(TextUtils.isEmpty(bvid) || !enableBv) ? avid : bvid`.
- `dd_enable_system_media_control` still exists as a literal in `classes6.dex`;
  `ff_background_use_system_media_controls` no longer does. Both hooks stay
  installed for older hosts.
- The live room has no dedicated process in the manifest, so the existing
  main-process-only installer guard is correct.

Unit tests added: danmaku retain/weight-usability policy, share-link purification
(tracking-parameter allowlist, millisecond-to-second conversion, look-alike
domains, fragment preservation, idempotence), external-browser ownership policy
(payment gateways stay in-app, look-alike domains do not), AV-number selection
(order independent, self-validating), the Moss response-handler proxy (observer
failure never breaks host delivery, host exceptions keep their type, mismatched
delegates are refused), and the four comment judgements including the
longest-mention-first rule and exact author matching.

Catalog and adapter counters moved: `SettingsCatalog` 86 -> 100 entries with
`CATALOG_VERSION` 10 -> 11 and a new `settings-backup/catalog-v11.txt` fixture,
`DiagnosticFeatureRegistry` 31 -> 38 feature ids, and `VersionAdapter`
schema/rule 53/48 -> 54/49. The schema bump forces one background re-adaptation
on first launch after the update; that is expected and self-healing.

Not verified in this round: no device run, so none of the eight features has
runtime evidence yet. The live-room pager class is located structurally and has
only been checked against 9.11.0 static structure. The splash background hook is
installed but its visible effect depends on whether the Compose splash content
draws its own opaque background.

Local gates for this batch: `assembleDebug testDebugUnitTest lintDebug
minifyReleaseWithR8 assembleDebugAndroidTest --no-daemon` succeeded in 4m57s with
126 suites / 712 JVM tests and Lint 0 errors / 172 warnings (170 before this batch;
the two added are a `DiscouragedApi` for the splash `getIdentifier` lookup, which
matches the ten existing name-based lookups, and one `PluralsCandidate` on the new
weight option label). Debug APK SHA-256
`352a6219d234059a86a871499512fe2137997e353d46534002f4c64a11419399`, with no source
file newer than the artifact. Evidence lives in
`Temp/biliroaming-port-20260906/` (`gate-final.log`, the string-insertion script and
the single-class BVCompat decompilation).


## Dynamic feed and search filtering (2026-09-06, batch 2)

Static evidence gathered from the 9.11.0 APK before writing hooks:

- `DynamicMoss#dynAll` / `executeDynAll` are referenced only by `classes25.dex`,
  but `DynAllReply#getDynamicList` has a `classes8.dex` consumer. Decompiling
  `DynamicMossKtxKt.suspendDynAll` shows the real call site is the coroutine wrapper
  in the same dex, calling `dynAll(req, anonymous MossResponseHandler)`. The
  "no cross-dex reference means nobody calls it" shortcut from batch 1 therefore
  only holds when the response type has no cross-dex consumer either.
- `SearchAllResponse#getItemList` is referenced from `classes14.dex`, so the getter
  boundary is genuine for search.
- `SearchMoss#executeDefaultWords` is referenced from `classes12.dex` and has no
  `SearchMossKtx` wrapper, so the synchronous call is the single boundary.
- `AdditionalType` exposes `additional_type_goods_VALUE` and
  `additional_type_up_rcmd_VALUE`; `DynamicList` and `CardVideoUpList` expose the
  private `clearList`/`addAllList` pairs, `UpListItem` exposes `getLiveStateValue`
  and the private `setPos(long)`.

Unit tests added: `AuthorRuleSet` (uid/name split, exact name matching, unreadable
signals, zero/negative uids, rule bound), `ProtobufListRetention` (null-means-no-
rewrite, same-instance-when-unchanged, immutable copy), and `DynamicPurifyPolicy`
(per-fragment keyword matching, no cross-fragment match, text not read when no
keyword is set, independent author/promotion/charge judgements).

Counters moved: `SettingsCatalog` 100 -> 113 entries with `CATALOG_VERSION` 11 -> 12
and a new `settings-backup/catalog-v12.txt` fixture; `DiagnosticFeatureRegistry`
38 -> 40. `VersionAdapter` schema/rule stay at 54/49 because both faces are
unobfuscated bapis classes resolved structurally at install time.

Local gates: `assembleDebug testDebugUnitTest lintDebug minifyReleaseWithR8
assembleDebugAndroidTest --no-daemon` succeeded with 129 suites / 728 JVM tests and
Lint 0 errors / 172 warnings (unchanged from batch 1 — the new files contribute no
lint findings). Debug APK SHA-256
`a9a8371d88c923b7260200643cd7638c960f53c11e850ac6cefe93e53b8b559c`, with no source
file newer than the artifact.

Not verified: no device run. The dynamic top-bar `pos` renumbering, the topic-strip
clearing and the search-result filtering have no runtime evidence yet. Author
filtering on search results covers video cards only, by design.


## Ported-feature host coverage (2026-09-06, batch 3)

Both ported batches were re-checked offline against every host APK available
locally: 9.7.0, 9.8.0, 9.9.0, 9.10.0 and 9.11.0. Two probes live in
`Temp/biliroaming-port-20260906/`:

- `probe_compat.py` verifies 94 structural contracts (class plus method signature
  or field type) and counts cross-dex callers for every getter boundary.
- `probe_selectors.py` reports the candidate count and the drift trace for every
  `singleOrNull` structural selector, because an ambiguous selector silently skips
  the whole item.

Result: all 94 contracts are present on all five versions, and every selector
matches exactly one candidate on every version. The generated matrix is
`COMPAT_MATRIX.md` in the same folder.

Three code changes came directly out of that matrix:

1. The dynamic video tab exposes its top author bar as `getVideoUpList`, not
   `getUpList`, on all five versions, so "hide streaming authors" never resolved a
   container there. The lookup now tries both names.
2. The dynamic feed container is `DynamicList` on the combined tab and
   `CardVideoDynList` on the video tab. Resolution by return type stays, but the
   installer now also requires the container's `getList(int)` to return
   `DynamicItem`, turning a possible future silent no-op into an observable skip.
3. The search-suggestion protocol unit now distinguishes "host has no `SearchMoss`"
   (not applicable) from "class present but unusable" (degraded, reported as
   `partial:search-protocol`).

Two robustness fixes came out of the review rather than the matrix: the top author
bar is rewritten before `pos` is renumbered, so a failed write leaves positions
untouched; and both author lists must be readable before either is written back,
so an unreadable second list can no longer be cleared.

Confirmed correct by the matrix: the live pager implementation class drifts
`IM.h` → `KM.h` → `LM.g` → `LM.h` → `MM.g` (and its field name from `a` to `b`)
while staying a single candidate; the live player bridge drifts `p5.b` → `q5.b`
with all three control methods intact; `DmSegMobileReply#getElemsList` has no
cross-dex caller on any of the five versions, so the Moss boundary choice holds
across the whole range; `shareMode` is a boxed `Integer` everywhere; and all five
hosts declare `QUERY_ALL_PACKAGES`, so the external-browser resolution check is not
blocked by Android 11+ package visibility.

Coverage boundary: no 8.x host APK remains on this machine, so nothing here may be
extrapolated below 9.7.0. Every ported feature defaults to off and degrades to
`partial` or "not installed" when a path is missing, so an older host does not
crash — but there is no evidence that these switches work there.

Local gates after the changes: 129 suites / 728 JVM tests, Lint 0 errors /
172 warnings, Debug APK SHA-256
`2216796a71f4d90c86f1ae6176ac07d3b5efe1cd3c6cf350d4ff35c38c117b56`. Still no device
run: this round was offline static verification plus code hardening only.


## Ported-feature host coverage extended to 26 versions (2026-09-06, batch 4)

The user supplied 21 additional host APKs, so the verifiable range grew from
9.7.0–9.11.0 to **8.84.0 – 9.11.0, 26 versions** (8.85.x and 8.90.2 are still
missing locally, and nothing before 8.84.0 is covered).

Tooling was consolidated. `host_identity.py` parses the `<manifest>` start tag of
each APK with a minimal AXML reader and checks package, versionName and
versionCode: all 26 report `tv.danmaku.bili` with internal versions matching their
filenames. `probe_hosts.py` replaces the two earlier probes with a single pass that
prefilters by dex type index, so it reads only a two-byte class index for most
method ids; per-APK time dropped from about 40 seconds to 3.4, and the whole sweep
takes roughly 100 seconds. Its 9.11.0 output matches the earlier probes item by
item.

Result: **93 structural contracts across 26 versions, zero missing**, every
`singleOrNull` selector matches exactly one candidate on every version, and every
getter-boundary verdict is identical across the whole range. Two findings are worth
recording:

- The live pager implementation class takes 23 distinct names across the 26
  versions (`of1.e` … `MM.g`) and the field holding it renamed from `a` to `b` in
  9.10.0, yet there is always exactly one candidate. Hardcoding any single name
  would have failed silently on 25 of 26 versions.
- The live player bridge interface renames 14 times and not monotonically: 9.6.0
  and 9.9.0–9.11.0 are `q5.b` while 9.7.0/9.8.0 in between are `p5.b`. All three
  control methods exist on all 26.

One code change came out of the sweep. Scanning dex string constants shows
`dd_enable_system_media_control` present on all 26 versions and
`ff_background_use_system_media_controls` present on **none** of them. The
`ConfigManager$Companion#isHitFF` hook copied from upstream therefore could never
fire anywhere in the verifiable range, while sitting on a startup path called from
20 dex files and inflating the coverage denominator. It was removed;
`SystemMediaNotificationFeatureInstaller` now hooks only `DeviceDecision#getBoolean`.

Local gates after the change: 129 suites / 728 JVM tests, Lint 0 errors /
172 warnings, Debug APK SHA-256
`86587942779d43d5ae3bd3a0af9b12af05c8980631cd201f4af0a156f209d20f`, no source newer
than the artifact. Still no device run.

## Independent manual telemetry quota (2026-09-07)

- Manual network attempts use a persistent rolling 24-hour allowance of three,
  separate from automatic cooldown and retry timestamps. Previews and unavailable
  host receipts do not consume attempts; transport failures do. Local clock
  rollback retains future attempts, and malformed quota state fails closed.
- GitHub telemetry controls now link to a nested explanation rather than expanding
  the full text. Simplified Chinese, Traditional Chinese and English descriptions
  were updated together; telemetry endpoint URLs are absent from those resources.
- Server checks: TypeScript and 37 tests pass, including real SQLite transaction
  rollback, rolling-boundary refill, quota-preserving purge, daily deduplication,
  source rejection before body parsing, 5-second stalled-body cancellation, and
  indexed purge lookup. The simultaneous-request test uses a serialized local
  SQLite transaction adapter; it is not a distributed load test.
- Android gates: 139 suites / 798 JVM tests, zero failures or errors; Lint 0 errors /
  172 warnings; assembleDebug and minifyReleaseWithR8 both pass.
- Debug APK SHA-256:
  `10cff2e06ace6a3aa7acf22d318fbedfdc3cecae68cfbeec6f0cecad63332804`
  (13,391,562 bytes). This Debug APK targets Staging; it was not installed this turn.
- Staging Worker version: `7929a291-fe64-4563-b21f-d960e1dd2978`.
  Production Worker version: `18fea01f-2045-4641-bbb3-2ec75140ec38`.
  Both databases have migrations 0002 and 0003 applied.
- Both live endpoints returned health 200, automatic 204, manual 204/204/204/429
  with UPLOAD_QUOTA_REACHED, then automatic 204 and token-scoped synthetic purge 204.
  Quota records intentionally remain for scheduled expiry. No unrelated rows were
  removed. These are desktop-to-service synthetic checks, not device UI acceptance
  or proof of module-originated network requests. No flood test was performed.
- Source/location limiters are approximate; D1 enforces the exact manual allowance
  and a 2,000 validated-request daily/environment budget. This protects write growth,
  not guaranteed availability or an account billing cap. Fabricated identities and
  distributed sources remain an abuse boundary, not authenticated physical devices.

## Device/ROM and framework-service version telemetry (2026-09-07)

- Added bounded manufacturer/model labels, ROM family, connected-service framework
  version name and versionCode. API level remains a separate field. Disconnected
  cached service versions are encoded as unknown. No manager package inventory,
  serial, IMEI, Android ID, raw properties or full build fingerprint is sent.
- Telemetry disclosure version 3 gates the expanded fields; core terms/Hook protocol
  remains version 2. The notice is updated in all three locales. Legacy opt-outs
  remain off; existing enabled users must review the new disclosure before sending.
  Manual/automatic timing, identity rotation and purge rules are unchanged.
- Final Android gates: 140 suites / 803 tests, zero failures/errors; Debug assembly,
  Lint and Release R8 pass. Lint: 0 errors, 172 warnings.
- Debug APK: 13,395,074 bytes; SHA-256
  `e1ddca7d27ebd732d764074ef2b55abd9f48648f351cb0748e8c276676ca8f77`.
  Main source mtimes are not newer than the artifact. No device installation or
  live ROM-classification/consent-dialog acceptance was performed this turn.
- Server: typecheck and 50 tests pass. Includes disclosure-marker rejection,
  bounded labels/ROM enum, independent service version/API fields, legacy missing
  buckets, device/version filtering and matched-cohort regression separation.
- Both live environments: expanded fields plus old disclosure marker return 400;
  accepted report returns 204; manual 204/204/204/429; automatic after manual quota
  exhaustion returns 204; token-scoped synthetic raw-report cleanup returns 204.
  Six read-only analytics queries pass on both databases. Private dashboard still
  redirects anonymous requests to login (302).
- Dashboard adds four distributions and filters using the existing chart primitives.
  Synthetic loopback preview returned 200. No browser screenshot or logged-in
  interaction QA was performed. Version IDs are recorded in server/ANALYTICS.md.

## Cold-start update badge and capability diagnostics (2026-09-07)

The update gate waits for a continuous 10-second resumed interval and allows one
automatic request per module process. It uses monotonic time, cancels on pause,
preserves a current-channel notice through Activity recreation and suppresses stale
results. Automatic discovery displays NEW instead of opening a dialog.

Capability telemetry uses schema 2 / catalog 1 / disclosure 4, host receipt schema 3
and diagnostic export format 5. Core terms remain 2. There are 116 leaf capability/
technical-path entries; runtime support is explicitly graded, not claimed universal.
The full catalog plus aggregate groups fits one 32 KiB packet without truncation.

Final local gates: 143 suites / 823 JVM tests; Debug assembly, Lint and Release R8
passed. Lint: 0 errors / 174 warnings. Server typecheck and 58 tests passed.
Debug APK SHA-256:
`d9a981b094389bb452c59c141a8ecf9f97ff69ea9b77fb01cd57c503fcfd1f1b`
(13,411,306 bytes, Staging endpoint). No device install or live animation/Hook
acceptance was performed. Cloudflare staging and production synthetic requests
verified all 116 query rows, old-disclosure rejection, manual quota and cleanup.
Detailed implementation scope and deployment IDs: capability_diagnostics.md.
