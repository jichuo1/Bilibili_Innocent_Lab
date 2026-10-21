<div align="center">

<img src="banner.svg" alt="Bilibili Innocent Lab" width="100%">

[![Stable](https://img.shields.io/github/v/release/jichuo1/Bilibili_Innocent_Lab?style=flat-square&color=FB7299&label=stable)](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases)
[![License](https://img.shields.io/github/license/jichuo1/Bilibili_Innocent_Lab?style=flat-square&color=00AEEC)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.1%2B-2d3a55?style=flat-square&labelColor=2d3a55)](#requirements)
[![Framework](https://img.shields.io/badge/Xposed-LSPosed-00AEEC?style=flat-square&labelColor=2d3a55)](#requirements)
[![Target](https://img.shields.io/badge/target-tv.danmaku.bili-FB7299?style=flat-square&labelColor=2d3a55)](https://www.bilibili.com)
[![Telegram](https://img.shields.io/badge/Telegram-group-26A5E4?style=flat-square&logo=telegram&labelColor=2d3a55)](https://t.me/Bilibili_Innocent_Lab)

### A UI cleanup and interaction enhancement module for the Android Bilibili client

Built on **Xposed / LSPosed, libxposed Modern API and KavaRef**, targeting the official Android client `tv.danmaku.bili`.

[Download releases](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases) · [Report an issue](https://github.com/jichuo1/Bilibili_Innocent_Lab/issues) · [Telegram group](https://t.me/Bilibili_Innocent_Lab)

**English** · [简体中文](README.md)

</div>

---

<details>
<summary><b>Contents</b></summary>

- [Introduction](#introduction)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Cloning and Multi-User](#cloning-and-multi-user)
- [Recommended Testing Order](#recommended-testing-order)
- [Release Channels](#release-channels)
- [FAQ](#faq)
- [Building](#building)
- [Performance and Compatibility Design](#performance-and-compatibility-design)
- [Reporting Issues](#reporting-issues)
- [Privacy](#privacy)
- [Disclaimer](#disclaimer)
- [Additional Disclaimer and Risk Notice](#additional-disclaimer-and-risk-notice)
- [License](#license)
- [Credits](#credits)

</details>

---

## Introduction

Bilibili Innocent Lab is an Xposed/LSPosed module for the Android Bilibili client. Its main goal is to clean up interface content that hurts the experience while keeping the client's original visual structure and interaction habits as intact as possible, and to fill in interaction capabilities around comments, video descriptions, third-party extension compatibility and version adaptation.

This module is not a standalone Bilibili client and does not replace the official app. It is loaded into the target app process through runtime hooks and makes limited modifications to specific screens, data bindings and interaction entry points. You still use your own Bilibili account, player, recommendation system, comment section and video detail pages — once the module is enabled you can turn the features you want on or off.

The project emphasises "optional, low-intrusion, reversible". Almost every feature has its own switch, so you can enable only what you need; the module never requires all features to be enabled at once. For experimental features the settings UI gives an explicit warning, so you can decide whether to use them based on your current client version and device environment.

Because the Bilibili client keeps updating, internal class names, method signatures, layout structures and data models can all change. The project therefore includes runtime version adaptation, adaptation result caching and a manual re-adaptation mechanism, to minimise the chance that every feature breaks at once after a client update. No hook-based module can guarantee permanent compatibility with all future versions, so please read the version notes and known issues before use.

**To keep the project maintainable, please do not broadcast project-related content in any form — including but not limited to files, text, icons, links, images, videos and code — to non-specific audiences on public social media.**

**This is a non-profit project. It never charges for software or services, and it never collects or transmits your personal data without consent. If you suspect a file obtained from another channel contradicts these principles, always compare it against the Release hash of the matching version. If the hashes differ, the file may have been tampered with by a third party; this project cannot vouch for its safety, and if you know this and keep using it, you bear all resulting risk and consequences.**

> [!TIP]
> This module can be used with BiliRoaming. The module also provides compatible settings for higher host versions. If you use this module's "advanced settings", turn off the duplicated features in other modules so behaviour stays predictable. This module does not and will not provide anything related to server-side parsing; if you need that, look elsewhere. The version published in the LSPosed repository may lag behind. **If the host takes very long to cold start after enabling the module, or the module cannot communicate with the host, try turning off hiding of the host in HMA-OSS.**

**A note on ROOT permission**

Where possible, grant this module ROOT permission, which enables:

- Confirming and restarting Bilibili after saving settings.
- Unifying part of the local diagnostics in the Diagnostics Center.

> This note is updated with each version so ROOT usage always stays transparent. The project promises never to abuse ROOT permission in any way that harms the host device. See the latest open-source code for details.

**A note on root-free NPatch support**

The module now offers experimental root-free support for NPatch. On devices without Root/LSPosed you can load the module into a locally patched Bilibili client through NPatch Manager. This is an independent optional compatibility path and does not change the existing Root/LSPosed installation or usage. It currently requires Android 9 or later, NPatch Manager 1.0.7 or later, and local/manager mode: add and enable this module in NPatch Manager first, then turn on "Root-free support (NPatch, experimental)" in the module settings.

Once enabled, the module synchronises the currently supported feature configuration to NPatch, and the patched Bilibili process loads the corresponding hooks. After enabling, disabling or changing these settings you must manually force-stop and reopen Bilibili. The target process installs module features only after authorisation and full configuration validation pass, and the module UI shows "activated" only after it receives and verifies the host receipt.

> NPatch support is still experimental. Its real-world stability is affected by the Android version, ROM, NPatch Manager version, Bilibili version and other injected modules, and it should not be treated as a complete replacement for every Root/LSPosed scenario. Back up your configuration first and keep the ability to restore the unmodified app.

---

## Features

### AI task assistant (experimental)

The optional assistant accepts natural-language goals and can search Bilibili, page through a bounded
result set, inspect video details and navigate to known candidates. It is off by default; restart
Bilibili after enabling its host interface.

Configure up to eight API sources, explicitly select the sources allowed to receive the task, and run
the tool/decision/vision capability test. Tests make real API requests and use quota. Both chat and
JEV/Decisions endpoints participate. Leave the main-source field blank for automatic routing. A fixed
main source is tried first; another main source takes over only with explicit fallback enabled.
Selected, verified vision and decision helpers can cooperate independently of the main source.
Ordinary chat models without native tool support can plan after a separate text-command/receipt
challenge. Instructions and execution results use normal dialog, without tools, tool-role messages or
JSON-mode API requirements. Only a complete validated single-action packet can execute; arbitrary
prose never becomes a guessed command. At most one format correction shares the original deadline.

Optional screenshots require separate consent and a successful random-image challenge. Automatic
routing can use a tool planner, a vision-capable JEV, or an ordinary vision model as a text-only JEV's
eyes. Only selected sources with real vision proof receive images; images never enter planner history.
Enable the module service through the assistant accessibility entry for general Bilibili interface
actions: observed-control clicks, scrolling, ordinary text input and Back. Unavailable or unknown
service status does not block the original search tools. Each action needs a fresh observation;
protected account, identity and financial operations remain blocked independently of model output.
The original business mode retains touch/key takeover and the host stop button.
Duration in seconds and tool steps are independently configurable, defaulting to
120 seconds and 12 steps; zero means unlimited. Individual requests and renewable host leases remain
bounded, and repeated operations with no new evidence stop. Tasks are not resumed after process
death. Bounded conversation rounds, historical summaries and task-local detail/vision caches preserve
observation time without retaining images or replacing current authorization. General screenshots use
Android 14's host-window screenshot API and exclude other apps, keyboards and module status windows.
Older Android versions use accessibility controls; original search/video PixelCopy remains available
in business mode. Secure windows, protected inputs and incomplete inspections cannot be captured.
Visual taps need current consent, verified vision support, a recent matching snapshot and a
verifiable ordinary target. Completely opaque regions, shell and cross-app control are unavailable.

Android 16 and later use system Live Update notifications with current status, log and stop actions.
Promotion to a status chip or vendor island depends on the OS and user notification settings;
unsupported promotion keeps a normal task notification and never creates an overlay on these versions.
Earlier Android versions can use an optional non-focusable status island with overlay permission.
Its module icon, animated status, tips and read-only logs do not take host focus. Cutout information
is best-effort and does not guarantee placement around a physical camera.
General mode waits while module logs are open, then requires a new observation on return to Bilibili
without replaying the previous action. Other-app navigation or locking stops operation. Original
business-mode activity takeover rules remain in place.
Private logs store only stages, source numbers, roles, timing, outcomes, token counts and cache flags,
never goals, keys, images, reasoning or raw replies. Four tasks, 256 events per task and a 64 KiB total
budget bound retention, with visible omission counts. Interrupted tasks are recorded without replay.
The small status view refreshes at most 20 fps; screen-off, lock, permission revocation and task closure
remove its window and animation.

Search RPC results are distinguished from visible results. Activity launch arguments do not prove
playback, and publisher verification does not by itself establish an official original source. Retest
after changing an endpoint, model or key; the module uses capability proofs for 24 hours. Existing
semantic-source credentials retain their protected host-configuration delivery; the Agent action
protocol does not additionally transmit credentials. Live API, device/framework and rollout-specific
acceptance remain separate from APK structure and JVM verification.
Planner, vision and decision roles have independent health/cooldown state. Vision failures retain
structured search and detail paths. Decisions use the actually verified choice/noul/score formats;
compatibility retries require an explicit schema rejection and share a request deadline. With only
decision sources, actions come from a predefined search/pagination/details/screen menu, with no
invented parameters or video opening/playback. Complex goals should also select a tool-capable model.
Unlimited budgets do not extend the 24-hour capability proof or guarantee Android process survival.
Task input uses a soft budget with six recent complete tool rounds and up to eight deduplicated historical
records. The original goal, latest complete tool round, candidate identities and verification/unknown
states remain available; history never grants current navigation authority. Short-lived auxiliary results
can reuse identical evidence or the same image/page. Changed images, pages, evidence, sources or expired
proof still require fresh checks. Planner actions and unknown visual conclusions are never cached.
Auxiliary provider usage reaches execution logs; cache hits do not repeat previous usage as new consumption.
Output budgets and image challenges stay unchanged for reasoning-model and OCR compatibility. Savings
come from smaller repeated inputs, fewer auxiliary requests and fewer explicitly rejected compatibility
attempts. Local byte reductions are not provider-billed token counts or monetary savings.

The module uses independent switches so you can enable only the parts you need. Settings are organised into Favorites, Cleanup, Enhancements, Beautify, and Module pages; favorites and search lead to the original controls. Free copy lives in Enhancements, while visual configuration lives in Beautify. Existing values and behaviour remain consistent; host features usually require restarting Bilibili. If the current client has no reliable adaptation entry point, the feature leaves the host's behaviour intact.

| UI entry | Contents |
| --- | --- |
| Favorites | Saved feature entries using the original values, permissions, and listeners |
| Cleanup | Content, advertisement, component, and prompt filters; shared rules keep a single entry |
| Enhancements | Comment / description copy, reading and browsing interactions, playback, and full number display |
| Beautify | Module and host visuals, materials, backgrounds, and related appearance options |
| Module | Activation, diagnostics, updates, module management, experimental compatibility, and the task assistant |

The sections below describe capabilities by feature topic. Developer-facing category and architecture records are maintained locally and are not part of the public distribution.

### 🏠 Home and navigation

#### Home top bar

| Feature | Description |
| --- | --- |
| Hide the home game centre | Hides the game centre entry at the top of the home page; search, scan and other entries are unaffected |
| Hide search suggestions | Clears the default suggestions shown automatically inside the search box; opening the search page, typing keywords and searching normally are unaffected |

#### Home recommendations

| Feature | Description |
| --- | --- |
| Hide promoted live cards on the Live tab | Removes the top promoted live carousel without leaving a gap; preserves followed streamers and the normal live list. Off by default; restart Bilibili after changing it |
| Hide the home large-card carousel | Handles the carousel cards that take up a lot of space at the top of the home page or in the recommendation area, completing the carousel cleanup in environments where it previously failed |
| Filter recommended ads | Filters recognised ads before the home recommendation data reaches the UI; ordinary video recommendations are unaffected |
| Filter recommended image-text posts | Filters image-text cards in the home recommendation feed by adapted data type |
| Filter game promotions | Filters game promotion content in the home recommendation feed that can be identified unambiguously |
| Open portrait videos in the detail page | Opening a portrait video from the home page prefers the normal video detail page instead of the automatic portrait continuous-play screen; opening from other pages is unaffected |

#### Custom home structure

| Feature | Description |
| --- | --- |
| Custom home tabs | Enter the home tabs to hide by name; multiple rules can be separated by commas, full-width commas, semicolons or newlines. At least one tab is always kept, so the launch page and indexing do not break |
| Custom home components | Enter hide rules by component name or known identifier, to remove home sections you do not need |
| Custom bottom bar | Hide bottom navigation entries by name; only adapted visual bindings are handled, the host's underlying page data is not deleted, and at least one usable entry is always kept |
| Recommended video duration range | Set a minimum and a maximum video duration separately; videos below the minimum or above the maximum are removed from the home recommendations and from the recommendations at the bottom of the video detail page. Leave either bound empty to leave that side unrestricted |

### 🔔 Dynamics, the "My" page and system prompts

#### Dynamics

| Feature | Description |
| --- | --- |
| Hide the "Local" tab | Removes "Local" from the Dynamics tabs; other tabs and their original order are unchanged |
| Hide the "Campus" tab | Removes "Campus" from the Dynamics tabs; other tabs and their original order are unchanged |
| Default to the "Video" tab | Opening Dynamics prefers the "Video" category; if the current version has no such tab or navigation entry, the host default page is kept |

#### Dynamics content filtering

| Feature | Description |
| --- | --- |
| Filter Dynamics by keyword | Hides a Dynamic when any keyword appears in its body, in the body of a reposted original Dynamic, or in the module text; each text segment is matched separately, so nothing is falsely matched across segment boundaries |
| Filter Dynamics by author | Each rule entry is one UID or username (usernames must match exactly); Dynamics from a matching author, including reposts, are hidden. Dynamics whose author cannot be identified are kept |
| Hide shopping and recommendation attachment cards | Hides Dynamics carrying a shopping card or an "UP recommendation" attachment card, judged by the attachment card type Bilibili delivers rather than by wording |
| Hide locked charging-exclusive Dynamics | Hides charging-exclusive Dynamics you do not have permission to view; unlocked ones and ones whose permission state cannot be read are kept |
| Hide the Dynamics topic bar | Removes the topic bar above the Dynamics list; the Dynamics content itself is unaffected |
| Hide live entries in the top UP bar | Removes UPs that are currently live from the avatar bar at the top of Dynamics and re-lays out the remaining entries so no gap is left |

> The combined page and the video page share the same criteria, so configuring once applies to both. Removing a card does not affect the pagination cursor; only the current page becomes shorter.

#### Search

| Feature | Description |
| --- | --- |
| Hide promoted search result cards | Removes commercial ad cards and special campaign cards from the combined search results; the order of ordinary results is unchanged |
| Filter search results by keyword | Hides a video result when its title contains any keyword |
| Filter search results by author | Hides video results from authors on the list |

> The latter two only apply to video cards; anime, live and article cards have different field structures, so nothing is guessed and they are left as they are.
> The suggestions inside the search box are handled by "Hide search suggestions" as well: once it is on, suggestions no longer appear on the search page either.

#### The "My" page

| Feature | Description |
| --- | --- |
| Hide the Premium membership card | Hides the Premium membership card on the "My" page without touching other menus or account information |
| Keep the Premium membership card position | Works together with "Hide the Premium membership card": the content is hidden while the original layout placeholder is kept, reducing layout jumps |
| Custom "My" page components | Enter hide rules by menu name, useful for trimming entries you do not need such as the creation centre, courses and the game centre |

#### Pages and prompts

| Feature | Description |
| --- | --- |
| Disable the client update prompt | Blocks the Bilibili client's own update reminder; it does not affect this module's Stable or Preview update checks |
| Disable the teen mode prompt | Hides the teen mode notice page without changing teen mode's enabled state, restriction rules or system settings |
| Show full numbers | Counts such as views and likes prefer the full value instead of abbreviations like "wan" and "yi"; only adapted number-formatting entry points are handled |
| Floating video cards | Keeps the existing rounded recommendation covers, subtle shadows, and grid spacing. The Following feed uses a shared Lumen palette, complete static dynamic cards, an All/Video sliding selector, and compact frequent contacts while clearing the existing floating dock. Recommendation corners remain configurable from 0–40dp; dynamic cards use the 16dp preset. Available in advanced enhancement settings, off by default, requires restarting Bilibili. |
| Show AV numbers instead of BV numbers | Bilibili shows either an AV number or a BV number at the same place; enabling this always shows the AV number. BV links in the body are still recognised and clickable |

#### Sharing

| Feature | Description |
| --- | --- |
| Clean up share links | Removes tracking parameters from Bilibili links in the share panel link and share text, keeping only the page number and timestamp. **b23.tv short links are not expanded**, so no network request is made |
| Convert mini-program cards to plain links | When sharing to WeChat or QQ a plain link card is sent instead; when the title only contains the app name, the real video title is filled back in and no attribution text is appended |

#### Launch and system

| Feature | Description |
| --- | --- |
| Splash screen follows dark mode | The splash background is black in dark mode and white in light mode, so switching from a dark UI no longer flashes white; only the background is changed, not the splash image or logo |
| Use system media control notification | Enables the background playback system media-style notification that Bilibili ships but has switched off in its gradual rollout |
| Open external links in the system browser | Web pages outside Bilibili no longer open in the built-in browser. Bilibili domains plus payment and UnionPay gateways still stay inside the app; when no browser is available the original behaviour is kept so the client never errors |

### ▶️ Video detail, player and content feeds

#### Video detail page cleanup

| Feature | Description |
| --- | --- |
| Hide game ads in the video mention area | Filters recognised game promotion cards in areas such as "video mentions" under videos with game tags; normal video information, tags and detail content are kept |
| Hide UP shopping recommendations | Hides recognised product promotion areas such as "products shared by the UP" on the video detail page; ordinary descriptions, collections, tags and comments are unaffected |
| Filter related recommendations | Filters commercial promotions, game recommendations, live recommendations, course recommendations and special recommendations separately, judged by the explicit types in the host's public data |

#### Player

| Feature | Description |
| --- | --- |
| Hide ads on the video pause screen | Handles promotional content that appears after pausing some videos; the actual effect depends on the client version, player type, account experiment group and ad delivery policy |
| Hide the portrait switch button | Hides the "enter look-around" button in the player; fullscreen, playback controls and other controls stay available |
| Hide player interactive overlays | Blocks the vote card, follow prompt, triple-action contract card and command danmaku inside the player; progress chapter points are unaffected. Off by default, takes effect after restarting Bilibili |
| Block automatic activity half-sheets on the film and TV page | Blocks the Premium promotion and similar activity half-sheets that expand automatically below the player on movie, TV series and anime playback pages; deliberate activity links, purchase entries and normal playback permission checks are kept. Off by default, takes effect after restarting Bilibili |
| Transparent player status bar | Applies the transparent status bar style only on the ordinary video detail page; no other Activity or window is modified globally |
| Default playback quality | Follow Bilibili, or prefer 360P / 480P / 720P / 720P60 / 1080P / 1080P high bitrate / 1080P60 / 4K / 8K; it only changes the initial quality request when playback starts and does not unlock Premium or paid quality — the client still adjusts automatically when the source, account, network or server does not support the requested quality |
| Playback speed menu options | In Advanced settings → Player, enter 1–12 speeds from 0.1× to 8× with up to two decimal places, separated by commas or spaces. Options are deduplicated and sorted highest first. Switching between this feature and Default playback speed shows a conflict prompt; long-press speed remains independent. Follow Bilibili restores the original menu. Restart Bilibili after changes |

#### Danmaku cleanup

| Feature | Description |
| --- | --- |
| Filter danmaku by weight | The server assigns each danmaku a weight from 1 to 10 (higher is better); after setting a minimum kept weight, danmaku below it are removed before playback. When Bilibili does not deliver weights the whole danmaku set is kept as is, so danmaku are never wiped |
| Block Premium gradient-coloured danmaku | Removes the gradient colouring attached to Premium danmaku; the danmaku themselves still show in ordinary colours |

> Danmaku cleanup works on the danmaku content stream and is a separate path from "Hide player interactive overlays" above; the two can be enabled independently. Both are off by default and take effect after restarting Bilibili.

#### Live rooms

| Feature | Description |
| --- | --- |
| Disable swiping to switch live rooms | Swiping up or down in a live room no longer switches to another room; other gestures and playback controls are unchanged |
| Double tap to pause instead of like | Double tapping the live video pauses or resumes playback instead; when the playback state cannot be switched, the original like still happens |

#### Portrait video feed

The portrait continuous-play screen can filter the following categories independently:

Ads · Live · Game promotions · Anime and film · Courses · Short dramas · Shopping · Movies · Documentaries · TV series · Variety shows · Music

Every category is judged precisely from the public structured type in the adapted data object or from the presence of a business object; anything that does not match unambiguously passes through, and ordinary portrait videos keep their original order and playback behaviour.

### 💬 Comment section cleanup and content filtering

#### Comment UI cleanup

| Feature | Description |
| --- | --- |
| Disable keyword search links in comments | Keywords in comments no longer jump to search; comment text, emoji and normal link structures stay as close to the original as possible |
| Hide the empty comment section prompt | Hides the one-tap send prompt in the "no comments yet" state without closing the comment section entry |
| Hide comment poll widgets | Hides polls, side-picking and similar widgets in comments; ordinary likes, replies and comment bodies are unaffected |
| Hide the comment follow button | Hides the follow button in the comment section while keeping comment content, user badges and other interactions |
| Disable comment experience feedback | Hides comment experience feedback, questionnaires and other QoE content |
| Hide comment section promotions | Filters recognised promotional content in the comment section data without modifying ordinary comment and reply bodies |
| Hide comment reply buttons | Off by default; hides only the Reply control in comment action bars, preserving the N replies entry, input bar and three-dot menu. Restart Bilibili to apply |
| Disable tap-to-reply on comments | Briefly tapping a comment body or card no longer opens the reply editor directly; the explicit reply button, bottom input bar, three-dot menu and long-press free copy still work |

#### Comment content filtering

| Feature | Description |
| --- | --- |
| Filter comments by keyword | Up to 64 keywords can be configured, separated by commas, full-width commas, semicolons or newlines; a comment or reply is hidden when its body contains any keyword, and letter case is ignored when matching |
| Filter comments by user level | After setting a minimum kept level, comments and replies below it are hidden; when a comment's user level cannot be read reliably the comment is kept, to avoid deleting anything by mistake |
| Hide comments that only contain an @ | Hides a comment whose body contains nothing but an @ mention; when the mention list cannot be read the comment is kept |
| Filter comments by author | Each rule entry is one UID or username (usernames must match exactly); comments and replies from a matching author are hidden. When the author cannot be identified the comment is kept |

> The four criteria share the same comment list read boundary, so enabling one more does not attach another hook. Filtering covers adapted top-level comments, pinned comments and nested reply lists, reading only the information needed to decide; when nothing matches, the host's original list is kept, and a filtered copy is created only when something matches. It is independent of free copy, and the two can be enabled or disabled separately.

#### Comment layout

Classic comments can be restored under Enhancement → Advanced enhancement settings → Comment reading and interaction. When a comment has replies but no preview, the option fetches up to three replies through Bilibili’s detail API and shows them below the parent comment. Tap to view more replies. Only the first detail page is requested, with at most four concurrent requests and a 2.5-second wait budget for the entire comment page. Timeouts or failures preserve the original reply entry. Fetching may increase loading time. The option is off by default and requires restarting Bilibili.

### 📋 Free copy for comments and descriptions

#### Free copy in the comment section

Once enabled, long-pressing a comment body pops up a copy bubble with selectable text next to the content. Keep long-pressing and drag the Android system selection handles to select the fragment you need, then copy it through the system floating toolbar — no need to copy the whole comment.

- **Coverage**: ordinary comments, long comments, expanded content and some reply lists.
- **Full original text**: the complete original text is read from the comment data where possible, to avoid ending up with only the abbreviated text shown while collapsed, and original line breaks are preserved.
- **Emoji mapping**: Bilibili's own emoji are mapped to readable text where possible, reducing blank or meaningless placeholders in the copied result.
- **List recycling adaptation**: batch binding is deferred while scrolling, and comments near the current viewport are processed in batches once the list settles; page references are held weakly, and tasks that could contend for the UI frame budget are suspended during a long press.
- **Protection of official interactions**: official interaction areas such as images, buttons, media containers and the three-dot menu are not taken over by the body long-press listener; after using free copy on a comment with images and closing the bubble, the comment image can still be tapped to open its preview as usual.

#### Free copy for video descriptions

Long-pressing the description on a video detail page gives you the same copy bubble to select part of the text. For expandable descriptions the module reads the full original text where possible, normalises line breaks, trims trailing whitespace and deals with placeholder content produced by icons or special spans, while keeping the paragraph structure of the body.

#### Copy bubble appearance

| Appearance | Description |
| --- | --- |
| Default dark | The default colour scheme; no configuration needed |
| Manual light | Manually switch to the light style |
| Auto follow | Follows the Bilibili light/dark theme; enabling it overrides the manual light setting. The theme result is decided and cached in advance, so that no reflection runs at the moment the long-press popup appears |

The bubble uses a transparent dialog to host the system selection handles and the floating toolbar; tapping outside the bubble plays the exit animation and closes it.

### 🌍 BiliRoaming version support extension

"BiliRoaming version support extension" works together with an installed and enabled BiliRoaming module. It is not a replacement for BiliRoaming and provides no accounts, parsing servers, access keys or network proxy services.

When a newer Bilibili client changes its internal analysis targets, an older BiliRoaming may fail while reading or generating hook information. Once the extension is enabled, this module tries to repair the known analysis-candidate problems and helps BiliRoaming rebuild a complete cache, so that its settings entry, page customisation and related hook capabilities keep working on adapted versions. The current compatibility handling includes:

- Re-analysing when the cache is damaged, expired or the client version changes;
- Reducing pointless repeated full analyses and cold-start waiting;
- Adding a BiliRoaming settings entry on the "My" page;
- Synchronising switch state through a cross-process Provider, a permission-protected broadcast and a local cache;
- Handling app visibility and background launch restrictions in some MIUI/HyperOS environments;
- Restoring the corresponding native behaviour once the feature is turned off, so that no compatibility state lingers.

> After enabling this feature you usually need to restart Bilibili twice, as the settings page instructs, so that cache rebuilding and the later fast-start path can complete in order. Real-world compatibility also depends on the Bilibili version, the BiliRoaming version and the LSPosed branch.

### 🧪 Experimental features and automatic version adaptation

Experimental features contains separately expandable "Appearance" and "Compatibility" menus. UI aesthetics, background, colour scheme and the original display settings live under Appearance; framework support and host adaptation options live under Compatibility.

#### Predictive back animation

On systems that support the predictive back gesture, going back from the module settings UI uses the system-provided back transition preview.

- **Android 14–15**: provided as an experimental switch that you can turn on or off yourself in the settings;
- **Android 16 and later**: the system forces this capability on for this module and it can no longer be disabled at runtime, so the switch is no longer shown in the settings (a saved preference is still kept, so that old backups can be imported).

Some vendor systems may modify, restrict or disable the corresponding animation on their own.

#### Automatic version adaptation

After a Bilibili update, obfuscated class names, method signatures and page structures can change. The module locates the hook points each feature needs by combining class structure, field types, method parameters and binding characteristics, and records the client version, APK fingerprint and adaptation rule version. A successful adaptation is cached, and later launches prefer the fast path while the environment is unchanged, reducing repeated scans.

Each feature has its own independent installation and diagnostics boundary; automatic adaptation can only cover client versions whose structure is still similar, so if the host is heavily refactored some capabilities may still have to wait for a module update.

**If features stop working after upgrading Bilibili**:

1. Open the module settings and go to "Experimental features → Compatibility";
2. Choose "Re-adapt to the current version" and confirm clearing the records;
3. Restart Bilibili; the module relocates the feature entry points for the current version on the next launch.

### ⚙️ Module settings and maintenance tools

| Feature | Description |
| --- | --- |
| Activation status | The module home page shows whether Xposed/LSPosed has activated it and, where available, the framework name and API version; if it shows as not activated, check the module switch, whether the scope includes Bilibili, and whether the target process was ended and restarted after the change |
| Hide the launcher icon | Hides the module's launcher icon; the settings can still be reached from the LSPosed manager afterwards. If LSPosed has "force show launcher icon" enabled, turn that option off first |
| One-tap restart of Bilibili | After saving settings you can end and restart the target app from inside the module; Root, shell permission or vendor background restrictions can make this fail, in which case force-stop manually and open it again |
| Log settings | Log capture can be enabled independently and comes in "concise" and "full" levels: concise keeps notable errors and key runtime problems, while full adds hook registration, feature hits and interception information. Before publishing logs, check them yourself for client version, class names and runtime state |
| Update check | Supports both the Stable and Preview channels, checked infrequently and separately, with a fallback mirror when the network fails; Stable only receives official Releases, while Preview also compares Alpha pre-releases that follow the semantic version format and warns about the risk. The module never installs an APK silently in the background — whether to download, install or use a test build is always up to you |

## Requirements

| Item | Requirement |
| --- | --- |
| Android version | Android 8.1 (API 27) or later |
| Root / hook environment | Magisk + LSPosed recommended |
| Target app | The official Android Bilibili client, package `tv.danmaku.bili` |
| Module framework | An Xposed / LSPosed compatible environment |
| Cloning | Only system-level multi-user / app twin / work profile setups **whose package name is still `tv.danmaku.bili`** are supported; renamed clones and VirtualApp-style container cloning are not, see [Cloning and Multi-User](#cloning-and-multi-user) |
| Verified clients | The main version path from 8.84.0 to 9.11.0 |
| BiliRoaming extension | Requires a compatible BiliRoaming version installed and enabled separately |
| Predictive back | A switch is provided on Android 14–15; on Android 16 and later the system forces it on and the switch is no longer shown in the settings |

> "Verified" only means the project has been developed and tested on the corresponding version or on structurally similar builds; it does not mean every device, system theme, account experiment group and minor client version will produce exactly the same result. In theory clients between the verified version numbers also work, but complete feature coverage is not guaranteed; if a feature fails and you want the project to adapt to it, follow the process in [Reporting Issues](#reporting-issues).

---

## Installation

**Preparation**

1. Download the APK from [GitHub Releases](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases);
2. Install or overwrite-install the module on your Android device.

**Enable it in LSPosed**

3. Open the LSPosed manager;
4. Enable `Bilibili Innocent Lab`;
5. Add the official Bilibili client `tv.danmaku.bili` to the module scope;
6. Force-stop Bilibili, then start it again.

**Verify and configure**

7. Open the module settings and confirm the page shows "module activated";
8. Enable the options you need under Cleanup, Enhancements or Experimental features;
9. If a setting does not take effect immediately, use "restart Bilibili" inside the module, or force-stop the target app manually.

**BiliRoaming extension**

10. For the BiliRoaming version support extension, complete the two restarts as the page instructs.

> After overwrite-installing the module you normally do not need to clear Bilibili's data. Unless the release notes explicitly ask for it, do not casually wipe client data, module configuration or the BiliRoaming cache.

---

## Cloning and Multi-User

The module works on Bilibili's **package name**. So whether "cloning" works depends on whether the cloned client keeps the package name `tv.danmaku.bili`.

| Cloning form | Package name after cloning | Supported |
| --- | --- | --- |
| System multi-user, app twin, work profile | Still `tv.danmaku.bili` | ✅ Supported, but it must be enabled separately **under that user** following the steps below |
| Renamed clones (some root-free multi-instance and package-renaming tools) | Changed to another package name | ❌ Not supported |
| VirtualApp-style container cloning (running inside a cloning app) | The container app's own package name | ❌ Not supported |

**Why renamed clones do not work**: the module's Xposed scope is fixed (`staticScope`) and only declares the official package name, so no other package can be added manually in the manager; even if the framework agreed to inject, the host resources and cache directories the module resolves through the official package name would all miss. This is a deliberate trade-off — turning host identity into a runtime variable would drag in the authorisation chain, Provider caller validation and cross-process broadcasts, and the benefit would not reach most users.

**Enabling under a twin app or another user**

The framework delivers the libxposed service **per Android user**, so enabling it in the primary user does not automatically cover a twin user:

1. Install the module app under the user that runs the twin client as well;
2. In the framework manager, switch to that user, enable this module for it and make sure the scope includes Bilibili;
3. Open the module settings once under that user so the configuration is published;
4. Force-stop and restart Bilibili under that user.

If the module settings page shows "there is no framework service under the current Android user N", step 2 was not completed under that user; if it shows "the module is in user A while Bilibili is in user B", the module and the client are installed under different users and the configuration cannot be delivered. The "framework service" item in the Diagnostics Center gives the same evidence.

---

## Recommended Testing Order

On a first install, verifying in the following order is recommended:

1. Confirm the module is activated;
2. Enable only one UI cleanup feature;
3. Restart Bilibili and check the detail page or the home page;
4. Test free copy in the comment section;
5. Test free copy for video descriptions;
6. Choose the manual light bubble or auto follow according to your theme;
7. Once the basic features are stable, try the experimental features;
8. Enable the BiliRoaming extension only if BiliRoaming is installed and you actually need the compatibility.

> Enabling features one at a time makes it easier to tell where a problem comes from, and avoids the situation where several modules modify the same page at once and the conflict is hard to pin down.

---

## Release Channels

| | Stable | Alpha pre-release |
| --- | --- | --- |
| Purpose | Everyday use | Early validation of new features, adaptation to new clients, and testing of compatibility fixes |
| Update check | The built-in check receives official Releases only by default | Also compares official releases against Alpha pre-releases that follow the semantic version format, and warns about the pre-release risk |
| Stability | Prioritises known-good features, build completeness and basic compatibility | May contain changes that have not yet been validated on a wide range of devices |
| Recommendation | Recommended for everyday use | Not recommended for long-term use on devices where you cannot export logs yourself or restore an older version |

**Release discipline**: an Alpha is never published automatically just because of a local build; it should be uploaded through the corresponding workflow only when the developer is confident the build is worth testing, so the Releases page is not flooded with unusable intermediate builds.

> **Version numbering**: an Alpha always uses the next patch version after the current stable one — while the stable version is `1.0.6`, Alphas use `1.0.7-alpha.N`; once `1.0.7` is released, they move on to `1.0.8-alpha.N`.

---

## FAQ

| Question | How to troubleshoot |
| --- | --- |
| The module shows as not activated | Make sure LSPosed has this module enabled and the scope includes the official Bilibili client; after changing the scope, restart the target app and reboot the device if needed |
| The libxposed service is not detected in a twin app | The framework delivers the service per Android user: install the module and enable it for the user running the twin client in the manager as well, then open the module settings once. Renamed clones and VirtualApp-style containers are out of scope, see [Cloning and Multi-User](#cloning-and-multi-user) |
| A switch is on but the feature does not work | Force-stop and restart Bilibili first; right after a client upgrade try "re-adapt to the current version"; the BiliRoaming extension may need two launches to rebuild its cache |
| Long-pressing a comment does not show the free copy bubble | Make sure the comment free copy switch is on and the current page is an adapted official comment section; if only a few comments fail, note the comment type, whether it has images, whether it is in a reply list, and the client version |
| Images cannot be opened after using free copy | The current version specifically protects the media container of comments with images; check that you are on the latest build and fully restart the Bilibili process. If it still reproduces, describe the order of operations and the delay between closing the bubble and tapping the image |
| Text cannot be selected or copied inside the bubble | Make sure no other module also hooks the system clipboard, the text selection toolbar or PopupWindow; some vendor systems deeply customise the text selection component — if it crashes, provide the system version and the crash log |
| The home carousel still appears | The home layout can be affected by account experiment groups; provide the client version, a screenshot of the home layout and full logs, rather than only describing the feature as "not working" |
| The BiliRoaming extension is enabled but problems persist | Provide the versions of Bilibili, BiliRoaming, this module, LSPosed (including the branch) and Android/ROM, whether the two restarts were completed, and the relevant LSPosed logs for the target main process |
| What to do about conflicts with other modules | When several modules modify the comment section, video detail page, clipboard, theme attributes or the BiliRoaming cache at the same time, execution-order conflicts can occur; temporarily disable the other modules and reproduce with only this one, then restore them one by one once this module alone behaves correctly |

## Building

The repository root and the Gradle project directory are not the same. The Gradle project lives in:

```text
Bilibili_Innocent_Lab/
```

**Build a debug APK**

Windows PowerShell:

```powershell
cd Bilibili_Innocent_Lab
.\gradlew.bat assembleDebug --console=plain --no-daemon
```

Linux / macOS:

```bash
cd Bilibili_Innocent_Lab
./gradlew assembleDebug --console=plain --no-daemon
```

**Run the JVM unit tests**

```powershell
.\gradlew.bat testDebugUnitTest --console=plain --no-daemon
```

**Run lint**

Use the fast check during local iteration:

```powershell
.\gradlew.bat :app:lintFast --console=plain --no-daemon
```

The fast check skips only an explicit list of 32 BetterAndroid / KavaRef API replacement suggestions. Android correctness, permission, API compatibility, and Hikage checks remain enabled for production sources. Test sources are verified by compilation and tests, and are excluded from Lint. It does not replace the full gate. Before committing and in CI, run:

```powershell
.\gradlew.bat :app:lintDebug --console=plain --no-daemon
```

Fast reports are saved under `app/build/reports/lint/fast/`, and full reports under `app/build/reports/lint/full/`; both HTML files are named `lint-results-debug.html`. Use full task names. Only `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest` may accompany `lintFast`; run other tasks and full gates separately. On Linux / macOS, use `./gradlew` with the same tasks.

Use `--no-daemon` consistently while retaining dependency and task caches. Avoid routine `clean`; clean the task's isolated build after Hikage BOM or plugin signature changes. Add `--offline` when dependencies are cached.

**Verifying the artifact**

The debug APK is written to:

```text
Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk
```

Confirm that Gradle completed successfully and use the APK from that build. `UP-TO-DATE` and `FROM-CACHE` are valid incremental results; APK modification time alone does not prove whether an artifact includes the current inputs. After installation, compare the SHA-256 of the local APK with the installed `base.apk` to confirm that the intended artifact was installed.

**Further documentation**

- Architecture and regression records are maintained locally; developer work starts from the local `AGENTS.md`.

---

## Performance and Compatibility Design

This module contains many hooks running inside the target app process, so the design avoids putting complex logic into high-frequency callbacks:

**Hot paths and scheduling**

- Fast-return conditions are set on scrolling, touch and text-binding paths;
- Comment binding uses idle scheduling and batching.

**Caching and lifecycle**

- Methods and fields obtained through reflection are cached where possible;
- Pages, dialogs and comment views are held through weak references;
- Asynchronous tasks hold only the Application Context;
- Adaptation results for a new version are written to a cache, so that scanning is not repeated on every launch.

**Compatibility and exemptions**

- Cross-process switch reads have several fallback levels;
- Identity or origin exemptions are set for the module's own dialogs, the system selection handles and host media containers;
- Explicit configuration is used for window, status bar and theme attributes that differ markedly between ROMs.

The project does not take over every system API unconditionally in pursuit of "intercepting more content". For entry points with a wide blast radius such as PopupWindow, Dialog, the clipboard, broadcasts and touch events, it first decides whether the target belongs to the module based on object identity, call origin and gesture lifecycle, avoiding interference with image previews, system text selection and other normal host features.

---

## Reporting Issues

Before filing an issue, please carry out the following checks where possible:

1. Use the latest stable version, or state which Alpha version you are running;
2. Confirm the problem still reproduces with only this module enabled;
3. Note the versions of Bilibili, Android, LSPosed and the module;
4. Describe the state of the specific feature switches;
5. Give the full sequence of steps from entering the page to the problem appearing;
6. Attach screenshots, screen recordings or LSPosed logs where necessary;
7. For crashes, provide the full stack trace from the crash buffer where possible;
8. Do not publicly upload accounts, cookies, access keys or other personal sensitive data.

> Clear, repeatable steps are usually much easier to act on than a bare "it does not work".

---

## Privacy

The module's logic runs mainly on the local device and inside the target app process. Runtime logs are written to the LSPosed log system, and the update check fetches GitHub Release information. The module does not log in to Bilibili on your behalf and never asks you to send account credentials to this project.

Before exporting or sharing logs, you should still check them yourself for video links, device information, app versions or anything else you would rather not make public.

---

## Disclaimer

This project is an unofficial, non-commercial personal study and research project, and has no affiliation, authorisation, cooperation or endorsement relationship with Bilibili or its affiliates.

Using an Xposed/LSPosed module can change how the target app behaves and may be affected by client updates, system security policy, vendor ROMs, account experiment groups or other modules. You should assess the risk yourself and take responsibility for installation, enabling, data backup and your device environment.

This project provides no content resources, takes no part in account trading, offers no access credentials, and does not guarantee the availability of third-party extension services. Use it in compliance with the laws of your jurisdiction, the target platform's rules and the applicable open-source licences.

## Additional Disclaimer and Risk Notice

> **Important: please read this notice in full before downloading, installing, compiling, distributing or using this project.**  
> This notice explains the nature of the project, the risks of use and the boundaries of responsibility, and does not constitute formal legal advice for any individual, organisation or jurisdiction. If you plan to use this project for public distribution, organisational operation, commercial activity or other scenarios with higher legal risk, consult a suitably qualified professional. If you disagree with any part of this notice, stop downloading, installing, compiling, distributing or using this project immediately.

### 1. Nature of the project and unofficial status

Bilibili Innocent Lab is an unofficial, non-commercial open-source research project maintained independently by community developers, mainly for studying, researching and discussing technologies such as Android, Xposed/LSPosed, runtime hooking, UI interaction and software compatibility.

This project has no affiliation, authorisation, delegation, cooperation, sponsorship, recognition or endorsement relationship with Bilibili, its operating entity, affiliates or partners, and does not represent the position of any third-party platform, framework or extension project.

References to "哔哩哔哩", "Bilibili", related app package names, UI names, product names, trademarks, logos and other proprietary names in the project name, documentation or code are used only to describe the compatibility target, technical background or scope of features. The trademark rights, copyrights and other legitimate rights in them belong to their respective holders. Such descriptive use does not mean this project has been authorised by those rights holders.

### 2. Scope of features

This project provides only module code that runs on the user's local device. It is not a standalone video client, and it does not provide or operate any audio or video content, account services, membership services, network proxies, parsing services, access credentials, cookies, access keys, copyrighted content, paid resources or third-party platform data.

This project grants users no additional rights over any third-party content, software, interface, account or service. The text selection, UI cleanup, interaction enhancement, version adaptation or third-party extension compatibility the module provides does not mean the user has obtained permission to copy, repost, publish, distribute, modify, compile or commercially use the related content.

Free copy only changes how text is selected in the local UI. When you copy, save, quote, repost or distribute comments, descriptions and other text, you must confirm for yourself that your conduct complies with copyright, privacy, personal data protection, platform rules and other applicable requirements. The fact that the module can perform a technical operation does not mean that operation is legally authorised in every scenario.

### 3. Prohibited uses

This project is not intended to circumvent payment mechanisms, crack membership benefits, bypass digital rights protection, break access controls, obtain unauthorised data, disrupt a platform's normal operation, carry out network attacks, spread illegal content or infringe third parties' lawful rights.

No one may use this project for activities including but not limited to:

- Accessing, scraping, collecting, selling or distributing third-party data without authorisation;
- Bypassing paid services, copyright protection, account permissions or security verification;
- Obtaining, sharing or abusing other people's accounts, cookies, tokens, access keys or similar credentials;
- Copying, reposting, downloading or distributing content at scale without lawful authorisation;
- Interfering with, attacking, defrauding or abusing a platform, server, client or other users;
- Repackaging the project into a version containing malicious code, privacy collection, ad injection or payment fraud;
- Any other conduct that violates the laws of your jurisdiction, the target platform's rules or third-party open-source licences.

Responsibility for consequences arising from a user changing the project's purpose, modifying the code, combining it with other tools or carrying out prohibited operations rests with the person who actually did so.

### 4. Device, system and account risks

Xposed, LSPosed, Magisk, Root, Zygisk and other runtime hook environments change the normal execution flow of Android and the target app, and may lower the system's security boundary or trigger unexpected behaviour. Even if the project has been tested on a particular device and client version, the same result cannot be guaranteed in other environments.

Potential risks include but are not limited to:

- The target app failing to start, crashing, hanging or behaving abnormally;
- System UI anomalies, soft reboots, boot loops or the device failing to boot normally;
- App configuration, caches, local files or other data being corrupted or lost;
- Changes in battery consumption, memory usage, heat or performance;
- Conflicts with other modules, themes, ROM customisations or security software;
- Your account having features restricted, triggering security verification, receiving warnings, being banned or other platform action;
- Some or all hooks breaking after a client update;
- Third-party extensions, dependencies or services misbehaving, becoming unmaintained or changing behaviour.

Users should be able to recover a device, disable the module, uninstall the APK, enter safe mode, restore a backup and read LSPosed logs. Back up important data before use and make sure you can bear the consequences of a failed test or an environment conflict.

The project maintainers cannot control a third-party platform's risk-control rules and make no commitment regarding account safety, account value, account benefits or the outcome of platform penalties.

### 5. Compatibility and availability

This project was developed against the target app's runtime structure at a particular time and version. The target app may change class names, method signatures, data structures, UI layouts, network protocols, experiment configuration and security policy at any time, so any feature of this project may partially fail, fail completely or create new compatibility problems after a client update.

The README, release notes, verified version lists, screenshots, logs and test results represent behaviour only at the corresponding test time and in the corresponding test environment, and are not a guarantee for other devices, systems, accounts, regions, network environments or future versions.

The project maintainers do not guarantee:

- That all features will work forever;
- That all devices and ROMs will run normally;
- That every client version can be adapted automatically;
- That the project is free of bugs, vulnerabilities, compatibility problems or security risks;
- That issues, feedback or compatibility requests will be handled;
- That fixes will be released within a particular timeframe;
- That releases, Alpha builds or the update check service will always be available;
- That third-party modules, dependencies or external services will remain compatible.

The project may modify, suspend, remove or stop maintaining some features at any time, and may stop publishing builds, archive the repository or end the whole project, with no promise of advance notice.

### 6. No warranty

To the maximum extent permitted by applicable law, this project and its related code, documentation, build artifacts, scripts, configuration and instructions are provided "as is" and "as available", without any express, implied or statutory warranty.

The project authors, maintainers, contributors and publishers make no warranty of merchantability, fitness for a particular purpose, accuracy, completeness, stability, continuity, security, non-infringement, freedom from errors, freedom from viruses or compatibility with a particular device.

No example, test record, performance figure, development note or user feedback should be understood as a promise, a warranty or a service level agreement. Users must verify for themselves whether the project suits their device, account and purpose.

### 7. Limitation of liability

To the maximum extent permitted by applicable law, the project authors, maintainers, contributors, publishers and collaborators are not liable for losses arising directly or indirectly from downloading, installing, compiling, modifying, distributing, enabling, disabling or using this project, including but not limited to:

- Data loss, file corruption, device failure or the cost of restoring a system;
- App anomalies, feature outages, business interruption or lost time;
- Account restrictions, bans, loss of benefits or platform penalties;
- Lost revenue, lost opportunities, lost goodwill or lost expected profit;
- Privacy leaks, exposed credentials or third-party claims;
- Losses caused by using unofficial builds, tampered versions or malicious repackaging;
- Losses caused by conflicts with third-party modules, frameworks, services, websites or dependencies;
- Any indirect, incidental, special, punitive, accidental or consequential loss.

The above limitation of liability does not apply to liability that applicable law does not allow to be excluded, limited or transferred. This notice does not attempt to disclaim statutory liability arising from intent, gross negligence, personal injury or other matters that cannot be disclaimed under law.

### 8. Third-party projects and external links

This project may use or mention LSPosed, Xposed, Magisk, YukiHookAPI, KavaRef, BiliRoaming, GitHub and other third-party software, frameworks, services or websites. Those projects are maintained independently by their respective rights holders and are subject to their own licences, privacy policies, terms of service and disclaimers.

The appearance of a third-party name, link or compatibility code in this project does not mean this project guarantees its safety, legality, stability or continued availability, nor that a cooperation or authorisation relationship exists between the two.

The risk of visiting external links, installing third-party components, enabling other modules or using external services is for users to judge and bear themselves.

### 9. Build artifacts and redistribution

Obtain source code and APKs only from this project's official GitHub repository and its Releases page, and verify the release origin, digital signature, file checksum and version information yourself.

The project author cannot control installers redistributed by third-party websites, file hosts, groups, forums or individuals. For builds from unofficial sources, modified builds, re-signed builds, malicious repackaged builds or builds bundled with other software, the maintainers give no guarantee of trustworthiness and accept no liability arising from them.

Anyone redistributing the project must comply with the repository's applicable open-source licence, keep the necessary copyright and source information, and state clearly that their build is not an official release. Impersonating the project author, describing a modified build as the official original, or using this project for payment fraud, malicious advertising, privacy collection or other conduct that harms users is prohibited.

### 10. Logs, privacy and issue reporting

Runtime logs may contain the app version, system version, device model, process name, class names, page state, video links or other environment information. Before publicly submitting an issue, log, screenshot or screen recording, users should check for and remove account information, cookies, tokens, access keys, private chats, comment identities, device serial numbers and other sensitive content.

Information users submit to GitHub, forums or other public channels may be viewed, reposted, indexed or stored long-term by third parties. The maintainers cannot guarantee that an external platform will completely delete information that has already been made public.

If submitted content contains obviously sensitive information, third-party private data or data unrelated to the issue, maintainers may hide, delete or decline to process it, but cannot guarantee to notice it before a leak occurs.

### 11. No ongoing support

This project is maintained by developers in their personal time and does not constitute a commercial service, a technical support contract, a hosting service or a service level agreement.

The project maintainers are under no obligation to:

- Reply to every issue;
- Provide dedicated adaptation for a particular device or version;
- Provide one-to-one remote assistance;
- Fix every bug or compatibility problem;
- Guarantee an update frequency;
- Keep historical versions available for download;
- Support modified builds;
- Recover a user's device, account or data.

Community help, development suggestions and replies are voluntary and should not be understood as a commitment to ongoing service.

### 12. Contacting rights holders and content handling

If any rights holder believes that code, documentation, names, screenshots, links or other content in this project infringes their lawful rights, they may submit an explanation through a GitHub issue or the repository's public contact details, providing the information necessary to identify the rights holder, the content concerned and the basis of the right.

The maintainers will look into it within reason and may take steps such as explaining, modifying, removing, restricting distribution or other appropriate measures. Acting on feedback does not mean the maintainers automatically admit infringement, illegality or liability.

### 13. Updates to this notice

This disclaimer may be updated as the project's features, release methods, dependencies and applicable rules change. The updated version applies to subsequent downloading, installation, compiling and use from the date it is published.

Users are responsible for checking the latest README, release notes and disclaimer before updating the module or using the project again. Continuing to use the updated project means the user is aware of the corresponding risk notice; if you disagree with the update, stop using it and uninstall the relevant version.

### 14. Severability and limits of legal effect

If part of this notice is held to be invalid, unenforceable or in conflict with applicable law, the remaining provisions continue to apply to the extent permitted by law. The provision concerned should be interpreted under applicable law in a way that comes as close as possible to the original purpose of the risk notice and the boundary of responsibility.

A maintainer's failure to exercise or assert a right immediately should not be interpreted as permanently waiving that right.

Note in particular that a disclaimer does not automatically exclude all liability. China's Civil Code sets clear boundaries for the duty to highlight and explain standard clauses, for limitations of liability and for situations where liability cannot be disclaimed under law, so this notice is always limited to "the maximum extent permitted by applicable law" and does not exclude any liability that cannot be excluded by law. [Civil Code of the People's Republic of China](https://tjca.miit.gov.cn/zwgk/zcwj/flfg/art/2020/art_20cf1a2e1b854924b5caa744c8045d1f.html), [Interpretation of the Supreme People's Court on the General Provisions of the Contract Part of the Civil Code](https://www.court.gov.cn/zixun/xiangqing/419382.html)

When copying, reposting, distributing or using third-party works, users must also comply with the rules on copyright and the right of communication through information networks. The technical text-selection capability this module provides does not equal permission from the content rights holder to copy or distribute. [Copyright Law of the People's Republic of China](https://www.npc.gov.cn/c2/c30834/202011/t20201119_308796.html)

### 15. Acknowledgement of use

Before downloading, installing, compiling, modifying, distributing or using this project, please confirm that you understand:

1. This is an unofficial community project;
2. A hook environment may affect the safety of your device, apps and account;
3. Features may stop working as the client updates;
4. Free copy does not mean you have permission to distribute content;
5. Third-party services and modules are the responsibility of their respective maintainers;
6. Unofficial builds may contain unverifiable modifications;
7. You should back up your data yourself and be able to restore your environment;
8. You are responsible for your own specific use of the project and its consequences;
9. The project offers no form of absolute safety, permanent compatibility or risk-free guarantee;
10. All limitations of liability are limited to the extent permitted by applicable law.

If you cannot accept these risks, do not install, enable or continue to use this project.

---

## License

Except for third-party content stated otherwise, the original source code of this project is licensed under the [GNU General Public License v3.0 only](LICENSE).

SPDX-License-Identifier: GPL-3.0-only

Third-party components, frameworks and code used by or compatible with the project follow their own licences.

## Credits

Thanks to the following projects and communities for the foundational capabilities and development references:

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
- [KavaRef](https://github.com/HighCapable/KavaRef)
- [Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine) (video card surfaces, Apache-2.0)
- [BBZQ](https://github.com/HSSkyBoy/BBZQ)
- The Xposed / LSPosed community
- The Android Open Source Project and related developer documentation
- Everyone who tested, reported compatibility problems and provided logs

If this project helps you, you are welcome to file reproducible problems, compatibility information or improvement suggestions through an issue.
