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

## Intentional boundaries

- `hookinfo.pb` parsing and write semantics remain unchanged; its behavior is
  validated separately because it is tightly coupled to BiliRoaming versions.
- Roaming logging remains unchanged in this change set; its UI configuration
  wiring is intentionally deferred.
- The `system` Xposed scope is required for the narrowly-scoped MIUI background
  activity-start allowance used by the roaming settings fallback.
