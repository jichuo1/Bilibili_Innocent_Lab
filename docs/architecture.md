# Runtime architecture

## Hide frequently visited on Dynamics (2026-09-08)

The default-off dynamic.frequent_visits.hidden setting removes the whole
DynAllReply.upList / DynVideoReply.videoUpList carrier using a host builder copy.
It reuses DynamicPurifyFeatureInstaller's sync and asynchronous response paths,
not network blocking, global getters or View hiding. Whole-carrier resolution
requires only the paired getter type, has method and builder clear method; it
does not depend on individual UP fields, live state or position accessors.

Whole removal takes precedence over live-entry filtering without changing its
saved preference. Topic and dynamic-item filters remain independent. No-match
and default replies retain identity; clear/build/readback failure returns the
original reply. APPLIED follows verified absence in the completed copy, not hook
registration. The verified 9.10 UI list composer only inserts this component
when the relevant has method is true and the first row is nonempty, so removing
the field omits the component rather than reserving a blank container.

Settings catalog v15 adds one Boolean (122 settings). Diagnostic catalog v3 adds
dynamic_frequent_visits_hidden; the local-only server projection keeps v1/v2
accepted, with since=3 preventing the new leaf from masquerading as older data.
No viewing content, account identity or new telemetry payload category is added.
Native cold-cache layout behavior remains a device acceptance item.

## Video-session default speed (2026-09-08)

PlayerSpeedSessions extends the existing default-speed setting with two paths:
bound Theseus playable transitions and a narrowly scoped prepared-callback bridge
for the shared player core. Stable business anchors derive obfuscated members;
no global speed replacement, touch hook, polling, DEX scan or persistent media ID
is introduced. The original long-press policy and temporary speed slot remain
independent. Same-media rebinds and explicit first-binding sharing changes preserve
the current base; a changed media identity reinitializes the base.

Prepared-callback discovery is deferred until the host registers its listener.
Installation evidence stays partial until the concrete callback and setter are
registered. Thread-local scopes are restored after exceptions and nested callbacks;
only matching core/media initialization calls may change an argument. Weak-key
state maps never hold their own player key strongly. Unknown/non-VOD sources fail
open. See player_controls_port.md for the exact current behavior and device limits.

## Homepage PGC and selected-special filtering (2026-09-08)

Two default-off settings reuse the existing homepage response-list boundary:
home.recommend.pgc.removed and home.recommend.special_cards.removed. The existing
selection dialog, backup pipeline and authorized Remote config publish both keys;
settings catalog v14 adds exactly these two entries. Older backups preserve their
current values. No new dependency, host thread, network request or page-wide hook
is introduced.

HomeExtraCardPolicy is homepage-only. Exact known PGC types and bounded official
playback routes establish film/series identity; explicit bangumi_ugc is not a
token-only PGC match. Known special templates are considered only after PGC,
so a special-looking film card belongs to PGC, not both switches. Unknown types,
unrecognized URLs and unavailable optional fields fail open. Titles never identify
these categories. CopyOnFilter preserves order, element identity and the original
list on no match; it does not mutate the host response.

Diagnostic catalog v2 adds two independently measured leaves. APPLIED requires
publishing a filtered list containing an actual matching removal. The local-only
server projection accepts catalog v1 and v2, retains the received version, and
rejects v2-only IDs declared as v1. Existing payload privacy fields, consent,
retention and independent upload quotas remain unchanged.

## Independent capability coverage follow-up (2026-09-08)

Adapter schema 56 / rule 51 allows missing comment content/message chains without
discarding member-based rules, and retains discovered top-reply paths when the
default replacement is unavailable so the coverage denominator cannot silently
shrink. Installation enables only the usable requested judgements and preserves
partial coverage. Dynamic item-list, topic and UP-list carriers are independently
resolved; search title/name/uid and Story types also degrade independently.

Home search suggestions use both synchronous and asynchronous DefaultWords replies
with builder-based text-only copies. Routing fields remain untouched. Banner and
home recommendation status strings now agree with structured partial results.
See coverage_review_20260908.md for scope and device-validation limits.

## Player interactive response coverage (2026-09-08)

Adapter schema 55 / rule 50 records independent nullable Guide and DmResource
carriers plus synchronous and asynchronous Moss paths. Guide failure no longer
suppresses DmResource or response discovery. Each existing whitelist field is
cleared in a host protobuf builder copy; a has/count probe must show content before
editing and verify its absence afterward. Parent copies publish only after all
child changes succeed. Ordinary danmaku, chapter points, Chronos and unknown fields
are retained. No-match responses preserve identity.

Both response modes use PlayerInteractiveReplyCleaner. Getter fallbacks return
cleaned child copies, with a scoped ThreadLocal guard preventing recursive/double
cleanup during internal reads. Command and activity-list getters have independent
fallbacks; no callback mutates its parent reply in place. Registration completeness
requires both response modes per leaf, not merely a registered getter. Local
APPLIED evidence now requires actual content removal; the existing telemetry wire
catalog remains unchanged (interactive leaves still export OBSERVED only).

## Stability repair update (2026-09-07)

This update supersedes the older in-place danmaku/dynamic rewrite and synchronous
scan-cache descriptions below. Danmaku and dynamic filters now stage changes in
host protobuf builders, retaining unknown/unrelated fields; dynamic UP entries are
copied before position changes. A complete replacement reply is published at the
sync return or asynchronous onNext boundary only after every selected edit builds.
Failure returns the original reply and records a bounded error; no match returns
the original instance without building a copy. The proxy's observer-only entry
remains compatible, and host callback exceptions retain their original type.

Scan hooks submit immutable data, not JSON. The host bridge encodes, validates,
reads source metadata and persists on its existing background executor. A bounded
per-surface latest-value publisher serializes writes, confirms only successes and
retries a failure at most once per drain; a later equal submission can retry again.
Disk restore cannot overwrite a newer snapshot. The receiver reads memory only;
the module still independently verifies the receipt's source version.

Version-change uploads arriving during another upload retain one pending action.
The main-thread completion path drains it through the normal receipt/consent/quota
checks; it does not reuse a stale receipt or consume manual/regular automatic quota.
This is not a durable background job or a guarantee against OS process reclamation.
Host-to-module notification and existing consent boundaries remain unchanged.

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
only a locked page with exit and review actions. Accept first commits private,
non-authorizing pending metadata while retaining the previous decision. The
existing single-thread Modern publisher then publishes `ACCEPTED`; the pending
page also offers an explicit NPatch path for environments without a framework
service. Both paths promote the private decision and clear pending only after
their publication verification succeeds. The standard Service 102 path requires
an acknowledged commit and full client-cache validation; NPatch retains its
independent protocol read-back. A cached value alone never acknowledges a write.
If the service is absent, the pending gate remains locked and resumes on service
bind without asking the user to accept again. Decline continues to invalidate a
pending accept, confirm the remote closed decision, and only then commit the
private declined decision. The back key exits an undecided gate without writing
a decision, while outside-touch dismissal is disabled.
The undecided and pending gates also render a read-only environment snapshot:
module UID/userId, primary-versus-possible-profile classification, framework
name/API/Remote capability, current-user visibility and UID/userId of
`tv.danmaku.bili`, same-user comparison, and a bounded failure code. This uses a
single current-user `PackageManager` lookup plus in-process Remote diagnostics;
it does not enumerate other users, query the host process, expose settings, or
weaken the authorization gate.
`FreeCopyActivity`, `SettingsBackupActivity`, and `DiagnosticsActivity` apply the same check and finish
immediately when the decision is unauthorized, so an internal Activity launch
or restored Activity stack cannot bypass the gate.

Supported Modern API 101/102 frameworks use one authorization bootstrap. The standard path
publishes a strictly allowlisted `hook_config` Remote Preferences group through
`XposedService`; the explicitly enabled NPatch path obtains `IXposedService`
through `getRemoteService` with `modulePackageName` and publishes the same exact
document. Bilibili reads that group synchronously in
`Application.attach.before` and installs the complete Hook chain in the same
ordering window. The host never opens the module app's private files and never
falls back to Provider, ordered broadcast, or a legacy preference bridge.
Schema, catalog, exact key set, value types/ranges, generation, module version,
delivery state, NPatch revision, terms decision and SHA-256 digest must all
validate before either authorization or feature settings are consumed. Missing,
partial, corrupt, stale-version, disabled-delivery, or service-unavailable state
disables every feature. Pending terms metadata is private and additive; it is
not a fifth wire decision, is excluded from backup, and never changes the
Remote Preferences exact key set or digest.

While unauthorized, the provider's locale, free-copy, and roaming routes return
safe disabled/default snapshots, and the roaming settings action also refuses
to start another Activity. Bilibili installs only the minimal Application and
Instrumentation bootstrap before this decision. Accepting terms does not
retrofit hooks into an already-running unauthorized Bilibili process, so that
process must be restarted.

## Modern API 101/102 entry and host configuration

The only module entry is `HookEntry : XposedModule`. Package hooks are installed
from `onPackageReady`, and the narrowly scoped system-server hooks are installed
from `onSystemServerStarting`. Packaging uses only
`META-INF/xposed/java_init.list`, `module.prop`, and `scope.list`; the legacy
`assets/xposed_init`, Yuki initializer resource, manifest `xposedminversion`,
YukiHookAPI dependency and rovo89 API dependency are absent.

The minimum API is 101 and the target remains 102. API 101 compatibility is based
on Irena 2.0.0's pinned interface and lifecycle. It uses internal logical-point
replacement without invoking the 102-only Hook ID API; 102+ retains native IDs.
The newer call is isolated behind an R8-preserved version bridge.

`module.prop` declares `staticScope=true` and `scope.list` contains only
`tv.danmaku.bili` and `system`, so the scope cannot be extended in the framework
manager. The supported multi-instance boundary follows from that: system
multi-user, dual-app and work-profile clones keep the official package name and
are supported, while renamed clones and VirtualApp-style containers are not.
`onPackageReady` rejects any other package and logs the observed name once per
package, because a silent early return makes that failure unobservable. Host
resource ids are resolved with `getIdentifier(name, "id", TARGET_PACKAGE)` and
host caches live under `/data/user/<userId>/tv.danmaku.bili/`, so a renamed host
would fail on both paths even if a framework injected it.

The libxposed service binder is delivered per Android user: the framework calls
`XposedProvider` with `SendBinder`, and `XposedServiceHelper` is purely passive —
it never binds or retries on its own. A missing service may mean the module is
not enabled for this user, or that framework delivery or process reconnection
failed. It cannot identify a single cause by itself.
The module surfaces that distinction in the activation card and the diagnostics
`FRAMEWORK_SERVICE` item; it never works around it.

The module's ordinary default preferences remain private and authoritative for
the settings UI and backup system. `RemoteHookConfigStore` resolves every
catalog record to its effective value and writes only the `hook_config` Remote
Preferences group. The allowlist additionally contains the free-copy revision
and adapter-reset timestamp. Metadata records schema, catalog, generation,
module version, delivery state, NPatch revision, terms version/decision,
readiness and a canonical SHA-256 digest. Arbitrary
preferences, credentials, update throttles, UI state, language, skin state and
framework operational data cannot enter this group.

Module startup registers one `XposedServiceHelper` listener. Relevant source
changes are coalesced on one daemon publisher. Service 102 updates its local
cache before IPC, even when commit subsequently fails. `RemoteHookConfigCommitter`
therefore acknowledges only a successful commit followed by complete client-cache
validation. It deduplicates only the exact acknowledged snapshot on the same
service connection. Failure or a connection change forces another actual write;
the SDK cache is not an independent framework-database read-back. Each host process reads
the group once, validates the exact document and converts it into an immutable
`SnapshotHookConfigSource`; bind, scroll, draw and Hook callbacks perform no
cross-process preference I/O.

The editor explicitly removes obsolete keys and puts the full allowlisted document
in one commit; it does not rely on a `clear` flag, which Irena ignores. Pending
removals survive failed submissions even when the SDK cache has already removed
those keys, and are scoped to the active service connection.

NPatch publication keeps the same read-update-read-back rule and never asks the
host to call the module Provider. A disabled NPatch intent is represented by a
newer complete document with `deliveryEnabled=false`, not by a partial delete or
an app-private tombstone. Provider rejection, a dead or wrong-descriptor Binder,
read-only storage, timeout, or read-back mismatch records a bounded failure and
leaves the host fail-closed. The setting switch controls only this configuration
delivery; it cannot disable native injection already embedded by NPatch.

Both no-root deliveries are limited to their manager modes, and for the same
structural reason. NPatch integration mode (`--embed`) keeps the Remote Store
inside the patched application, where the injected side is read-only and no
companion publisher exists; an LSPatch embedded host has the same shape. A
separate module settings application cannot publish an authorized snapshot in
either case, so both remain fail-closed by design rather than falling back to a
private file, a Provider or a broadcast. This is a boundary, not a defect to be
worked around, and the two no-root status strings name it so the state is not
mistaken for a missing manager. The hand-written NPatch Binder path additionally
pins the two `IXposedService` transaction codes in a unit test, because a renumbered
AIDL would otherwise fail only as an opaque remote error.

Accepting terms retains a non-authorizing private pending decision until the
publication succeeds, as described above. Declining publishes the denied
configuration before committing the private decision; a failed write does not
claim the previous remote snapshot was revoked. Neither decision retrofits hooks
into an already-running Bilibili process.

Pinned API baselines, manager routing, platform limits and device acceptance
matrices are in [vector_compatibility.md](vector_compatibility.md),
[irena_compatibility.md](irena_compatibility.md) and
[lspatch_compatibility.md](lspatch_compatibility.md).
Host AndroidX classes are resolved through the host ClassLoader, including the
RecyclerView hooks and type checks used by comment binding. Module-owned AndroidX
classes are not used as substitutes for host types.

The private preference filename is deliberately unchanged, so settings already
owned by the module UI remain available after the framework migration. Missing
settings use catalog defaults, and malformed individual source values are
normalized only in the Remote Preferences mirror rather than deleting the
private source.

## Local diagnostics center

`DiagnosticsActivity` is an unexported, read-only module Activity. It reuses the
same terms gate, Activity-scoped skin session, fixed Liquid underlay, foreground-only
stretch viewport, and predictive-back preference as the other module UI. Collection
runs on one ViewModel-owned worker; Activity recreation does not retain a View,
renderer, Binder, PackageInfo, or Context in the diagnostic model.

Diagnostics distinguish locally configured intent, a Remote Preferences snapshot that
the module publisher wrote and fully read back, host structure adaptation, runtime
observation, actual rule application, and an explicit unavailable boundary. Framework
API capability is not treated as proof that an individual host hook adapted or installed.
The Bilibili main process therefore exposes a signature-permission-protected, ordered-
broadcast receipt that is initialized before Modern configuration authorization. The
receipt reports only bounded bootstrap/config/install-chain states, Hook-point counts,
and the 29 logical feature IDs from `DiagnosticFeatureRegistry`; it never reads the host
private cache from the module process or exposes host class/member names. The query
validates sender identity where available, nonce, protocol, payload digest, target version
and update time, and module version before the module UI accepts the receipt.

Every `FeatureInstallCoordinator` result is mirrored into the receipt as installed,
disabled, not-applicable, safely skipped, or isolated failure. Free-copy and roaming use
explicit adapters into the same registry because they retain specialized installation
paths. Runtime ADAPTED/OBSERVED/APPLIED evidence remains a separate 0/1 signal: absence
means no verified hit, not failure. Feature stages are first-hit atomic bits, and both
stage and install updates are persisted on one daemon with a minimum 30-second coalescing
interval; Hook callbacks perform no IPC and do not accumulate per-hit counters.

`RemoteHookConfigStore` exposes an in-memory bounded publication diagnostic
(`NOT_INITIALIZED`, `WAITING_FOR_SERVICE`, `PUBLISHING`, `READY`, or `FAILED`) with
attempt/success timestamps, generation, pending state, and one of a small failure-code
set and a service connection generation. The committer retains only an
acknowledgement digest, not a second copy of settings. Diagnostics never retain
preference values or export the original Throwable. MainActivity
uses only already-resolved activation/NPatch state plus this in-memory summary, so the
new overview entry adds no package query or `AtomicFile` read to its render path.

The optional SAF report is a versioned UTF-8 JSON allowlist and is reopened, compared
byte-for-byte, and structurally validated before success is shown. It includes module
and target versions, framework capability, bounded runtime state, bootstrap/install-chain
codes, Hook-point/feature counts, skin backend, catalog counts, and assessment codes.
Preference values, custom rules, file paths, log text, exception details, and host
class/member names are structurally absent. No storage permission or network operation
is used.

## Privacy-preserving adaptation telemetry

Telemetry is a module-App-only consumer of the existing bounded host diagnostics
receipt. The Bilibili process registers no telemetry thread, scheduler or network
request. `MainActivity.onStart` and a successful update check may ask
`TelemetryCoordinator` to run, but the coordinator first requires the current
versioned terms decision and a separate telemetry choice, then enforces one network
attempt per 24 hours. A missing host receipt schedules only a local 15-minute retry.
Explicit manual requests have a separate persistent rolling 24-hour allowance of 3
network attempts. Manual collection/results never modify automatic cooldown state;
network failures consume a manual attempt, while previews and unavailable receipts do not.
The allowance is synchronously reserved under the storage lock immediately before sending.

Device/ROM and framework-service-version metadata use independent telemetry disclosure
version 3, without changing the core terms/Hook protocol version 2. Both the core terms
and current telemetry disclosure must authorize sending. Existing enabled users are
asked to review the expanded notice; existing opt-outs remain off. The new notice
does not reset identities, manual allowances or automatic cooldowns.

The module's existing background telemetry worker reads bounded product manufacturer/
model labels and classifies a ROM family from fixed system-property indicators.
Only the family enum is serialized; property values, full build fingerprints and
unique hardware identifiers are not. Version metadata reuses the diagnostics input
from the connected framework service; disconnected cached versions become unknown.
No package inventory, new Hook callback, scheduler, root operation or production
dependency is introduced. The server accepts the optional schema-v1 extension only
with disclosure marker 3 and strict field validation. This marker is not attestation.

Terms protocol 2 adds a fixed, visible telemetry choice above the accept/decline
buttons. It is selected on by default, but can be switched off before acceptance;
refusal does not affect terms authorization or any module feature. Old terms records
do not authorize the new processing purpose. The same choice is exposed as the last
row of the GitHub secondary dialog, with a separate information button, exact JSON
preview, manual upload and confirmation-gated raw-report purge. Long explanatory text
is reached through a dedicated nested entry, rather than expanded in the control dialog.

`TelemetryStore` uses its own private `telemetry_preferences` file. Consent, rotating
IDs, deletion tokens, timestamps and transport state are excluded from
`SettingsCatalog`, settings backup and Remote `hook_config`. Missing/corrupt storage
fails closed. An installation UUID and 256-bit deletion token rotate after 90 days;
the previous deletion token remains locally usable for 31 days so the server's
30-day raw-retention window can still be purged. The raw UUID is HMACed by the server
and the deletion token is stored only as SHA-256.

`TelemetryPayloadCodec` is an explicit schema-v2 capability allowlist (the server
also accepts legacy schema v1). It sends numeric module
and host versions, Android SDK, a bounded ABI/framework/delivery category, adapter
rule generations, bootstrap counts, and non-disabled feature installation evidence.
It excludes setting values, disabled/not-reported feature choices, account data,
content, device hardware identifiers, precise time, raw logs, exception text and
host member names. Unavailable adapter cache/duration/DexKit evidence is encoded as
`unknown`, never guessed as success or false. Debug uses the Staging host; Release
uses the fixed HTTPS Production host. Redirects, compression and remote endpoint
configuration are absent.

The Cloudflare Worker independently repeats strict schema/size/forbidden-field
validation, stores only a canonical projection, limits one installation to one row
per UTC day, keeps raw rows for 30 days and persists long-term feature cells only at
10 or more installation keys. A `410` endpoint retirement can only stop collection;
purge stays available while ingestion is retired.

## Host version adaptation and DEX assist

`hook/VersionAdapter` locates every hook point by structure — field shapes,
parameter types, return types resolved through KavaRef — because obfuscated
names do not survive host rebuilds. `quickLocate` serves the `loadApp` fast
path. `ensureAdapted` itself runs synchronously inside `Application.attach`;
only the `adapt()` location work moves to a daemon thread. The synchronous
path must therefore stay free of file and ZIP I/O.

`hook/adapter/dex` is a bounded fallback for the single case where structural
location fails. DexKit sits behind the `DexAssistEngine` interface, so neither
the adapter nor the hook registry holds a bridge object. A bridge is created
only from the background adaptation thread, only when every built-in owner
candidate for the block-update point has missed, and is closed as soon as its
archive has been queried. It is never created from `quickLocate`, from
`loadApp`, or from an installed hook callback. The native library is probed
through a tri-state latch; a load failure degrades the point to missing rather
than raising. Candidates returned by a query are re-resolved through the host
ClassLoader and re-checked against the same reflection constraints, and an
ambiguous result — several owners, or several leaves under one owner — is
treated as missing. Every outcome, including each failure reason, is recorded
as a single `dex.assist` diagnostic.

The adaptation cache is written through `AtomicJsonCache`: a same-directory
temporary file is synced, parsed back through `AdaptResult.fromJson`, and only
then atomically renamed over the live cache, so an interrupted write cannot
replace a working result with a truncated document.

`dexSourceFingerprint` is a content digest over the `classes*.dex` central
directory entries of every code archive. It covers two blind spots in the host
fingerprint, which reads only the base APK's length and modification time: a
change confined to a DEX-bearing split, and a reinstall of identical content.
It is deliberately excluded from the synchronous cache-validity check. A
daemon thread compares it after a fast-path hit; a mismatch only invalidates
the cache so the next launch re-locates, because hooks in the current process
are already installed against the previous result and cannot be retrofitted.
An unreadable fingerprint leaves the cache intact.

## Shared runtime helpers

`runtime/AndroidUserSpace` is the single point that resolves Android user
spaces. It converts a uid to a user id, classifies any non-primary user as a
possible clone or work profile, compares the module and target user ids, and
captures a bounded snapshot holding only those user ids. `TargetAppStorage` and
the user-terms gate helpers delegate to it instead of keeping their own copies.
The result feeds display text only: it never changes hook authorization, never
alters `ModuleHealthEvaluator` severity, and never enters the exported
diagnostic report.

`runtime/TargetAppStorage` centralizes Bilibili cache path construction and
derives the Android user id from the current process uid through
`AndroidUserSpace`. This prevents the version-adapter cache and roaming cache
from diverging on work-profile or multi-user devices.

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

The current catalog contains 74 records. Seventy-three are automatically restorable.
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

## Module UI Liquid renderer (current protocol 9)

The executable source currently identifies the Liquid renderer as protocol version 9.
Material You remains the default and safe fallback; Liquid selection, pending activation,
health confirmation, and rollback remain isolated in `ui_skin_preferences`. An Activity may
create its skin session only after its own terms gate and early-return checks have completed.
`MainActivity`, `FreeCopyActivity`, `SettingsBackupActivity`, and `DiagnosticsActivity` all
participate. The process registry allows multiple owners for the same renderer version and
activation attempt, so closing one translucent or secondary Activity cannot invalidate another.

The one-way backend order remains REFRACTION (API 33+ RuntimeShader), BLUR (API 31+
RenderNode/RenderEffect), then TRANSLUCENT. Construction and runtime-draw failures advance to a
lower backend without retrying the failed backend in the same Activity. Bounded degradation
reasons are exposed only in local diagnostics; complete renderer failure persists the Material
You rollback before an Activity may recreate.

Every Activity retains a stable generated or user-selected underlay for its root background and
fallbacks. When the explicit real-time option is enabled on a supported hardware Canvas, Liquid
surfaces may additionally sample a sanitized full-root PixelCopy source. Capture uses three
buffers, a default 1,000,000-pixel budget, and a 0.72 scale ceiling. The stable underlay replaces
already-rendered glass regions before a captured frame becomes the next backdrop, preventing
recursive feedback; `NO_GLASS_VISIBLE` is a valid frame outcome rather than a capture failure.

Capture scheduling starts from the highest supported display mode up to 120 Hz. Thermal policy
caps both cadence and pixel budget, while measured successful-completion throughput can only step
the session down to a supported mode no lower than 60 Hz. The suppression mask is built when the
PixelCopy request is issued from draw-time surface origins, not later from callback-time geometry.
Scroll callbacks invalidate only surfaces whose recorded root-space origin actually moved;
content, optical-intensity, and backdrop changes still invalidate all affected surfaces.

Bitmap, shader, Paint, Path, RenderNode, and coordinate buffers are reused. Only the active
backend is rebound to a new backdrop; the stable suppression underlay is pre-scaled per capture
size, and completed real-time buffers are prepared for drawing before the next surface traversal.
Foreground/background lifecycle stops capture and performance hints, critical memory pressure
releases real-time resources and advances to TRANSLUCENT, and Activity destruction closes its own
owner idempotently.

The M0, M1a, and M1b sections below preserve the historical design stages. Their milestone-only
scope statements do not describe or override the current protocol-9 implementation above.

## Module UI skin foundation (M0, historical milestone)

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

## Module UI Liquid renderer (M1a, historical milestone)

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

## Module UI Liquid background pipeline (M1b, historical milestone)

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

## Theseus automatic activity half-panel

`PgcAutoActivityPopupFeatureInstaller` owns one main-process descriptor construction
hook, controlled by the opt-in `hide_pgc_auto_activity_popup` catalog setting.
`PgcAutoActivityPopupLocator` inspects only member metadata during quick adaptation.
Enabled installation revalidates the cached point with the current host ClassLoader,
then reads the descriptor property table once. Constructor parameter erasures and
property order must agree; the unique `play_half_container` property must have the
unique half-popup type and a nullable flag. Missing or ambiguous evidence skips this
feature without registering a broader constructor, WebView, payment, or network hook.

The callback clones the inner argument array only for a non-null typed half popup.
It replaces that one slot with null and proceeds with the original descriptor.
Other activity fields retain their original references. OBSERVED is recorded only
for a populated half; APPLIED additionally requires successful construction and a
verified null half field in the actual returned model. Diagnostic failures are
isolated from filtering; only the first evidence and each bounded failure reason are
reported. No host instance is retained beyond the callback, and no disk, IPC,
network, class scan, or DexKit work runs in it.

Cache schema 53/rules 47 and catalog 10 carry this feature through the existing
atomic adapter cache, immutable Remote settings, and Modern API 101/102 registration.
Old settings backups leave an absent new key untouched. This path affects automatic
activity panels; manual routes and playback/payment authorization remain owned by
the host. Static host verification covers 8.90.2 and 9.10.0; UI acceptance is separate.

## Danmaku stream purification and the Moss response proxy

`DanmakuPurifyFeatureInstaller` owns the danmaku *content* stream, which is a
different carrier from the player interactive overlays: overlays live on
`ViewProgressReply`/`DmViewReply`, danmaku elements live on `DmSegMobileReply`.
The two features stay independent, with separate settings and separate diagnostics.

The boundary is the Moss response, not a getter. In the 9.11.0 host,
`DmSegMobileReply#getElemsList` is referenced only by the dex that defines it, so a
getter-boundary rewrite has no call site; the reply is handed to the parser whole.
The installer therefore covers the synchronous `executeDmSegMobile` /
`executeDmSegCache` return value and, for the asynchronous `dmSegMobile` /
`dmSegCache` overloads, replaces the host callback with `MossResponseHandlerProxy`.

`MossResponseHandlerProxy` is a `java.lang.reflect.Proxy` created on the host
ClassLoader over the host's own `MossResponseHandler` interface. It observes
`onNext` and forwards every method verbatim, including default methods with return
values. An observer failure is swallowed so host delivery is never broken; an
exception thrown by the host callback keeps its original type. It refuses to wrap a
delegate that is not an instance of the interface rather than handing the host an
object with different semantics. It retains no reply and no host callback beyond a
single request.

Purification uses the protobuf-lite private `clearElems`/`addAllElems` and
`clearColorfulSrc`/`addAllColorfulSrc` pairs. The premium-gradient type value is read
from the host's own `DmColorfulType.VipGradualColor_VALUE` constant, falling back to
the documented value only when that read fails. Three guards apply: the process-wide
`getDefaultInstance()` singleton is skipped, a segment whose weights are all zero is
passed through untouched (a server that stops sending weights must not empty the
danmaku track), and a rewrite that removes nothing neither writes back nor reports
APPLIED.

## Dynamic feed and search result filtering

`DynamicPurifyFeatureInstaller` covers four Moss boundaries: the synchronous
`executeDynAll`/`executeDynVideo` return values and the asynchronous
`dynAll`/`dynVideo` callbacks through `MossResponseHandlerProxy`. The combined and
video tabs share one judgement set, because a keyword or author list means the same
thing on both.

The reference heuristic used for danmaku needs one correction here. In 9.11.0 no dex
outside `classes25.dex` references `DynamicMoss#dynAll`, yet the feature is alive:
the business module calls `DynamicMossKtxKt.suspendDynAll`, a generated coroutine
wrapper that lives in the same dex and calls `dynAll` with an anonymous handler.
"No cross-dex reference" therefore only proves a getter is dead when the *response
type* has no cross-dex consumer either — which is exactly the danmaku case.

Per-item judgements read module captions and forwarded/original descriptions, the
author name and uid, the additional-card type, and the charge-only privilege flag.
Keyword matching runs per text fragment and stops at the first hit; it never joins
fragments, so a keyword can never straddle a boundary. The promoted additional-card
type values are read from the host's own `AdditionalType` constants and only fall
back to documented numbers when both constants are unreadable.

Response-level work clears the topic strip — always gated on `hasTopicList()`, since
`clear*` succeeds silently on an unset field — and drops streaming entries from the
top author bar, renumbering `pos` across both lists so the strip leaves no gap.

`SearchPurifyFeatureInstaller` can use a getter boundary because
`SearchAllResponse#getItemList` has a genuine cross-dex consumer. It removes ad and
special campaign cards, and applies title/author judgements only to video cards,
gated on `hasAv()` because the card slot is a protobuf oneof.

The search box default word has no separate switch: the existing
"hide home search suggestion" setting now also clears the text fields of
`SearchMoss.executeDefaultWords`, so the suggestion disappears on the search page as
well. Routing fields are deliberately left intact so the box stays tappable.

`AuthorRuleSet` and `ProtobufListRetention` are the shared primitives behind the
comment, dynamic and search faces: one uid/name rule semantics, one retain/filter
implementation for in-place rewrites and getter rewrites respectively.

## Share, external links, live room and video-id surfaces

`SharePurifyFeatureInstaller` works on the host's public `ShareClickResult` getters,
whose call sites are genuine in the host business dex. It performs pure string
purification with a query-parameter allowlist and writes the result back to the same
field so the copied link and the sent payload cannot diverge. It deliberately does
not expand `b23.tv` short links: that needs a synchronous network request inside a
callback that can run on the main thread.

`ExternalBrowserFeatureInstaller` shares the `Instrumentation#execStartActivity`
platform boundary with `HomeVerticalDetailFeatureInstaller` under a different logical
hook id. Its predicate is ownership only — an `*MWebActivity` component, an http(s)
scheme and a host outside the in-app allowlist, which includes Bilibili domains and
the payment gateways. Before rewriting it asks the caller's PackageManager whether
anything can handle the URL and keeps the original intent when nothing can. Only the
link travels; host extras are not copied.

`LiveRoomWidgetFeatureInstaller` locates the obfuscated pager implementation
structurally, from the declared field of the stable `LiveVerticalPagerView` whose
type is a *subclass* of the host RecyclerView; a field typed as RecyclerView itself
is rejected so the platform base class is never hooked. Double-tap-to-pause resolves
`isPlaying`/`pause`/`resume` from the return type of `getPlayerCommonBridge()` and is
not installed at all when any of them is missing, so the gesture never becomes a
no-op.

`BvToAvFeatureInstaller` hooks the single static `(String, String) -> String` chooser
on `com.bilibili.droid.BVCompat`, located structurally because the method name is
obfuscated. Rewriting the return value leaves the host's text-matching patterns
intact, unlike flipping the static feature flag. The rewrite is self-validating: it
only applies when the returned value really is a BV id and the other argument is a
non-blank non-BV id.

`SystemMediaNotificationFeatureInstaller` overrides two configuration lookups for two
specific keys. Those lookups run hundreds of times during startup, so the callback
body is a single string comparison with no parsing, logging or allocation.

## Settings organization and navigation

The main settings view separates Purification from Enhancements. Each primary card
owns its advanced submenu as its last child at construction time. Free-copy actions
and both bubble-appearance options live together in Enhancements. The former
post-construction move below Experimental has been removed.

Experimental is now a static primary section with independent Appearance and
Compatibility submenus. Appearance owns skin, Liquid background, color spec,
language and launcher visibility; Compatibility owns framework support and host
adaptation, retaining the existing platform guard for predictive back. Display no
longer occupies a separate primary card. The four submenu states share the same
bounded animation and search-revelation entry, with no preference-key migration.
Appearance applies horizontal insets once at its content root; clickable rows and
switch titles do not add competing insets. Purpose-specific local vector icons use
the existing theme tint, with upstream attribution in THIRD_PARTY_NOTICES.md.

Advanced categories carry a purpose and a localized region title. The six ordinary
purification regions and all four enhancement regions use the same independent
collapsible cards; only shared Home/detail duration filtering uses a plain heading.
`AdvancedCategoryLayoutPolicy` partitions by headings only and preserves every
non-heading child, including the final description before the next heading. Invalid
markers keep the original layout. Regrouping happens before the first draw, reuses
the same control instances, and never rebinds preference listeners.

Search traverses both collapsed submenu roots and their category containers. Results
include purpose and region; revealing a result expands only the required menu and
category, then scrolls and highlights after the existing bounded property animation.
No per-frame height animation, extra hardware layer, configuration migration or
host Hook change is introduced. See `settings_organization.md` for the full inventory.

## Intentional boundaries

- `hookinfo.pb` parsing and write semantics remain unchanged; its behavior is
  validated separately because it is tightly coupled to BiliRoaming versions.
- Roaming logging remains unchanged in this change set; its UI configuration
  wiring is intentionally deferred.
- The `system` Xposed scope is required for the narrowly-scoped MIUI background
  activity-start allowance used by the roaming settings fallback.
