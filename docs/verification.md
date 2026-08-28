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

## Device checks

1. In Bilibili's main process, toggle roaming compatibility on and off while
   the app is running. The module App broadcast must update the local cache.
2. Send the same action from an unrelated package. Android must reject it
   because the sender lacks
   `com.Bilibili_Innocent_Lab.xposedmodule.permission.SET_ROAMING_COMPAT`.
3. Test cold start, `web`, `download`, and `ijkservice` processes with the
   module process both alive and stopped.
4. Test Bilibili 8.90.2, 9.0.0, and the 9.1.0-9.9.0 major-version paths where
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
