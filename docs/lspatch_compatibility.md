# JingMatrix/LSPatch compatibility

This is a module-side compatibility record for **JingMatrix/LSPatch Manager
mode**. It separates upstream statements, local artifact checks, JVM coverage,
and device evidence. A successful build or patched APK generation does not
prove that a patched Bilibili process loads this module, consumes configuration,
or applies a feature hook.

## Fixed baseline (2026-09-06)

| Component | Baseline / evidence |
| --- | --- |
| LSPatch Manager | [v1.2 / 487](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2), release APK SHA-256 `E93E34C5170831AAD51B2035E3CD94A1C3B5298711189FABC359B566BC34AD17` |
| LSPatch patcher | v1.2 / 487 release JAR SHA-256 `D238FDC414D121B7FA454D8B4CCF420DF3A8C97D563761861FF92BD9C5DA2165` |
| Patched runtime metadata | LSPatch 1.2 / 487, API 102, Vector core `canary-3106` / `e00c5c5038bbcc14e0b382ab893301d6993e6989` from generated `config.json` |
| Module source baseline | `ca6ce2e`; version 1.1.3 / 14; Modern target API 102 |
| Local Bilibili input | 9.11.0 / 9110200, SHA-256 `47CCE44F0CFD0D0F25339CB86A0ADB3E0D230C68FAB8A43EDAF0B20C48F8E5C6` |

The local Bilibili input is a single APK sample. It is not evidence that the
official split-delivery install set can be patched, installed, or launched.

## Module-side behavior and boundaries

- Keep the single Modern entry, `tv.danmaku.bili` / `system` static scope,
  minimum API 101, target API 102, protective exception mode, and disabled
  automatic hot reload. Do not add a legacy entry for LSPatch.
- Keep the existing Remote Preferences document, complete snapshot validation,
  terms gate, generation/digest handling, and fail-closed host bootstrap. A
  missing or malformed remote document installs no feature hooks and never
  falls back to module-private preferences, a Provider, or a broadcast.
- The LSPatch service name is used only for manager routing and presentation.
  API version plus `PROP_CAP_REMOTE` remain the only configuration capability
  gate. A framework name never grants capability.
- LSPatch Manager may deliver a capable service to the module application
  before the patched host has consumed configuration. On each foreground entry
  or LSPatch service connection, the activation card makes at most one bounded,
  signed host-receipt query for that service connection. It retains neither the
  receipt nor an in-flight request across a lifecycle stop or connection change.
  Only accepted configuration plus a completed install chain is positive LSPatch
  activation evidence. Rejected, unauthorized, or failed-install receipts show
  an action state rather than repeatedly asking the user to start the host.
- A verified NPatch heartbeat remains stronger evidence than an LSPatch service
  which is merely waiting for a host receipt. The two delivery paths remain
  independent; this ordering does not change either protocol.
- `org.lsposed.lspatch` is an explicit, independently checked manager target.
  It never falls back to LSPosed, Vector, or a shell-parasitic activity.
- Do not modify `HookEntry`, the NPatch protocol, `RemoteHookConfigStore`, or
  the system-server roaming hook solely to support LSPatch. LSPatch cannot
  inject `system_server`; host-side roaming behavior and system-side BAL
  allowance are separate evidence chains.

## Local POC record

| Gate | Result | Evidence / limit |
| --- | --- | --- |
| G0: artifact identity | Pass | Official v1.2 / 487 patcher and manager release assets were downloaded and SHA-256 verified. Historical POC/iteration-2/iteration-3 Debug APK hashes are `51C5E160BAD3CC3397E5DD124EDBCE129AFAA98A2D36CE84A8A04D5C4F6F8172`, `81EB6B05DD80F30BD5EB6657156AAED83A73A0A44FF0F16BC7202E533831CF66`, and `AC7CDDBB1A920140F2001A7E527B399C919396A6F4B7A278C7BDF50421B874BD`; the isolated iteration-4 artifact is `7ACF424E2C47C18812ADB55CD1A530318EC6DE6A03D056C45E43A092B77E0EAF`, and the post-reintegration main-tree artifact is `592372839A15E423FDFDDA1FBBEA741AF1504B1BBAAC314D29E978BF98E315ED`. Both carry the same package/version and v2 signer; the checkout-specific APK entry comparison differs only in the three Xposed metadata payloads. |
| G1: Manager-mode generation | Pass | The patcher processed the local Bilibili 9.11.0 APK and generated a 215,130,689-byte output with SHA-256 `2A1BAE3228B8BDC59E39502574262FFEC77DFCE7641EE7EBE6A64381E82A89C5`. `apksigner` verified v2 signing; no absolute or parent-traversal ZIP entry was found. |
| G1: device install and lifecycle | Pending user-authorized test environment | The connected device retains official Bilibili 8.90.2 under the same package name. Replacing it with the differently signed patched package requires an explicit data-impact decision. No manager, module, or patched host was installed by this POC. |
| G2: configuration publication and host generation receipt | Pending device | Requires Manager-mode service delivery, a real module-side commit, a host cold start, and a matching accepted generation receipt. SDK cache equality is not sufficient. |
| G3: fail-closed and framework boundary | Pending device | Requires missing/corrupt/unapproved configuration, manager loss/reconnect, and proof that LSPatch does not claim system-server support. |
| Split delivery | Unverified | The local sample is not a complete official split install set. Do not claim split compatibility from the single-APK generation result. |

The isolated static suite also covers exact LSPatch manager routing, package
visibility, receipt-state classification, stale callback rejection, foreground
pause/resume and late-service/reconnect session handling, NPatch heartbeat
precedence, bounded diagnostic-export mapping, and multilingual multiline
activation guidance. Device layout and callback timing remain pending.

## Required device matrix before compatibility can be claimed

1. Record Android/ROM, ABI, Android user, LSPatch manager version/hash, module
   APK hash, original base/split set hashes, patch options, and whether another
   framework is active.
2. Patch and install a complete supported Bilibili split set in Manager mode;
   confirm cold start, one Modern module entry, one package callback, one
   attach bootstrap, and non-crashing DexKit native loading.
3. With NPatch support off, accept terms and publish generation `g1`; cold-start
   the patched host and require an independent accepted host receipt. Change one
   reversible setting, publish `g2`, cold-start again, and require `g2`.
4. Exercise unapproved, malformed, missing, and manager-disconnected
   configurations. Every new host process must remain fail-closed.
5. Verify the activation card says waiting when only the LSPatch service is
   known, and becomes positive only after the diagnostics host receipt records
   accepted configuration and a completed install chain.
6. Test main process and real secondary processes, module/target Android-user
   alignment, manager restart, module upgrade, and representative purification,
   enhancement, player, and comment features. Keep NPatch, LSPosed, Vector, and
   Irena results separate.

## Explicit non-goals

- No embedded-mode configuration UI. An embedded host with no companion
  publisher remains fail-closed by design. NPatch integration mode (`--embed`)
  has the same shape — its Remote Store lives inside the patched application and
  the injected side is read-only — so the two are stated once as a shared
  boundary in [architecture.md](architecture.md) rather than as two separate
  framework quirks.
- No signature-bypass escalation, anti-detection work, account-risk mitigation,
  or claim that a re-signed Bilibili package is safe for a user account.
- No LSPatch canary compatibility promise. This record is pinned to v1.2 / 487.
- No claim that `system_server` hooks, BiliRoaming background-start allowance,
  or another module's behavior is provided by LSPatch.
