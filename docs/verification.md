# Regression verification

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
with `minApiVersion=102`/`targetApiVersion=102`, and `scope.list`. It must not
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
24. On each supported API 102 framework, open the module UI and confirm the
    service status reports connected, API 102 and Remote Preferences capable.
    If the service is unavailable, the host must fail closed and install no
    feature Hook; it must not fall back to the compatibility Provider or an
    ordered broadcast authorization race. Click Accept once while disconnected:
    the UI must retain a non-authorizing pending state, avoid asking for a second
    acceptance, and automatically converge to `ACCEPTED` only after service bind
    and full read-back. Without the standard framework service, use the explicit
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
    log `API 102 Remote Preferences 验证成功` with the same generation and must not
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
    the UI decision rolled back.
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
2. Determine whether the framework stores Remote Preferences per Android user.
   Publish `hook_config` from the primary user, then read the host log in the
   cloned user. If the group is empty there, the host must reject with
   `remote_key_set_mismatch` and install no feature Hook — a partially applied
   configuration would be a defect.
3. With `staticScope=true`, record how the framework manager applies the fixed
   scope to secondary users: automatically, per user, or not at all.

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
   and that an exported diagnostic report still validates at format version 3
   with no user id field in it.
7. Scope a renamed host clone by any means available and confirm the module logs
   the observed package name once and installs nothing.
