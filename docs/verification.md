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
