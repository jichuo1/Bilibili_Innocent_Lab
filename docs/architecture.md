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

## Intentional boundaries

- `hookinfo.pb` parsing and write semantics remain unchanged; its behavior is
  validated separately because it is tightly coupled to BiliRoaming versions.
- Roaming logging remains unchanged in this change set; its UI configuration
  wiring is intentionally deferred.
- The `system` Xposed scope is required for the narrowly-scoped MIUI background
  activity-start allowance used by the roaming settings fallback.
