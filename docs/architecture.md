# Runtime architecture

## Process boundaries

`MainActivity` writes module preferences. `HookEntry` runs only in Bilibili and
installs the feature hooks. `RoamingCompatHook` owns the BiliRoaming integration
and keeps its runtime state isolated from ordinary UI hooks.

The roaming switch has two channels:

1. A read-only content provider is the cold-start fallback.
2. A permission-protected dynamic receiver updates Bilibili's local cache while
   it is already running.

The receiver requires the module's signature permission. Package targeting is
kept for routing only and is not treated as an authorization mechanism.

The exported compatibility provider validates the Binder caller uid and accepts
only the module itself, trusted system/ADB callers, or tv.danmaku.bili.
The settings-opening receiver has no discoverable intent filter; Bilibili uses
an explicit component together with a short-lived request timestamp. Android
14+ additionally validates the framework-reported sender package.

The module-owned UI locale has a separate two-channel bridge:

1. AppCompat and the platform per-app locale remain the only authoritative
   source. A module-private `system/en/zh-CN/zh-Hant` mirror lets the provider
   answer before an AppCompat Activity exists on Android 12 and lower.
2. The compatibility provider exposes the mirror as a read-only cold-start
   fallback. A separate signature-permission broadcast updates Bilibili's
   in-memory and local cache while its main process is already running.

Bilibili reads its local locale cache synchronously during attach and performs
at most one provider refresh on a daemon thread. Injected comments, menus,
toasts, and overlays only read immutable text snapshots; they never query the
provider on a hook, bind, scroll, or draw path. `system` follows the device
system locale rather than Bilibili's own per-app override. The bridge never
calls `Locale.setDefault` and never caches an Activity, View, or host object.

## Versioned user-terms authorization

`settings/terms/UserTermsConsentStore` owns a module-private, versioned decision
record. It uses ordinary app `SharedPreferences`, not Yuki preferences, the
settings-backup protocol, or a host-process cache. `ACCEPTED` and
`LEGACY_EXEMPT` authorize module operation; `UNDECIDED` and `DECLINED` do not.
Missing state alone may enter the one-time legacy migration: an upgraded package
installation is eligible only when its first-install time predates the fixed
terms-rollout cutoff. Legacy-sentinel evidence additionally requires both the
first-install time and positive `prefs_alive_ts` to predate that cutoff. An
eligible result is persisted as `LEGACY_EXEMPT`; an install at the exact cutoff
or later, a corrupt record, or a terms-version mismatch becomes `UNDECIDED` and
must never re-enter legacy inference.
Failure to obtain/read the private preferences or to commit the initial
migration also returns `UNDECIDED`; storage failure is never interpreted as a
missing legacy record.

`MainActivity` resolves this state before reading feature preferences, writing
cross-process mirrors, constructing the settings hierarchy, or starting update
checks. An undecided user sees the scrollable terms gate; a declined user sees
only a locked page with exit and review actions. Accept and decline use a
synchronous commit. The back key exits an undecided gate without writing a
decision, while outside-touch dismissal is disabled.
`FreeCopyActivity` and `SettingsBackupActivity` apply the same check and finish
immediately when the decision is unauthorized, so an internal Activity launch
or restored Activity stack cannot bypass the gate.

Authorization has two parallel, live module-process channels and no positive
host cache. The exported compatibility provider exposes a read-only
`/hook_authorization` snapshot through the existing Binder-caller allowlist. In
parallel, Bilibili sends an explicit ordered broadcast to
`RoamingOpenReceiver`; the receiver accepts no state input, reads
`UserTermsConsentStore` itself, and returns handled/authorized result extras.
This second channel remains available on known hosts that isolate cross-package
provider authorities. Android 14+ additionally checks the framework-reported
sender package for the broadcast response. Android 13 and lower do not expose
that sender identity to this receiver, so the fallback intentionally has no
intent filter, accepts no state input, and returns only one read-only Boolean;
it can neither mutate consent nor start the settings UI through this action.

While unauthorized, the provider's locale, free-copy, and roaming routes return
safe disabled/default snapshots, and the roaming settings action also refuses
to start another Activity. Bilibili installs only the minimal
Application/Instrumentation authorization bootstrap before this decision.
During `Application.attach`, the provider task and ordered-broadcast result
handler share one 800 ms deadline and race to atomically publish the first
explicit Boolean result; an unknown/failed channel does not resolve the race.
Only an explicit authorized result installs the complete hook chain once. An
explicit denial, two failed/unknown channels, missing state, corruption, or
timeout all fail closed, discard the installer reference, and cannot enable
hooks later from a delayed result. Accepting terms does not retrofit hooks into
an already-running unauthorized Bilibili process, so that process must be
restarted.

## Shared runtime helpers

`runtime/TargetAppStorage` centralizes Bilibili cache path construction and
derives the Android user id from the current process uid. This prevents the
version-adapter cache and roaming cache from diverging on work-profile or
multi-user devices.

`runtime/ShellCommandRunner` owns bounded root-command execution. It merges and
continuously drains process output so the settings UI cannot leave a background
thread blocked on a full stderr pipe.

## Versioned settings backup

Settings backup is an allowlisted protocol, not a copy of the backing
SharedPreferences file. `settings/backup/SettingsCatalog` assigns every
supported preference a stable logical id, current storage key, typed default,
value-version, catalog introduction version, restore policy, validation rules,
and post-import effects. Storage keys may therefore change without changing the
on-disk identity. Derived revisions, liveness sentinels, adaptation caches,
application language, update channel, and launcher-icon state are excluded.

Format v1 is a UTF-8 JSON document with product, format, catalog, source, scope,
and explicit-value metadata. A deterministic binary canonicalization is hashed
with SHA-256 to detect accidental damage; it is not a signature or proof of
origin. Published format decoders are permanent compatibility entry points:
future formats add a new decoder branch and must retain the v1 branch.

Import is split into a pure `ImportPlan` and a confirmed apply phase. The plan
migrates known catalog versions, preserves missing or implicit source values,
keeps current-only settings, reports removed/future/manual/invalid records, and
contains only writes that are valid under the current schema. The Activity uses
Android's Storage Access Framework, displays the complete plan, and never asks
for broad storage permission. Confirmed writes use the Yuki preferences bridge
and synchronous `commit()`; they never call `clear()` or write unknown keys.

The v1 catalog contains 70 records. Sixty-nine are automatically restorable.
Roaming compatibility remains in the file and preview as `MANUAL`, including
its backup and current values, but the importer never writes it. Old invalid
QN, comment-level, and logging enum values are exported using the same effective
normalization as the current UI rather than blocking the entire backup.

Free-copy preferences also drive an `AtomicFile` mirror. Before committing
those values, the importer writes an idempotent roll-forward journal containing
the final values and revision. A process restart can then finish the mirror
without guessing or attempting a fragile cross-file rollback. Confirmed import
execution and its verified/possibly-changed result live in a ViewModel so an
Activity recreation cannot interrupt or misreport the transaction.

The backup Activity uses a translucent, non-floating window with one persistent
motion host. MainActivity supplies weakly-held card/title screen geometry; the
host draws a rounded surface from that card to the full content bounds, stages
page alpha/translation, and moves a dedicated title copy to the toolbar when
both endpoints are single-line LTR text. The same `expansion` value is directly
seeked by AndroidX predictive-back progress, so no framework/shared-element
transition is involved. Timed entry/exit delays page content until the container
and title are near their destinations; interactive predictive back uses a wider
content-alpha profile and keeps it through cancel/commit completion, avoiding a
one-frame disappearance after nonlinear gesture mapping. While a timed or
gesture-completion morph is still in flight, a newly-started back action is
consumed until the surface is stable so the two content-alpha profiles cannot
switch at an intermediate expansion. Internal preview/error back navigation
never runs the cross-Activity morph. Restored or incompatible window geometry
falls back to a neutral fade/scale rather than targeting stale screen coordinates.

## Module UI skin foundation (M0)

The module UI has a passive skin foundation under `ui/skin`. Material You remains
the only effective renderer in M0: there is no user-visible skin selector, Liquid
renderer, shader, backdrop capture, Window mutation, or host-process overlay. The
three existing Activities inherit `SkinnedActivity`, but their existing layout,
transition, language, predictive-back, and system-bar order remains authoritative.

`SkinnedActivity.monetColors` is deliberately independent from the skin repository.
It performs the same Activity-scoped `MonetColors.fromWallpaper()` call as before
and caches only the resulting integer palette. This lets MainActivity color its
terms gate before authorization without reading or repairing skin preferences.
The reserved `prepareSkinSession()` entry is not called in M0; a future renderer
must call it only after the Activity-specific terms gate and other existing early
returns have succeeded. Session shutdown is idempotent, runs before the base
Activity is destroyed, and cannot be resurrected after the lifecycle tombstone.

Skin selection uses the independent `ui_skin_preferences` file and is excluded
from `SettingsCatalog`, settings backup, Yuki preferences, Hook mirrors, and NPatch
snapshots. The persisted protocol is strictly decoded: missing data means Material
You, while corrupt, unknown, type-mismatched, or inconsistent data fails closed to
Material You. A Liquid selection is written as pending before any renderer may be
installed. A new process rolls an unfinished pending state back once; ordinary
Activity recreation reads the in-process state and must not misinterpret the same
pending activation as a crash.

Asynchronous renderer results require two independent identities. The persisted
`activationAttemptId` separates user selections and same-version retries. The
process-local `LiquidRenderSessionOwner` separates Activity/renderer instances
within one activation. A new renderer claim atomically invalidates the old owner;
success and failure callbacks must pass both checks, and closing an old Activity
cannot release the new Activity's owner. Future Liquid code must use
`SkinRepository.claimLiquidRenderSession()`, report through the returned owner,
and release that owner during renderer teardown; it must not call the pure recovery
guard directly.

## Intentional boundaries

- `hookinfo.pb` parsing and write semantics remain unchanged; its behavior is
  validated separately because it is tightly coupled to BiliRoaming versions.
- Roaming logging remains unchanged in this change set; its UI configuration
  wiring is intentionally deferred.
- The `system` Xposed scope is required for the narrowly-scoped MIUI background
  activity-start allowance used by the roaming settings fallback.
