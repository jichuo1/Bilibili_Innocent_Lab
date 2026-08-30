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

## Module UI Liquid renderer (M1a)

M1a activates renderer protocol version 1 without changing the Material You
default. MainActivity exposes a single-choice "Interface appearance" entry as the
first item under Experimental features. A selection is synchronously persisted
before the dialog leaves or the Activity is recreated. A failed write keeps the
current Activity and skin; a renderer failure recreates only after the repository
can confirm that the Material You rollback was persisted, preventing a recovery
write failure from becoming a recreate loop.

The MainActivity terms gate remains the first boundary. `prepareSkinSession()` is
called only after an authorized decision; undecided and declined screens continue
to use the original Material palette and do not read skin preferences. The content
root is bound only after Hikage has installed the layout. FreeCopyActivity,
SettingsBackupActivity, the predictive-back motion host, and host-process overlays
do not create Liquid sessions in M1a. This is required while the validation registry
has one process-wide owner: a translucent SettingsBackupActivity may coexist with
MainActivity and must not invalidate its renderer owner.

`LiquidActivityRenderer` tries one-way backends in this order:

1. API 33+, hardware Canvas: an isolated RuntimeShader refraction backend;
2. API 31+, hardware Canvas: an isolated RenderNode/RenderEffect blur backend;
3. API 27+, software Canvas, low memory, or graphics failure: a resource-free
   translucent surface backend.

Failure in a higher backend advances once and never retries it in the same Activity
session. The translucent backend remains a valid Liquid presentation; only failure
of the complete renderer invokes the persisted Material You rollback. The UI shows
the backend actually held by the session and uses an explicit initializing or
unavailable label instead of reporting an unknown backend as translucent.

GPU backends share one Activity-scoped static Monet backdrop. It is generated at
0.25x dimensions, capped at 524,288 ARGB_8888 pixels (2 MiB), and is never produced
by capturing the Window or View tree. Consequently, rendered Liquid surfaces are
not recursively sampled. Bitmap, RuntimeShader, RenderEffect, and RenderNode setup
occurs only during bind, size change, or fallback; Drawable draw calls reuse all
objects and update only coordinates and uniforms. Critical trim-memory events
release the sampled source and permanently move that Activity session to the
translucent backend. Activity destruction closes the backend, sampled source, and
layout listener idempotently.

Pending Liquid is promoted only after a visible root Drawable draw succeeds and its
posted callback still owns both the persisted activation attempt and the current
process renderer owner. A stale Activity silently retires its renderer; it cannot
show a failure, write a rollback, or compete with the replacement Activity. A
failed health-confirmation commit is treated as renderer validation failure and
must persist Material You before MainActivity may recreate.

M1a routes only ordinary top-level `surfaceVariant` setting cards and project modal
containers through the skin surface factory. Activation/status cards, accent
buttons, editors, and the logging slider retain their semantic colors. The former
`createGlassContainer`/`presentGlassDialog` helpers are named
`createModalContainer`/`presentModalDialog`; "Liquid" is now the only term for the
new skin. The Material path preserves the previous modal radius, fill, and subtle
white stroke.

The API 33 refraction program is an Android View/Drawable adaptation of
Kyant0/AndroidLiquidGlass commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`.
Its source header, `THIRD_PARTY_NOTICES.md`, and the complete Apache-2.0 license in
`third_party/AndroidLiquidGlass-LICENSE.txt` are part of the implementation.

## Module UI Liquid background pipeline (M1b)

Renderer protocol version 2 supersedes M1a's background presentation while keeping
the same skin selection, recovery owner, and one-way backend order. The root window
is now an independent underlay rather than another glass surface. It always draws a
stable Material background plus two low-intensity, oversized radial Monet washes;
the top and bottom return to the exact background color. CARD and MODAL are the only
roles that apply refraction/blur and tint. Existing white edge-highlight width and
alpha are unchanged.

The underlay remains a static 0.25x ARGB_8888 bitmap with the same 2 MiB cap and is
still never produced by Window/View-tree capture. It is now retained consistently
for REFRACTION, BLUR, and TRANSLUCENT so API level or a graphics fallback cannot
silently replace the page background with a different design. GPU CARD tint is
lighter and neutral; MODAL tint is independently stronger, while software Canvas
and TRANSLUCENT use separate opaque-enough fallbacks for text readability.

API 33 sampling explicitly uses linear BitmapShader filtering. Bitmap-to-root scale
and the surface's root-space origin are RuntimeShader uniforms; mutating a child
Shader local matrix after `setInputShader()` is forbidden because the parent shader
may retain the child's earlier native instance. Root and surface locations use
screen coordinates so Activity and Dialog windows share one coordinate system.
The refraction shader also includes safe gradient normalization, small-radius
stability, and restrained linear-sRGB saturation adapted from
QmDeve/AndroidLiquidGlassView v1.0.5. Full contrast/white-point processing,
seven-sample dispersion, per-frame View-tree recording, and a new production
dependency are intentionally not included.

Nested scrolling invalidates the weakly registered visible Liquid surface Views so
their root-space sampling origins are re-recorded instead of moving with stale
hardware display lists. A temporary software Canvas draws only the local translucent
fallback and never advances the Activity's persistent backend. Root size changes are
coalesced by frame, reuse the bitmap when sampled dimensions are unchanged, and swap
new sources before retiring old ownership. Submitted bitmaps are never explicitly
`recycle()`d as a substitute for a GPU fence.

Memory callbacks are classified semantically: UI-hidden/background levels do not
permanently downgrade a renderer; running-critical, complete, and `onLowMemory()` do.
After the root becomes a raw underlay, pending Liquid health is confirmed only after
the first visible CARD/MODAL draw has exercised its actual backend.

The QmDeve-derived shader portions retain their MIT attribution in the source,
`THIRD_PARTY_NOTICES.md`, and
`third_party/AndroidLiquidGlassView-LICENSE.txt`; the pre-existing Kyant Apache-2.0
attribution remains separate.

## Intentional boundaries

- `hookinfo.pb` parsing and write semantics remain unchanged; its behavior is
  validated separately because it is tightly coupled to BiliRoaming versions.
- Roaming logging remains unchanged in this change set; its UI configuration
  wiring is intentionally deferred.
- The `system` Xposed scope is required for the narrowly-scoped MIUI background
  activity-start allowance used by the roaming settings fallback.
