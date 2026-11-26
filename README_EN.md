<div align="center">

<img src="banner.svg" alt="Bilibili Innocent Lab" width="100%">

[![Stable](https://img.shields.io/github/v/release/jichuo1/Bilibili_Innocent_Lab?style=flat-square&color=FB7299&label=stable)](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases)
[![License](https://img.shields.io/github/license/jichuo1/Bilibili_Innocent_Lab?style=flat-square&color=00AEEC)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.1%2B-2d3a55?style=flat-square&labelColor=2d3a55)](#requirements)
[![Framework](https://img.shields.io/badge/Xposed-LSPosed-00AEEC?style=flat-square&labelColor=2d3a55)](#requirements)
[![Target](https://img.shields.io/badge/target-tv.danmaku.bili-FB7299?style=flat-square&labelColor=2d3a55)](https://www.bilibili.com)
[![Telegram](https://img.shields.io/badge/Telegram-group-26A5E4?style=flat-square&logo=telegram&labelColor=2d3a55)](https://t.me/Bilibili_Innocent_Lab)

### UI cleanup and enhancement module for the official Android Bilibili client

Clean up ads · Filter content · Free copy · Enhance playback · Visual polish<br>
Every feature has its own toggle and targets the official client `tv.danmaku.bili`

[Download releases](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases) · [Report an issue](https://github.com/jichuo1/Bilibili_Innocent_Lab/issues) · [Telegram group](https://t.me/Bilibili_Innocent_Lab)

<br>

**English** · [简体中文](README.md)

</div>

---

<details>
<summary><b>Contents</b></summary>

| Category | Sections |
| --- | --- |
| **Get started** | [Introduction](#introduction) · [Requirements](#requirements) · [Installation](#installation) · [ROOT permission](#a-note-on-root-permission) · [Recommended testing order](#recommended-testing-order) |
| **Root-free & cloning** | [Root-free support](#root-free-support) · [Cloning and multi-user](#cloning-and-multi-user) |
| **Features** | [Features](#features) · [Release channels](#release-channels) |
| **Help** | [FAQ](#faq) · [Reporting issues](#reporting-issues) |
| **Notices & developers** | [Notices and disclaimers](#notices-and-disclaimers) · [For developers](#for-developers) · [License and credits](#license-and-credits) |

</details>

---

## Introduction

Bilibili Innocent Lab is an open-source Xposed / LSPosed module for the official Android Bilibili client, supporting both Root (LSPosed) and Root-free (NPatch / LSPatch local patching) setups.

It keeps the official layout and navigation habits intact while clearing out ads and unwanted cards, letting you freely select fragments of comments, video descriptions and titles to copy, and adding player frame interpolation, audio rescue, floating glass UI options and logged-out/incognito browsing. Every feature has an independent switch (almost all off by default); turn a switch off and restart Bilibili to restore original behaviour, and export or restore your settings locally at any time.

### Capability overview

| Area | Highlights |
| --- | --- |
| 🧹 **Cleanup** | Ads, promotions and unwanted cards across Home, Dynamics, Search, Player and detail pages; splash ads; 12 category filters in the portrait feed |
| 🔎 **Filtering** | Keywords, uploaders, tags, duration, view count and publish time; offline AI comment scoring; optional BYO-API semantic filters |
| 📋 **Free copy** | Long-press comments, descriptions or titles for a selection bubble with system text handles |
| ▶️ **Player** | Preferred quality/codec, custom speed menu, buffering strategy, frame interpolation (QCOM / RIFE selectable), audio rescue, subtitle styling and SponsorBlock skipping |
| 🎨 **Appearance** | Floating glass top/bottom bars, custom page backgrounds, rounded card styles and dark-aware splash screen |
| 🔒 **Privacy** | Browse without login, incognito sessions, share-link tracking cleanup and plain link cards |
| 🧰 **Tools** | Reply context map, AI video insight and task assistant, local backup/restore, Diagnostics Center and remote-flag overrides |
| 📦 **Root-free** | Works via NPatch and JingMatrix LSPatch local (Manager) patching |

> [!IMPORTANT]
> Read [Notices and disclaimers](#notices-and-disclaimers) in full before downloading, installing, compiling, distributing or using this project. Hook-based modules may break after major client updates; check release notes before updating Bilibili.

---

## Getting started

### Requirements

| Item | Requirement |
| --- | --- |
| **Android version** | Android 8.1 (API 27) or later |
| **Environment** | Root + LSPosed recommended; [root-free loading](#root-free-support) is also supported |
| **Target app** | Official Android Bilibili client (`tv.danmaku.bili`) |
| **Adapted builds** | Verified across official releases **8.84.0 – 9.21.0**; minor client updates usually adapt automatically, while major refactors may require a module update |
| **Cloning / twins** | Supported only for system multi-user / app twin / work profiles that keep the package name `tv.danmaku.bili`; see [Cloning and multi-user](#cloning-and-multi-user) |
| **BiliRoaming extension** | Requires a compatible BiliRoaming APK installed and enabled separately |

### Installation

| Step | Action |
| :---: | --- |
| **1** | Download the APK from [GitHub Releases](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases) and install or overwrite-install it (third-party repos may lag behind). Clearing app data is unnecessary unless explicitly noted in release notes |
| **2** | Open the LSPosed manager and enable `Bilibili Innocent Lab` |
| **3** | Add official Bilibili (`tv.danmaku.bili`) to the module scope |
| **4** | Force-stop and reopen Bilibili (if startup slows down or the module stays inactive, try turning off hiding for Bilibili in HMA-OSS) |
| **5** | Open module settings and confirm the banner reads "**module activated**" |
| **6** | Toggle only the features you want under Cleanup, Enhancements, Beautify or Experimental; avoid enabling overlapping options in other modules |
| **7** | Tap "Restart Bilibili" inside settings (or force-stop Bilibili manually) for changes to take effect |
| **8** | If using the BiliRoaming extension, follow the on-screen cache check prompt and restart Bilibili once ready |

### A note on ROOT permission

If your device is rooted, you may grant ROOT permission to allow:

- Restarting and verifying Bilibili automatically after saving settings;
- Reading additional local diagnostic details in the Diagnostics Center.

No other ROOT operations are performed, and all usage can be inspected directly in source.

### Recommended testing order

On a fresh setup, enable features step by step so any issue is easy to isolate:

1. Confirm the module home screen says activated;
2. Turn on **one or two** cleanup switches, restart Bilibili and verify;
3. Test long-press free copy on comments, descriptions and titles;
4. Once core features look good, try player frame interpolation, audio rescue, visual theming or AI options;
5. Enable the BiliRoaming extension only if you actively use BiliRoaming and need newer-client compatibility.

---

## Root-free support

Non-rooted devices can load the module into a locally patched official Bilibili APK via **NPatch** or **JingMatrix LSPatch** in Manager (local) mode:

| | **NPatch (Manager mode)** | **JingMatrix LSPatch (local / Manager mode)** |
| --- | --- | --- |
| **Recommended version** | NPatch Manager 1.0.7+ (Android 9+) | JingMatrix LSPatch 1.2 (487)+ |
| **Tested reference** | Bilibili 8.90.2, Android 16 / arm64 | Bilibili 9.13.0, Android 16 / arm64 |
| **Embedded (`--embed`) mode** | ❌ Not available in current releases | ❌ Not available in current releases |
| **Settings switch** | Accept terms and enable "Root-free support" | No NPatch switch or compatibility mode required |

> [!NOTE]
> Only Manager-based local patching is available right now; bundled embedded APK builds are not offered in current releases.

### Setup steps

**NPatch (Manager mode)**

1. Patch and install official Bilibili in Manager mode via NPatch;
2. Open NPatch Manager and associate `Bilibili Innocent Lab` with Bilibili;
3. Open the standalone module settings app, accept the terms, turn on **"Root-free support"** under compatibility settings, then force-stop and reopen Bilibili.

**JingMatrix LSPatch (local mode)**

1. Patch official Bilibili in LSPatch Manager using **local** mode and link `Bilibili Innocent Lab`;
2. Install the patched client while keeping the standalone module settings app installed, and accept the terms inside settings;
3. Force-stop and reopen Bilibili, then verify activation in the module home screen.

### Everyday tips

- **Keep the settings app installed**: In root-free mode Bilibili retrieves configuration from the standalone module app. If the system kills the settings app, open settings once before cold-starting Bilibili.
- **Force-stop after changes**: After toggling features in root-free mode, force-stop and reopen Bilibili manually. Unauthorised or incomplete configuration states keep features disabled instead of applying stale caches.
- **Upgrades and reinstalls**: Updating the module APK keeps your preferences; uninstalling and reinstalling resets terms consent and root-free authorisation (you can restore feature toggles from a Settings Backup file).

<details>
<summary><b>Additional root-free troubleshooting notes</b></summary>

<br>

- Root-free stability depends on your ROM, Android version, patch manager build and other injected modules, and may not match Root + LSPosed in every environment. Keep a local settings backup and original APK handy.
- Open **Diagnostics Center → Root-free support** in the module app to inspect the detected patch mode, loader version, active configuration source and responding processes when filing bug reports.

</details>

---

## Cloning and multi-user

The module only attaches to clients keeping the official package name `tv.danmaku.bili`:

| Setup | Package name | Supported |
| --- | --- | :---: |
| System multi-user, App Twin, Work Profile | Still `tv.danmaku.bili` | ✅ Enable separately **inside that user profile** |
| Renamed APK clones | Custom package name | ❌ |
| VirtualApp / container cloners | Container package name | ❌ |

Renamed clones or container apps cannot match Bilibili's official package resources or config paths.

**Enabling for a twin app / secondary Android user**:

1. Install `Bilibili Innocent Lab` inside the secondary user profile as well;
2. Switch to that user inside your LSPosed / patch manager, enable the module and select Bilibili in its scope;
3. Open the module settings app once inside that user profile;
4. Force-stop and reopen the cloned Bilibili client.

If the settings screen warns about a missing framework service in user N or a user mismatch between the module and Bilibili, repeat steps 1–2 for that specific user profile.

---

## Features

Options are organised under **Favorites / Cleanup / Enhancements / Beautify / Module**, with search and pinned favorites.

- `On by default`: enabled on fresh installs; all other switches start off;
- `Experimental`: relies on newer host paths or hardware capabilities and varies by device;
- Changes take effect after **restarting Bilibili** unless noted otherwise; unsupported entry points on an untested client build are skipped safely.

**Quick jump**: [Home and navigation](#-home-and-navigation) · [Dynamics, search and "My"](#-dynamics-search-and-my) · [Video detail pages](#-video-detail-pages-and-recommendations) · [Player](#-player) · [Danmaku and live](#-danmaku-live-rooms-and-the-portrait-feed) · [Comments](#-comments) · [Free copy](#-free-copy) · [Smart filtering and AI](#-smart-filtering-and-ai-features-experimental) · [Appearance](#-appearance-and-beautify) · [Privacy](#-privacy-and-browsing) · [System and remote flags](#-system-and-remote-flags) · [BiliRoaming extension](#-biliroaming-version-support-extension) · [Module management](#-module-management-and-maintenance)

### 🏠 Home and navigation

#### Top bar and recommendations

| Feature | Description |
| --- | --- |
| Hide top-bar Game Center entry | Removes the game shortcut in the top bar |
| Hide default search placeholder | Removes promoted default search suggestions in the search box and search page |
| Hide home banner carousel `On by default` | Clears large banner carousels on the home feed without leaving blank space |
| Hide Live tab promoted banner | Removes the top promotional carousel on the Live tab |
| Filter recommendation cards by type | Hide ads, feed promotions, image-text posts, game cards, live streams, anime/films, comics, courses, special cards, portrait or large-card picks by card type without touching ordinary videos |
| Open portrait videos in standard details | Opens home portrait cards in the normal video page instead of jumping into the portrait swipe feed |
| Load home recommendations early | Prefetches the next page ~4 rows before the bottom to avoid loading stalls (uses slightly more data) |
| Enhanced recommendation feedback | Adds quick tag and uploader blocklist shortcuts to the feedback sheet, with optional auto-confirm for new blocked tags |

#### Recommendation rules

| Feature | Description |
| --- | --- |
| Filter by title keywords | Hides recommended videos matching any keyword |
| Filter by tag / uploader | Filters home and detail recommendations by tag name/ID or uploader name/UID |
| Filter by duration / view count | Set min/max seconds or view bounds; leave either bound blank for no limit |
| Filter by publish time | Keep or exclude videos by calendar date range or relative window (e.g. last 2 days); cards missing timestamps are kept |

#### Custom navigation layout

| Feature | Description |
| --- | --- |
| Customise home tabs / modules | Visit home once, scan items in settings and tick entries to hide, or write text rules (at least one home tab stays enabled) |
| Customise bottom bar | Hide unwanted bottom navigation tabs by name (keeps at least one tab) |

### 🔔 Dynamics, search and "My"

#### Dynamics

Rules apply to both the All and Videos tabs at once.

| Feature | Description |
| --- | --- |
| Hide "City" / "Campus" tabs | Removes each tab from Dynamics separately |
| Land on "Videos" tab by default | Selects the Video feed tab automatically when opening Dynamics |
| Hide topic bar / frequently visited | Removes the top topic bar or the frequently-visited strip cleanly |
| Hide live uploaders from the top strip | Drops currently live avatars and compacts the remaining row |
| Filter dynamics by keyword / author | Matches post body, reposted text or uploader UID/exact name (cards without readable author info are kept) |
| Hide shopping / recommendation cards | Removes attached commerce/product cards |
| Hide locked fan-charge dynamics | Hides unsubscribed charge-only posts while keeping unlocked or unreadable ones |

#### Search and "My"

| Feature | Description |
| --- | --- |
| Hide search trending and discovery | Clears trending searches and discovery suggestions while keeping personal search history |
| Hide promoted search results | Strips ad and campaign cards from combined search |
| Filter search video results | Filters video results by keyword or author without touching anime, live or articles |
| Hide VIP card on "My" page | Hides the VIP banner (optional placeholder lock prevents layout jump) |
| Customise "My" page entries | Scan and hide unwanted creation-center, course or game shortcuts |

### 📺 Video detail pages and recommendations

| Feature | Description |
| --- | --- |
| Hide game ads in "Video mentioned" `On by default` | Removes game promotion cards in video mention panels |
| Hide uploader merchandise cards `On by default` | Hides "shared by UP" product shopping blocks |
| Hide topic promotions | Removes promotional topic banners below the player |
| Hide "You may be interested" after following | Blocks follow-triggered uploader suggestion cards before they appear |
| Hide post-playback recommendations | Hides end-of-video suggestion overlays inside the player |
| Hide detail page clutter | Independently hide rankings, live reserve cards, collection entries, VIP badges, topic pills, co-creator follow buttons and trending search strips |
| Filter related recommendations | Hide commercial, game, live, course or reason-matched cards; **Strong mode** also drops unknown/trending recommendation cards |
| Block AI-declared videos `Experimental` | Auto-skips videos carrying the "Contains AI-generated content" disclosure; optional strong mode with `access_key` pre-checks related cards in-memory only |

### 🎬 Player

#### Cleanup and interface

| Feature | Description |
| --- | --- |
| Hide paused-page ads `On by default` | Suppresses promotional overlays shown when pausing playback |
| Hide player interactive overlays | Removes vote cards, follow prompts, triple-action cards, command danmaku and the top-left TV banner |
| Hide popup promotions | Suppresses unsolicited data-plan, commerce and mini-program popups while keeping user-opened sheets and network warnings |
| Block automatic series activity panels | Prevents automatic Premium promos from expanding on anime/movie pages |
| Hide portrait-feed shortcut / transparent status bar | Hides the portrait enter button or uses a transparent status bar on video detail pages |

#### Quality, codec and playback control

| Feature | Description |
| --- | --- |
| Preferred quality | Follow Bilibili or request 360P up to 8K at start (respects source, account and network limits; does not bypass paid tiers) |
| Preferred codec / decode mode | Prefer H.264 / H.265 / AV1 or force hardware/software decoding for new playback sessions |
| Default speed / speed menu / long-press speed | Set default playback speed, enter 1–12 custom menu speeds (`0.1×–8×`) or customise/disable long-press fast-forward |
| Unlock background / PiP / cast | Independently enables background playback, mini-player and casting |
| Video buffering strategy `Experimental` | Choose minimal buffer, custom `1–600s` forward buffer or full single-file MP4/FLV prefetch with memory/disk caps and Wi-Fi-only switch |

#### Video frame interpolation `Experimental`

Smooths standard videos up to 4K (e.g., 30 fps → 60 fps target), with both **Automatic** selection and **Forced interpolation mode** control (enable interpolation status prompts to inspect the active backend live):

- **Automatic mode (default)**: Snapdragon devices with QCOM hardware motion estimation use model-free QCOM interpolation first; other Vulkan GPUs or unsupported streams fall back automatically to the RIFE v4.6 model downloaded in settings, dynamically scaling resolution tier to frame budget.
- **Force interpolation mode**: Enable **"Force interpolation mode"** to unlock the **"Interpolation mode"** picker:
  - The module probes your device's GPU architecture and acceleration features (e.g., Adreno / Mali·Immortalis / Xclipse·RDNA / PowerVR, FP16, Subgroup, AHB zero-copy);
  - **On QCOM-capable devices**, both `QCOM` and `RIFE` appear as selectable backends;
  - **Without QCOM hardware support**, `QCOM` is hidden and only `RIFE` is displayed with an inline prompt and one-tap button to download the RIFE v4.6 model;
  - Locking a mode keeps it selected without dropping back across backends or frame-time thresholds.
- **Limits & controls**: HDR, protected streams and non-1.0x speeds keep native playback; you can configure the FPS ceiling, toggle live status prompts and optionally bypass thermal throttling after confirmation (higher resolution adds slight A/V-synced latency and power draw).

#### Subtitles, Audio rescue & SponsorBlock

| Feature | Description |
| --- | --- |
| Enhanced subtitle styling & AI subtitle flag | Adjust subtitle font, size, backdrop and outline locally; toggle client AI multilingual subtitle rollout flag |
| Audio rescue `Experimental` | Sudden peak guard (−6 dBFS ceiling), stereo/mono/one-sided channel repair, plus optional ~56 MB speech denoising and voice clarity (~33 ms latency) |
| SponsorBlock integration | Queries `www.bsbsb.top` by video hash prefix (no account data sent) to prompt or auto-skip sponsor segments with a 6-second undo button; optional short-video support |

### 💬 Danmaku, live rooms and the portrait feed

| Feature | Description |
| --- | --- |
| Danmaku weight filter / hide gradient danmaku | Drop danmaku below a 1–10 server weight threshold or strip gradient effects while keeping text visible |
| Live rooms | Disable vertical room-swiping, or remap double-tap to pause/resume instead of liking |
| Portrait feed filters | Independently hide ads, live, game promos, anime/film, courses, short dramas, shopping, movies, documentaries, TV series, variety or music cards, plus side action/danmaku icons |

### 💭 Comments

| Feature | Description |
| --- | --- |
| Interface cleanup | Remove keyword search links, empty-comment send prompts, poll/vote cards, inline follow buttons, QoE prompts, promotions, reply buttons, tap-to-quick-reply or the entire comment tab |
| Rule filtering | Filter root, pinned and nested replies by keyword (up to 64 rules), minimum user level, mention-only text or author UID/username |
| Hide AI spam comments | Scores nickname, level, phrasing and public profile cues locally on device (three sensitivity tiers, Lv5+ protection, custom lists; rules ported from MIT-licensed `dafeiyu-cleaner`) |
| Reply context map & classic layout | "Trace" button visualizes full thread trees with pan/zoom and author/keyword search; optionally restore the classic nested reply preview layout |

### 📋 Free copy

Long-press supported text to open a selection bubble (tap outside to close), then drag standard Android selection handles to copy any snippet:

| Surface | Description | Default |
| --- | --- | :---: |
| **Comments** | Long-press comment bodies to copy complete original text with line breaks and converted emojis | On |
| **Video descriptions** | Expands and reads full description paragraphs with clean whitespace | On |
| **Video titles** | Enable under Enhancements → Free copy to copy full unclipped titles | Off |

Supports **Default dark / Manual light / Auto-follow Bilibili theme** bubbles without blocking image preview taps after dismissal.

### 🤖 Smart filtering and AI features (experimental)

All AI features start **off** and only contact API endpoints you configure yourself; review your provider's privacy and billing terms before enabling. API keys stay local on device and are excluded from backups and diagnostics.

- **Smart semantic filtering**: Configure up to 8 JEV / OpenAI-compatible endpoints under Compatibility settings with round-robin balancing and cooldown, then route Dynamic, Comment, Recommended Video and Danmaku filters independently on top of rule filters.
- **AI Insight video assistant**: On-demand Summary, Ask, Topic clustering and AI Chapters on video detail pages using subtitles and loaded comments/danmaku.
- **AI task assistant**: Goal-driven in-app search and UI automation across capability-checked endpoints (stops on app switch/screen lock; blocks sensitive credential/payment steps).

### 🎨 Appearance and beautify

Materials and motion are powered by [Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine).

- **Floating top / bottom bars**: Real-time sampled glass capsules (`Soft light`, `Clear glass`, `Liquid glass`), theme artwork blending, compact/icon-only bottom bar, touch glow bloom, accent badges and profile theme dimming (`On by default`).
- **Page backgrounds & floating cards**: Built-in gradient presets or local custom images for native pages (images stay on device), plus configurable `0–40dp` card corner radius, spacing, card materials and `Interface power saving` (`On by default`).
- **Splash & module UI**: Remove splash ads, skip brand splash, pick downloaded official splash assets, match splash background to dark mode and optionally enable liquid glass inside module settings.

### 🔒 Privacy and browsing

- **Browse without login / Incognito mode**: Hide login prompts while keeping manual login, store local history/favorites/follows on device (`Incognito` uses an ephemeral in-memory guest session and disconnects embedded webviews), and optionally skip guest onboarding questionnaires.
- **Link & share hygiene**: Strip tracking query params cleanly offline without resolving `b23.tv` short links; send plain link cards instead of mini-programs; optional re-signed WeChat share compatibility (`8.84.0–9.18.0`); open external links in the system browser.

### 🧩 System and remote flags

Hide client update & teen-mode prompts, show exact un-abbreviated counts, always display AV numbers, unlock system media playback notifications, pin the legacy home frame, freeze individual boolean/custom remote flags (auth/network/risk-control keys stay protected) and block unwanted app component asset pools.

### 🌍 BiliRoaming version support extension

Works alongside an enabled **BiliRoaming** module (provides no accounts, keys, proxies or server parsing of its own). Repairs known compatibility issues on newer Bilibili builds, helps rebuild and safely reuse verified caches across restarts or root-free updates, syncs switches, adds a settings entry on "My" and mitigates MIUI/HyperOS background restrictions.

### 🔧 Module management and maintenance

View live Xposed/LSPosed framework status, restart Bilibili via Root / Shizuku / guided manual stop, export and preview-validate local settings backups (secrets & consent stay excluded), inspect read-only Diagnostics & concise/full logs, check Stable / Preview (Alpha) / Canary updates, hide launcher icon, switch app language (`Follow system / 简体中文 / 繁體中文 / English`) and toggle Predictive Back (Android 14–15; system-managed on Android 16+).

#### Re-adapting after a Bilibili update

If an update changes obfuscated class names and causes features to stop responding: open **Experimental features → Compatibility**, tap **"Re-adapt to the current version"**, confirm and restart Bilibili.

---

## Release channels

| | **Stable** | **Alpha pre-release** | **Canary** |
| --- | --- | --- | --- |
| **Best for** | Everyday use | Testing new client adaptations & upcoming features | Following `main` commits & rapid bugfix verification |
| **Source** | [Latest release](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/latest) | Pre-releases on [Releases](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases) | [Actions Canary](https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/workflows/canary-build.yml) / [Telegram channel](https://t.me/Bilibili_Innocent_LabRelease) |
| **Recommendation** | ✅ Recommended for most users | Expect rough edges; keep logs/backups ready | Frequent updates, minimal verification |

Canary builds are synced nightly at 22:00 UTC+8 to the Telegram channel when a new verified build is available, and GitHub Actions artifacts remain downloadable for 30 days with SHA-256 checksums.

---

## FAQ

| Question | Quick fix |
| --- | --- |
| Module shows "Not activated" | Verify the module is enabled in LSPosed, `tv.danmaku.bili` is ticked in scope, and Bilibili was force-stopped and reopened (reboot if needed). |
| Very slow startup or communication failure | Turn off hiding for Bilibili / this module in HMA-OSS and test again. |
| Twin app lacks framework service | Install and enable the module inside the twin user profile too, open module settings once there, then restart the twin client. |
| Switch is on with no effect | Force-stop & restart Bilibili; after client upgrades run "Re-adapt to the current version"; with BiliRoaming, confirm cache readiness first. |
| Free copy doesn't open / blocks image taps | Ensure free copy is enabled and you're on the latest build; check for conflicts with other clipboard/PopupWindow modules. |
| Conflict with other modules | Reproduce with only this module enabled first, then re-enable other modules one by one. |

---

## Reporting issues

Before opening an issue, please try to include:

1. Exact module version (latest Stable, or the Alpha/Canary build tag);
2. Confirmation that the bug still reproduces with only this module active;
3. Bilibili, Android, LSPosed/patch manager versions and active toggle states;
4. Step-by-step reproduction steps, screenshots/recordings and LSPosed logs (or crash stack traces);
5. **Never upload accounts, cookies, `access_key`, API keys or sensitive personal data.**

---

## Notices and disclaimers

Read this section carefully before downloading, installing, compiling, distributing or using the project.

### Project statements

**To keep the project maintainable, please do not broadcast project-related content in any form — including but not limited to files, text, icons, links, images, videos and code — to non-specific audiences on public social media.**

**This is a non-profit project. It never charges for software or services, and it never collects or transmits your personal data without consent. If you suspect a file obtained from another channel contradicts these principles, always compare it against the Release hash of the matching version. If the hashes differ, the file may have been tampered with by a third party; this project cannot vouch for its safety, and if you know this and keep using it, you bear all resulting risk and consequences.**

### Privacy

All core processing runs locally on your device. Logs go to the local LSPosed buffer and update checks query GitHub Releases metadata; the module never transmits your Bilibili credentials, cookies or BYO API keys. Review exported logs for links or device identifiers before sharing publicly.

### Disclaimer

This is an unofficial, non-commercial personal research project with no affiliation, sponsorship or endorsement from Bilibili. Runtime hooks alter app behaviour and depend on client builds, system policies, ROMs and account experiments; you assume responsibility for testing, backups and compliant usage.

<details>
<summary><b>Read full Additional Disclaimer and Risk Notice (15 clauses)</b></summary>

<br>

> **Important: please read this notice in full before downloading, installing, compiling, distributing or using this project.**  
> This notice explains the nature of the project, the risks of use and the boundaries of responsibility, and does not constitute formal legal advice for any individual, organisation or jurisdiction. If you plan to use this project for public distribution, organisational operation, commercial activity or other scenarios with higher legal risk, consult a suitably qualified professional. If you disagree with any part of this notice, stop downloading, installing, compiling, distributing or using this project immediately.

#### 1. Nature of the project and unofficial status

Bilibili Innocent Lab is an unofficial, non-commercial open-source research project maintained independently by community developers, mainly for studying, researching and discussing technologies such as Android, Xposed/LSPosed, runtime hooking, UI interaction and software compatibility.

This project has no affiliation, authorisation, delegation, cooperation, sponsorship, recognition or endorsement relationship with Bilibili, its operating entity, affiliates or partners, and does not represent the position of any third-party platform, framework or extension project.

References to "哔哩哔哩", "Bilibili", related app package names, UI names, product names, trademarks, logos and other proprietary names in the project name, documentation or code are used only to describe the compatibility target, technical background or scope of features. The trademark rights, copyrights and other legitimate rights in them belong to their respective holders. Such descriptive use does not mean this project has been authorised by those rights holders.

#### 2. Scope of features

This project provides only module code that runs on the user's local device. It is not a standalone video client, and it does not provide or operate any audio or video content, account services, membership services, network proxies, parsing services, access credentials, cookies, access keys, copyrighted content, paid resources or third-party platform data.

This project grants users no additional rights over any third-party content, software, interface, account or service. The text selection, UI cleanup, interaction enhancement, version adaptation or third-party extension compatibility the module provides does not mean the user has obtained permission to copy, repost, publish, distribute, modify, compile or commercially use the related content.

Free copy only changes how text is selected in the local UI. When you copy, save, quote, repost or distribute comments, descriptions and other text, you must confirm for yourself that your conduct complies with copyright, privacy, personal data protection, platform rules and other applicable requirements. The fact that the module can perform a technical operation does not mean that operation is legally authorised in every scenario.

#### 3. Prohibited uses

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

#### 4. Device, system and account risks

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

#### 5. Compatibility and availability

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

#### 6. No warranty

To the maximum extent permitted by applicable law, this project and its related code, documentation, build artifacts, scripts, configuration and instructions are provided "as is" and "as available", without any express, implied or statutory warranty.

The project authors, maintainers, contributors and publishers make no warranty of merchantability, fitness for a particular purpose, accuracy, completeness, stability, continuity, security, non-infringement, freedom from errors, freedom from viruses or compatibility with a particular device.

No example, test record, performance figure, development note or user feedback should be understood as a promise, a warranty or a service level agreement. Users must verify for themselves whether the project suits their device, account and purpose.

#### 7. Limitation of liability

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

#### 8. Third-party projects and external links

This project may use or mention LSPosed, Xposed, Magisk, YukiHookAPI, MeguvizRef, MeguvizUI, MeguvizAndroid, Lumen Coacervation Engine, BiliRoaming, GitHub and other third-party software, frameworks, services or websites. Those projects are maintained independently by their respective rights holders and are subject to their own licences, privacy policies, terms of service and disclaimers.

The appearance of a third-party name, link or compatibility code in this project does not mean this project guarantees its safety, legality, stability or continued availability, nor that a cooperation or authorisation relationship exists between the two.

The risk of visiting external links, installing third-party components, enabling other modules or using external services is for users to judge and bear themselves.

#### 9. Build artifacts and redistribution

Obtain source code and APKs only from this project's official GitHub repository and its Releases page, and verify the release origin, digital signature, file checksum and version information yourself.

The project author cannot control installers redistributed by third-party websites, file hosts, groups, forums or individuals. For builds from unofficial sources, modified builds, re-signed builds, malicious repackaged builds or builds bundled with other software, the maintainers give no guarantee of trustworthiness and accept no liability arising from them.

Anyone redistributing the project must comply with the repository's applicable open-source licence, keep the necessary copyright and source information, and state clearly that their build is not an official release. Impersonating the project author, describing a modified build as the official original, or using this project for payment fraud, malicious advertising, privacy collection or other conduct that harms users is prohibited.

#### 10. Logs, privacy and issue reporting

Runtime logs may contain the app version, system version, device model, process name, class names, page state, video links or other environment information. Before publicly submitting an issue, log, screenshot or screen recording, users should check for and remove account information, cookies, tokens, access keys, private chats, comment identities, device serial numbers and other sensitive content.

Information users submit to GitHub, forums or other public channels may be viewed, reposted, indexed or stored long-term by third parties. The maintainers cannot guarantee that an external platform will completely delete information that has already been made public.

If submitted content contains obviously sensitive information, third-party private data or data unrelated to the issue, maintainers may hide, delete or decline to process it, but cannot guarantee to notice it before a leak occurs.

#### 11. No ongoing support

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

#### 12. Contacting rights holders and content handling

If any rights holder believes that code, documentation, names, screenshots, links or other content in this project infringes their lawful rights, they may submit an explanation through a GitHub issue or the repository's public contact details, providing the information necessary to identify the rights holder, the content concerned and the basis of the right.

The maintainers will look into it within reason and may take steps such as explaining, modifying, removing, restricting distribution or other appropriate measures. Acting on feedback does not mean the maintainers automatically admit infringement, illegality or liability.

#### 13. Updates to this notice

This disclaimer may be updated as the project's features, release methods, dependencies and applicable rules change. The updated version applies to subsequent downloading, installation, compiling and use from the date it is published.

Users are responsible for checking the latest README, release notes and disclaimer before updating the module or using the project again. Continuing to use the updated project means the user is aware of the corresponding risk notice; if you disagree with the update, stop using it and uninstall the relevant version.

#### 14. Severability and limits of legal effect

If part of this notice is held to be invalid, unenforceable or in conflict with applicable law, the remaining provisions continue to apply to the extent permitted by law. The provision concerned should be interpreted under applicable law in a way that comes as close as possible to the original purpose of the risk notice and the boundary of responsibility.

A maintainer's failure to exercise or assert a right immediately should not be interpreted as permanently waiving that right.

Note in particular that a disclaimer does not automatically exclude all liability. China's Civil Code sets clear boundaries for the duty to highlight and explain standard clauses, for limitations of liability and for situations where liability cannot be disclaimed under law, so this notice is always limited to "the maximum extent permitted by applicable law" and does not exclude any liability that cannot be excluded by law. [Civil Code of the People's Republic of China](https://tjca.miit.gov.cn/zwgk/zcwj/flfg/art/2020/art_20cf1a2e1b854924b5caa744c8045d1f.html), [Interpretation of the Supreme People's Court on the General Provisions of the Contract Part of the Civil Code](https://www.court.gov.cn/zixun/xiangqing/419382.html)

When copying, reposting, distributing or using third-party works, users must also comply with the rules on copyright and the right of communication through information networks. The technical text-selection capability this module provides does not equal permission from the content rights holder to copy or distribute. [Copyright Law of the People's Republic of China](https://www.npc.gov.cn/c2/c30834/202011/t20201119_308796.html)

#### 15. Acknowledgement of use

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

</details>

---

## For developers

Technical notes for contributors, builders and maintainers.

<details>
<summary><b>Building and local verification gates</b></summary>

<br>

The Gradle project lives in [`Bilibili_Innocent_Lab/`](Bilibili_Innocent_Lab) and includes Rust (`danmaku-wire`) and C++/Vulkan (`frame_window`) native modules.

**Common commands (Windows PowerShell; use `./gradlew` on Linux/macOS)**

```powershell
cd Bilibili_Innocent_Lab

# Query source-contract tests affected by current working-tree edits
.\gradlew.bat :app:contractsFor '-PinnocentLab.contractTarget=changed' --console=plain --no-daemon

# Full verification gate (Debug APK + unit/contract tests + full Lint + Release R8 check)
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug minifyReleaseWithR8 --continue --console=plain --no-daemon
```

- **Fast Lint**: Run `.\gradlew.bat :app:lintFast --console=plain --no-daemon` during day-to-day iteration (same ruleset as `lintDebug`, writing reports to `app/build/reports/lint/fast/`). Always pass `--no-daemon`, retain Gradle caches and avoid routine `clean`.
- **Artifact check**: The debug APK is output to `Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk`. After device installation, compare SHA-256 against the installed `base.apk`. Release/embedded signing tasks remain gated behind fixed signing credentials.

</details>

<details>
<summary><b>Test layers and source contracts</b></summary>

<br>

`testDebugUnitTest` executes two distinct source layers together:

| Layer | Directory | Purpose & filter |
| --- | --- | --- |
| **Behavior unit tests** | `app/src/test/java` | Catalog snapshots, filters, protocol codecs, state machines and fallback boundaries (`-PinnocentLab.testLayer=unit`) |
| **Source contract tests** | `app/src/contractTest/java` | Strict anchor guards over host wrappers, lifecycle cleanup, hot paths and library delegations (`-PinnocentLab.testLayer=contract`) |

- Run `:app:contractsFor -PinnocentLab.contractTarget=<File.kt|symbol>` before modifying production code;
- `SourceContract` fails loudly when `after`/`before` anchors disappear rather than falling back to the full file;
- `.github/release-templates/run_gradle_gates.py` enforces non-empty, 100%-reported coverage across both `unit` and `contract` layers plus zero effective Lint errors.

</details>

<details>
<summary><b>Hook runtime and performance boundaries</b></summary>

<br>

- **Framework & stack**: Uses Modern Xposed API (API 102) with the Meguviz library suite (`MeguvizRef`, `MeguvizUI`, `MeguvizAndroid`, `MeguvizMedia`) and [Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine).
- **Zero-blocking hot paths**: Resolved `Class`/`Field`/`Method` handles are cached during background adaptation so scroll, touch, binding and hook callbacks perform no disk, network, IPC or global DEX scans.
- **Lifecycle & isolation**: Views, Activities, Dialogs and Bindings are weakly held or explicitly detached; Protobuf rewrites copy only modified messages while keeping unknown fields; asynchronous/UI host entries route through `HostThreadGuard` to fail open safely.

</details>

<details>
<summary><b>Adaptation cache & root-free tooling</b></summary>

<br>

- **Structure relocation**: `VersionAdapter` locates obfuscated targets by structural signatures and bounded DexKit fallbacks, persists fingerprinted caches and tracks each feature family independently.
- **Root-free config boundaries**: Both NPatch and LSPatch Manager channels enforce signature, version and terms consent checks before installing hooks without trusting stale unverified caches.
- **Patching utilities**: Local NPatch build/signing scripts and baselines are documented in [scripts/npatch/README.md](scripts/npatch/README.md).

</details>

---

## License and credits

**License**　Except for third-party content stated otherwise, the original source code of this project is licensed under the [GNU General Public License v3.0 only](LICENSE).

SPDX-License-Identifier: GPL-3.0-only

Third-party components, frameworks and code used by or compatible with the project follow their own licences.

**Credits**　Thanks to the following projects and communities for the foundational capabilities and development references:

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
- [MeguvizRef](https://github.com/jichuo1/MeguvizRef) (reflection and member lookup, Apache-2.0)
- [MeguvizUI](https://github.com/jichuo1/MeguvizUI) (native View UI construction, Apache-2.0)
- [MeguvizAndroid](https://github.com/jichuo1/MeguvizAndroid) (Android runtime and window foundations, Apache-2.0)
- [Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine) (visual materials and motion for client and module surfaces, Apache-2.0)
- [BBZQ](https://github.com/HSSkyBoy/BBZQ)
- The Xposed / LSPosed community
- The Android Open Source Project and related developer documentation
- Everyone who tested, reported compatibility problems and provided logs

If this project helps you, you are welcome to file reproducible problems, compatibility information or improvement suggestions through an issue.
