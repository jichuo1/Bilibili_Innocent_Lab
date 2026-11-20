<div align="center">

<img src="banner.svg" alt="Bilibili Innocent Lab" width="100%">

[![Stable](https://img.shields.io/github/v/release/jichuo1/Bilibili_Innocent_Lab?style=flat-square&color=FB7299&label=stable)](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases)
[![License](https://img.shields.io/github/license/jichuo1/Bilibili_Innocent_Lab?style=flat-square&color=00AEEC)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.1%2B-2d3a55?style=flat-square&labelColor=2d3a55)](#requirements)
[![Framework](https://img.shields.io/badge/Xposed-LSPosed-00AEEC?style=flat-square&labelColor=2d3a55)](#requirements)
[![Target](https://img.shields.io/badge/target-tv.danmaku.bili-FB7299?style=flat-square&labelColor=2d3a55)](https://www.bilibili.com)
[![Telegram](https://img.shields.io/badge/Telegram-group-26A5E4?style=flat-square&logo=telegram&labelColor=2d3a55)](https://t.me/Bilibili_Innocent_Lab)

### A UI cleanup and interaction enhancement module for the Android Bilibili client

Clean up the UI · Filter content · Free copy · Enhance the player · Visual polish<br>
Every feature is optional and enabled on demand, targeting the official Android client `tv.danmaku.bili`

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
| **Loading methods** | [Root-free support](#root-free-support) · [Cloning and multi-user](#cloning-and-multi-user) |
| **Features** | [Features](#features) · [Release channels](#release-channels) |
| **Help** | [FAQ](#faq) · [Reporting issues](#reporting-issues) |
| **Notices** | [Notices and disclaimers](#notices-and-disclaimers) · [License and credits](#license-and-credits) |
| **Developers** | [Building and design](#for-developers) |

</details>

---

## Introduction

Bilibili Innocent Lab is an Xposed / LSPosed module for the Android Bilibili client. It cleans up distracting content while **keeping the official layout and interaction habits**, and adds capabilities around comments, descriptions, the player, appearance and version adaptation.

The module is loaded into the official client through runtime hooks and only modifies adapted screens and interaction entry points; your account, player, recommendations and comment section remain the official ones. Almost every feature has its own switch and is off by default, so enable only what you need; turn a switch off and restart Bilibili to restore the original behaviour, and settings can be exported as a backup.

### Capability overview

| Area | Representative features |
| --- | --- |
| 🧹 **Cleanup** | Ads, promotions and extra components on Home, Dynamics, Search and video detail pages; twelve categories in the portrait video feed; splash ads |
| 🔎 **Filtering** | Keyword, author, level, publish time, view count and recommendation reason; AI comment detection; optional AI semantic judging |
| 📋 **Free copy** | Long-press comments, descriptions and titles for a copy bubble with system selection handles |
| ▶️ **Player** | Quality and codec preferences, speed menu, buffering strategy, frame interpolation, subtitle enhancement, audio rescue, sponsor-segment prompts and skipping |
| 🎨 **Appearance** | Floating top / bottom bars, host page backgrounds, card materials, splash polish |
| 🔒 **Privacy** | Browse without login, incognito mode, share-link cleanup |
| 🧰 **Tools** | AI Insight, AI task assistant, reply context map, settings backup and restore, Diagnostics Center |
| 📦 **Root-free** | Loaded through NPatch / LSPatch local (Manager) mode |

> [!IMPORTANT]
> Please read [Notices and disclaimers](#notices-and-disclaimers) in full before downloading, installing, compiling, distributing or using this project; if you disagree with any part of it, stop using the project. Hook-based modules cannot guarantee compatibility with future versions, so read the release notes before use and report problems as described in [Reporting issues](#reporting-issues).

---

## Getting started

### Requirements

| Item | Requirement |
| --- | --- |
| **Android version** | Android 8.1 (API 27) or later |
| **Root / hook environment** | Magisk + LSPosed (an Xposed-compatible environment) recommended; [root-free loading](#root-free-support) is also available |
| **Target app** | The official Android Bilibili client, package `tv.danmaku.bili` |
| **Cloning** | Only system multi-user / app twin / work profile setups **whose package name is still `tv.danmaku.bili`** are supported; renamed clones and VirtualApp-style containers are unsupported, see [Cloning and multi-user](#cloning-and-multi-user) |
| **Adapted clients** | 8.84.0 to 9.20.0. The cloud archive holds 47 exact APKs (different builds of the same version are compared separately) and each feature is audited per build; class / interface, member signature, generic and R8 differences are in the [9.16.0 adaptation notes](docs/host-9.16.0-adaptation.md), and the incremental audit of 9.17.0 (9170100 → 9170300) is in the [adaptation notes](docs/host-9.17.0-9170300-adaptation.md) |
| **On-device testing and other versions** | On-device testing is mostly done on sampled versions, and results may differ by device, system theme, account experiment group and minor client version. Clients between the audited versions should work in theory, but complete feature coverage is not guaranteed; if a feature fails and you want the project to adapt to it, follow [Reporting issues](#reporting-issues) |
| **BiliRoaming extension** | Requires a compatible BiliRoaming version installed and enabled separately |

### Installation

| Step | Action |
| :---: | --- |
| **1** | Download the APK from [GitHub Releases](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases) and install or overwrite-install the module (the version in the LSPosed repository may lag behind). Overwrite-installing normally does not require clearing Bilibili's data; wipe client data, module configuration or the BiliRoaming cache only when the release notes explicitly ask for it |
| **2** | Open the LSPosed manager and enable `Bilibili Innocent Lab` |
| **3** | Add the official Bilibili client `tv.danmaku.bili` to the module scope |
| **4** | Force-stop Bilibili, then start it again. If the host cold-starts very slowly after enabling the module, or the module cannot communicate with the host, try turning off host hiding in HMA-OSS |
| **5** | Open the module settings and confirm the page shows "**module activated**" |
| **6** | Enable the options you need under Cleanup, Enhancements, Beautify or Experimental features; when using the "advanced settings", turn off duplicated features in other modules |
| **7** | If a setting does not take effect immediately, use "restart Bilibili" inside the module, or force-stop the target app manually |
| **8** | With BiliRoaming (this module also provides compatibility settings for newer host versions): after the first build or a rebuild, check the cache result first; restart as the page guides once it is ready, and skip the extra restart when the cache was reused |

### A note on ROOT permission

Where possible, grant this module ROOT permission, which enables:

- Confirming and restarting Bilibili after saving settings.
- Unifying part of the local diagnostics in the Diagnostics Center.

This note is updated with each version so ROOT usage always stays transparent. The project promises never to abuse ROOT permission in any way that harms the host device. See the latest open-source code for details.

### Recommended testing order

On a first install, enable and verify features one at a time in this order, which makes it easier to tell where a problem comes from:

1. Confirm the module is activated;
2. Enable only **one** UI cleanup feature, restart Bilibili and check the home page or detail page;
3. Test free copy in comments, descriptions and titles, and choose the manual light bubble or auto follow according to your theme;
4. Once the basics are stable, try the experimental features;
5. Enable the BiliRoaming extension only if BiliRoaming is installed and you actually need the compatibility.

---

## Root-free support

The module can be loaded into a **locally patched** Bilibili client through a patch manager, independently of Root / LSPosed usage. Two paths are supported:

| | **NPatch** (Manager mode) | **LSPatch** (local / Manager mode) |
| --- | --- | --- |
| **Verified scope** | Bilibili 8.90.2, fixed repair build 833, signature level 2, Android 16 / arm64 | Bilibili 9.13.0, signature level 2, Android 16 / arm64 (Debug on-device pass) |
| **Version floor** | Code requires Android 9; NPatch Manager 1.0.7 or later | JingMatrix LSPatch 1.2 (487) |
| **Embedded mode** | ❌ Not supported for now | ❌ Not supported for now |
| **Switch in settings** | Select "Root-free support" | No NPatch switch or Communication Compatibility Mode needed |

**This version does not support embedded (`--embed`) mode**: it is temporarily disabled to discourage resale of hosts with the module bundled inside; the implementation is retained, and NPatch and LSPatch share the same disabled flag. The floors above are minimum requirements; actual support is limited to the verified combinations.

### NPatch

1. In NPatch Manager, add and enable the module and the host scope;
2. Keep the separate module settings app installed, accept the terms and select "Root-free support";
3. Cold-start Bilibili and verify the receipt in the module UI.

- Once enabled, the NPatch host reads the full settings through the **module's own admission channel** so Communication Compatibility Mode is not needed; Manager Remote Store sync is an optional mirror, and its being unavailable does not affect delivery.
- After enabling, disabling or changing settings, **force-stop and reopen** Bilibili manually. Features are installed only after current authorisation and full configuration validation pass, and the module UI shows "activated" only after a verified host receipt. If all authorising endpoints are unreachable, features stay off; an old cache is never used.
- If the settings app was force-stopped by the system, open the settings app first, then cold-start Bilibili.
- Overwrite-installing the settings app keeps your configuration; uninstalling and reinstalling deletes its configuration, terms record and account authorisation, so you must accept the terms and enable root-free support again. Business settings can be imported from "Settings backup"; terms consent, authorisation credentials and runtime receipts are not restored by a backup, and the host must obtain current permission again. Uninstalling only the settings app does not remove the module code already embedded in a host.

### LSPatch

1. In the LSPatch manager, select the module and apply it (the "local" option in the UI corresponds to `--manager`);
2. Keep the separate module settings app installed and complete the terms authorisation;
3. Cold-start Bilibili to verify. Activation must be confirmed by the current authorised configuration and a real host receipt.

The conflict between the old NPatch intent and the LSPatch configuration channel described in [issue #31](https://github.com/jichuo1/Bilibili_Innocent_Lab/issues/31) has been reproduced and fixed; legacy LSPatch, Canary and combinations outside the matrix remain unverified.

<details>
<summary><b>Diagnostics and more notes</b></summary>

<br>

- The root-free Diagnostics details show the patch mode, signature bypass level, Loader API / version, Manager version, actual configuration source, and the processes responding to the current query with their hook counts. Unresponsive processes remain unknown; installation counts do not prove feature behaviour.
- Build 833's Manager loading failure when Manager is unavailable requires the tool's Java loader fix. See the [patching guide](scripts/npatch/README.md) for patching, your own signing and same-certificate upgrades.
- Full scope, recovery and untested combinations: [NPatch support notes](docs/npatch-release-support.md); per-category compatibility and historical round-trip / recovery evidence: [implementation roadmap](docs/npatch-implementation-roadmap.md); LSPatch artifacts, recovery steps and the historical test boundary: [stage 9 record](docs/lspatch-stage9.md). Embedded mode is not available at present; its records are kept for historical reference.

NPatch support is still experimental. Its stability is affected by the Android version, ROM, NPatch Manager version, Bilibili version and other injected modules, and it cannot fully replace a Root/LSPosed setup. Back up your configuration first and keep the ability to restore the unmodified app. Other systems, ABIs, host versions, signature levels and module coexistence combinations are unverified, and generic business items still to be verified in the catalogue matrix are not counted as passed. Your own certificates are covered by the artifact and same-certificate upgrade checks; their third-party share SDKs and long-term account risk control need separate verification.

</details>

---

## Cloning and multi-user

The module works by **package name**: a cloned client is supported only if it keeps the package name `tv.danmaku.bili`.

| Cloning form | Package name after cloning | Supported |
| --- | --- | :---: |
| System multi-user, app twin, work profile | Still `tv.danmaku.bili` | ✅ Must be enabled separately **under that user** following the steps below |
| Renamed clones (some root-free multi-instance and package-renaming tools) | Changed to another package name | ❌ |
| VirtualApp-style container cloning (running inside a cloning app) | The container app's own package name | ❌ |

**Renamed clones are unsupported**: the module's scope is fixed to the official package name, so no other package can be added in the manager; even if the framework injects, the host resources and cache directories resolved through the official package name would all miss.

**Enabling under a twin app or another user**: the framework delivers its service **per Android user**, so a twin user must enable the module separately even if the primary user already has.

1. Install the module app under the user that runs the twin client as well;
2. In the framework manager, switch to that user, enable this module for it and make sure the scope includes Bilibili;
3. Open the module settings once under that user so the configuration is published;
4. Force-stop and restart Bilibili under that user.

The messages on the settings page mean the following (the "framework service" item in the Diagnostics Center gives the same evidence):

| Message on the settings page | Meaning |
| --- | --- |
| "There is no framework service under the current Android user N" | Step 2 was not completed under that user |
| "The module is in user A while Bilibili is in user B" | The module and the client are installed under different users and the configuration cannot be delivered |

---

## Features

Every feature has its own switch, organised as below in the settings; favorites and search take you straight to them.

| UI entry | Contents |
| --- | --- |
| **Favorites** | Saved feature entries |
| **Cleanup** | Content, advertisement, component and prompt filters per page |
| **Enhancements** | Comment / description / title copy, reading and browsing interactions, playback and full number display |
| **Beautify** | Module and host visuals, materials, backgrounds and other appearance options |
| **Module** | Activation, diagnostics, updates and module management, plus the experimental compatibility and task assistant entries |

**Legend**: `On by default` means enabled on a fresh install; anything not marked is off by default. `Experimental` means still being validated, with results that vary by device and client version. Unless stated otherwise, **restart Bilibili** after changing a setting. When the current client has no reliable adaptation entry point, the feature is skipped and the official behaviour is kept.

**Quick jump**: [Home and navigation](#-home-and-navigation) · [Dynamics, search and "My"](#-dynamics-search-and-my) · [Video detail pages](#-video-detail-pages-and-recommendations) · [Player](#-player) · [Danmaku and live](#-danmaku-live-rooms-and-the-portrait-feed) · [Comments](#-comments) · [Free copy](#-free-copy) · [Smart filtering and AI](#-smart-filtering-and-ai-features-experimental) · [Appearance](#-appearance-and-beautify) · [Privacy](#-privacy-and-browsing) · [System and remote flags](#-system-and-remote-flags) · [BiliRoaming extension](#-biliroaming-version-support-extension) · [Module management](#-module-management-and-maintenance)

### 🏠 Home and navigation

#### Top bar and recommendations

| Feature | Description |
| --- | --- |
| Hide the top-bar game center entry | Hides the game center entry at the top of the home page |
| Hide the default search suggestion | Hides the default suggestion in the search box and on the search page |
| Hide home banner carousel `On by default` | Hides the carousel cards that take up a lot of space at the top of the home page or recommendation area, completing the carousel cleanup in environments where it previously failed |
| Hide promoted live cards on the Live tab | Removes the promoted live carousel at the top of the "Live" tab without leaving a gap |
| Filter recommendation cards by type | Separately filter ads, standard feed ads, image-text posts, game promotions, live, anime & films, comics, courses, special cards, portrait-video and large-card recommendations, judged by explicit card type only; ordinary video recommendations stay as they are |
| Open portrait videos in details | Opening a portrait video from the home page goes to the normal video detail page instead of the portrait continuous-play screen; home page only |
| Load home recommendations early | Requests the next page about four rows before the end of the list, reducing "loading" stalls at the bottom; works on phones and tablets and uses slightly more data |
| Enhanced video recommendation feedback | Adds tag and uploader blocking options to the feedback panel of home and detail-page recommendations, with optional auto-confirmation of new blocked tags |

#### Recommendation filter rules

| Feature | Description |
| --- | --- |
| Filter by title keywords | Hides recommended videos whose title contains a keyword |
| Filter by tag / uploader | Filters recommendations by tag name or ID and by uploader name or UID |
| Filter by video duration | Set a minimum and a maximum duration; videos outside the range are removed from home recommendations and the recommendations below video details; leave either bound empty to leave it unrestricted |
| Filter by view count | Set a minimum and a maximum view count; leave either empty for no limit |
| Filter by publish time | Before or after a date, inside or outside a range, or relative to now (such as the last two days); cards without a readable time are always kept |

#### Custom structure

| Feature | Description |
| --- | --- |
| Customize Home tabs | Open the home page to scan and tick, or type name rules (separated by commas, full-width commas, semicolons or newlines); at least one tab is kept |
| Customize Home components | Scan and tick, or write rules by component name or known identifier, to remove home sections you do not need |
| Customize the bottom bar | Hide bottom navigation entries by name; at least one usable entry is kept |

### 🔔 Dynamics, search and "My"

#### Dynamics

The combined page and the video page share the same rules, so configuring once covers both. Filtering may shorten the current page; pagination is unaffected.

| Feature | Description |
| --- | --- |
| Hide the "City" / "Campus" tabs | Removes each from the Dynamics tabs separately |
| Show the Video tab by default | Opening Dynamics lands on the "Video" category; when the current version has no such entry, the default page is kept |
| Hide the topic bar | Removes the topic bar above the Dynamics list |
| Hide frequently visited on Dynamics | Hides the whole frequently-visited block at the top without leaving a gap |
| Hide live authors from the top bar | Removes UPs that are currently live and re-lays out the remaining entries so no gap is left |
| Filter dynamics by keyword | Hides a Dynamic when any keyword appears in its body, in the body of a reposted original Dynamic, or in the module text; each text segment is matched separately |
| Filter dynamics by author | Each rule entry is one UID or username (usernames must match exactly); Dynamics from a matching author, including reposts, are hidden; Dynamics whose author cannot be identified are kept |
| Hide shopping and recommendation cards | Judged by the attachment card type Bilibili delivers |
| Hide locked charge-only dynamics | Unlocked ones and ones whose permission state cannot be read are kept |

#### Search

| Feature | Description |
| --- | --- |
| Hide search-page trending and recommendations | Hides trending and discovery on the search home with their accessories, keeps search history and leaves no gap |
| Hide promoted search results | Removes commercial ad cards and special campaign cards from the combined search results |
| Filter search results by keyword / author | Hides **video** results whose title contains a keyword or whose author is on the list; other card types such as anime, live and articles are left as they are |

#### The "My" page

| Feature | Description |
| --- | --- |
| Hide the VIP card | Hides the VIP card on the "My" page |
| Keep the VIP card position | Works with the option above: hides the content but keeps its placeholder, avoiding layout jumps |
| Customize Mine-page components | Scan and tick, or write rules by menu name, to trim entries such as the creation centre, courses and the game center |

### 📺 Video detail pages and recommendations

| Feature | Description |
| --- | --- |
| Hide game ads inside the "Video mentioned" section `On by default` | Filters recognised game promotion cards in areas such as "video mentions" under videos with game tags |
| Hide UP merchandise promotions `On by default` | Hides recognised product promotion areas such as "products shared by the UP" |
| Hide topic promotions on video detail pages | Hides topic and activity promotions below the player |
| Hide "You may be interested" uploaders after following | After you follow an uploader, the "You may be interested" card no longer pops up and leaves no gap; the recommendation request is blocked before it is sent |
| Hide player recommended video component | Hides the recommendation cards inside the player after an ordinary video ends |
| Hide detail-page elements | Separately hide the honor list, live reservation card, collection entry, uploader membership badge, video topic tags, co-creator follow buttons and trending-search strips and badges |
| Filter related recommendations | Separately filter commercial promotions, game, live, course and special recommendations (by explicit card type); recommendation reasons can also be used to detect promotions, and related videos can be filtered by reason keywords, uploader or tag |
| Strong mode for related recommendations | Additionally filters recommendations with unclear type or reason, trending recommendations and promotion cards; may hide ordinary recommendations |
| Block videos declared as AI-generated `Experimental` | Opening a video that carries the "Contains AI-generated content" declaration jumps to a related video (in playlists it skips to the next item), and shows a notice when none is usable; home recommendations are filtered in advance by the AI creation tag. Optional strong mode: after you authorise `access_key`, detail-page recommendations are pre-checked in the background and removed early (the token is only used inside Bilibili and is never stored anywhere except secure device memory) |

### 🎬 Player

#### Cleanup and interface

| Feature | Description |
| --- | --- |
| Hide paused-page video ads `On by default` | Hides promotional content that appears after pausing some videos; the effect depends on the client version, player type, account experiment group and ad delivery policy |
| Hide player interactive overlays | Blocks the vote card, follow prompt, triple-action contract card, command danmaku and the TV-app promotion banner at the top left |
| Hide player popup promotions | Hides automatically appearing data-plan, shopping and mini-program floating promotions, keeping network-status reminders and panels you open yourself |
| Block automatic activity panels on series pages | Blocks the Premium promotion and similar activity panels that expand automatically below the player on film and TV pages; activity links and purchase entries you open yourself are kept |
| Hide the portrait-feed button | Hides the "enter look-around" button in the player |
| Transparent player status bar | Applies the transparent status bar only on the ordinary video detail page |

#### Quality, codec and playback control

| Feature | Description |
| --- | --- |
| Default playback quality | Follow Bilibili, or prefer 360P up to 8K (including 720P60, 1080P high bitrate, 1080P60 and 4K); it only changes the initial quality request when playback starts and does not unlock Premium or paid quality; the client adjusts automatically when the source, account or network does not support it |
| Preferred video codec | Prefer H.264 / H.265 / AV1; only affects newly created playback sessions |
| Decoding mode | Follow Bilibili, or force hardware / software decoding, only for newly created playback sessions |
| Default playback speed | Sets the base speed for newly played videos; follows Bilibili by default |
| Playback speed menu options | In Advanced settings → Player, enter 1–12 speeds from 0.1× to 8× (up to two decimals, separated by commas or spaces); the menu lists them from highest to lowest. Mutually exclusive with Default playback speed, with a prompt when switching; "Follow Bilibili" restores the original menu |
| Long-press speed | Customise the long-press speed, or disable long-press acceleration; independent of the speed menu |
| Unlock background playback / mini player / casting | Three independent switches, each unlocking the matching playback capability |
| Video buffering strategy `Experimental` | Follows the host by default; choose minimal buffering, a custom 1–600 second forward buffer or whole-video prefetch, with memory / disk budgets and a Wi-Fi-only option. Whole-video prefetch only attempts a recognisable current MP4/FLV single-file video; with DASH/HLS/DRM, unknown size or insufficient space a bounded cache is kept. Actual forward duration and offline availability still need validation |

#### Video frame interpolation `Experimental`

On supported Qualcomm devices, interpolation uses QCOM hardware motion estimation first. QCOM works without a model download. Other Vulkan GPUs and streams outside the QCOM path can use the RIFE v4.6 model downloaded in settings. BIL checks hardware-buffer interoperability and measures the available processing sizes and synchronization modes against the frame budget. Enable interpolation status prompts to see which backend is active.

- The player accepts video up to 4K. Doubling a 30 fps source targets 60 fps. Actual presentation depends on the GPU, driver, display and workload. HDR, protected content and unsupported formats use normal playback.
- Higher resolutions add a little delay (audio stays in sync) and use more battery.
- You can set a **frame-rate ceiling** (the real target follows what the system actually outputs and what the phone can sustain), turn on **interpolation status prompts**, and disable thermal control (with a confirmation first).

#### Subtitles

| Feature | Description |
| --- | --- |
| Enhanced subtitle styling | In the player's subtitle settings, adjust font, size, text, backdrop and outline; styles are stored locally in Bilibili |
| Try forcing AI multilingual subtitles | Turns on the client's AI-subtitle rollout flag and requests video subtitles; the languages available depend on the tracks the official server provides, and not every video has a translation |

#### Audio rescue `Experimental`

Processes the audio of the current video; off by default.

| Option | Description |
| --- | --- |
| Sudden peak guard | Suppresses sudden loud peaks immediately and limits the sample peak to −6 dBFS, then recovers smoothly; independent of overall loudness levelling, and the actual headphone volume is still yours to control |
| Channels | Original, mono (left and right mixed to both ears; out-of-phase sound can cancel) or auto (fills in sound when only one side stays loud or the other is almost inaudible) |
| Speech denoising | Reduces noise floor and ambient interference in speech videos; may change the timbre, so turn it off for music-focused videos |
| Voice clarity | Reduces background music covering speech, already includes denoising; suited to speech videos with overly loud BGM |

Speech denoising and voice clarity need optional files (about 56 MB on first download, or import them offline); peak protection and channel repair work without them. The two add about 33 ms of delay. Multichannel and passthrough audio are not supported.

#### Sponsor segments · SponsorBlock

| Feature | Description |
| --- | --- |
| Sponsor segment prompts | Prompts community-marked sponsor segments on ordinary video detail pages (markings may be inaccurate). Queries the community service `www.bsbsb.top` using a video ID hash prefix; no submissions, votes or account data are sent |
| Automatically skip sponsor segments | Requires prompts to be enabled; pause and manual scrubbing take priority, and a confirmed skip can be undone for 6 seconds |
| Also skip sponsor segments in short videos | Short (portrait) videos get prompts or skipping too; one query per short video you linger on (at most 12 per minute); only markings on ordinary uploads, never ads, and coverage is small |

### 💬 Danmaku, live rooms and the portrait feed

#### Danmaku

Danmaku filtering is independent of "Hide player interactive overlays"; the two can be enabled separately.

| Feature | Description |
| --- | --- |
| Filter danmaku by weight | The server assigns each danmaku a weight from 1 to 10 (higher is better); after setting a minimum kept weight, danmaku below it are removed before playback. When Bilibili delivers no weights, the whole danmaku set is kept as is |
| Hide premium gradient danmaku | Removes the gradient colouring; the danmaku themselves still show in ordinary colours |
| Smart danmaku filter (AI) `Experimental` | See [Smart filtering](#-smart-filtering-and-ai-features-experimental) |

#### Live rooms

| Feature | Description |
| --- | --- |
| Disable swiping between live rooms | Disables swiping up or down to switch to another room |
| Double-tap pauses instead of liking | Double tapping the live video pauses or resumes playback; when the playback state cannot be switched, the original like still happens |

#### Portrait video feed

The portrait continuous-play screen can filter the following categories independently:

**Ads · Live · Game promotions · Anime and film · Courses · Short dramas · Shopping · Movies · Documentaries · TV series · Variety shows · Music**

Categories are judged by explicit structured types; anything that does not match passes through, and ordinary portrait videos stay as they are. You can also hide the like, comment, coin, favorite and share icons and the danmaku toggle on the side of the feed.

### 💭 Comments

#### Interface cleanup

| Feature | Description |
| --- | --- |
| Disable comment keyword search links | Keywords in comments no longer jump to search; normal links are kept |
| Hide the empty-comment prompt | Hides the one-tap send prompt in the "no comments yet" state |
| Hide comment vote widgets | Hides polls, side-picking and similar widgets in comments |
| Hide comment follow buttons | Hides the follow button in comments |
| Disable comment experience feedback | Hides comment experience feedback, questionnaires and similar content |
| Hide comment promotions | Filters recognised promotional content in the comment section data |
| Hide comment reply buttons | Hides the Reply control in comment action bars; the "N replies" entry, input bar and three-dot menu stay |
| Disable tap-to-quick-reply | Briefly tapping a comment body or card no longer opens the reply editor directly; the explicit reply button, bottom input bar, three-dot menu and long-press free copy work as usual |
| Hide the comment section | Removes the comment tab from the video detail page |

#### Content filtering

Filtering covers top-level comments, pinned comments and nested reply lists, and is independent of free copy; the two can be enabled separately.

| Feature | Description |
| --- | --- |
| Filter comments by keyword | Up to 64 keywords, separated by commas, full-width commas, semicolons or newlines; a comment or reply is hidden when its body contains any keyword, ignoring letter case |
| Filter comments by user level | Set a minimum kept level; when a user level cannot be read reliably the comment is kept, to avoid deleting anything by mistake |
| Hide mention-only comments | Hides a comment whose body contains nothing but an @ mention; when the mention list cannot be read the comment is kept |
| Filter comments by author | Each rule entry is one UID or username (usernames must match exactly); when the author cannot be identified the comment is kept |
| Hide AI comments and replies | Scores nickname, level, text features, how closely a reply repeats its root comment and already-loaded public profile data offline, hiding comments and replies from suspected AI spam accounts; three sensitivity levels, custom nickname words, comment words and an allow list, accounts at Lv5 and above protected by default, plus a strong mode; everything is computed on the device, and a comment is kept when its nickname, level or text cannot be read. Rules and weights are ported from dafeiyu-cleaner (MIT); see [docs/comment-ai-filter.md](docs/comment-ai-filter.md) |
| Smart comment filter (AI) `Experimental` | See [Smart filtering](#-smart-filtering-and-ai-features-experimental) |

#### Reading and layout

| Feature | Description |
| --- | --- |
| Reply context map | Adds a "Trace" entry to top-level comments with replies to inspect reply relationships and locate comments; filter by author or reply keywords and copy the visible reply previews; the tree loads all available replies in the thread in batches, selecting a node highlights its ancestors and descendants, and you can pan and zoom. Data is loaded only while the panel is open |
| Restore classic comments | Under Enhancements → Advanced enhancement settings → Comment reading and interaction. Restores the classic comment layout; when a comment has replies but no preview, fetches up to three replies through Bilibili's detail API and shows them below the parent comment, with more replies one tap away. Only the first detail page is requested, with at most four concurrent requests and a 2.5-second wait budget for the entire comment page; timeouts or failures keep the original reply entry, and fetching may increase loading time |

### 📋 Free copy

Once enabled, long-pressing text pops up a copy bubble (tap outside it to close). Keep long-pressing and drag the system selection handles to select just the fragment you need, then copy it through the system floating toolbar — no need to copy the whole text.

| Surface | Description | Default |
| --- | --- | :---: |
| **Comments** | Long-press a comment body. Covers ordinary comments, long comments, expanded content and some reply lists; the complete original text (with its line breaks) is read from the comment data where possible, rather than the abbreviated collapsed text; Bilibili's own emoji are converted to readable text where possible | On |
| **Video descriptions** | Long-press the description on a video detail page. Expandable descriptions are read in full, with line breaks normalised, trailing whitespace trimmed and icon placeholder characters removed, keeping the paragraph structure | On |
| **Video titles** | Long-press the title on a video detail page; turn it on separately in the free copy group of the Enhancements page. A collapsed long title is copied in full (placeholder characters of the badge image in front of the title are dropped); when the original cannot be read, the text shown on screen is used | Off |

- The long-press time matches the system long-press time; scrolling and multi-finger gestures do not trigger it, and the official tap-to-expand or collapse behaviour of the title stays as it is.
- Official interaction areas such as images, buttons, media containers and the three-dot menu keep responding as before; after closing the bubble on a comment with images, the image can still be tapped to open its preview.

**Bubble appearance**

| Appearance | Description |
| --- | --- |
| Default dark | The default colour scheme; no configuration needed |
| Manual light | White background with black text, for light themes |
| Auto follow | Follows the Bilibili light/dark theme; enabling it overrides the manual light setting |

### 🤖 Smart filtering and AI features (experimental)

The features below are off by default and send content to the API services you configure yourself — **please understand their data flow and billing first**. API keys stay on this device and are excluded from settings backups and diagnostics.

#### Smart filtering (AI semantic judging)

Configure API sources under "Experimental features → Compatibility → AI semantic judging" (service type, model, endpoint, key; JEV and OpenAI-compatible providers are supported, including third-party relays). Up to 8 sources are supported, with bounded load balancing and failure cooldown, and each filter can pick its own source.

| Filter | Description |
| --- | --- |
| Smart dynamic filter | Hides lottery posts, sponsored or shopping promotions and flame bait by meaning |
| Smart comment filter | Hides comments of the selected types by meaning |
| Smart recommended-video filter | Judges video titles in the home feed and related recommendations; a title is judged once and remembered for both places |
| Smart danmaku filter | Hides danmaku of the selected types by meaning; always waits for judging, so danmaku may appear a moment later while video playback is not delayed; identical danmaku are judged once and at most 300 distinct lines per segment are judged |

- Smart filtering works **on top of the rule filters and only removes more**; if no source is configured, a request times out or fails, only the rules apply, and turning it off and restarting removes every judgement.
- Adjustable sensitivity (low / medium / high), custom blocked types, judging guidance, and whether to wait for judging on first load (on: the first screen is already filtered but each load is about 1 to 2 seconds slower; off: content shows at once, judging runs in the background and applies from the next refresh).
- **Privacy**: the text to be judged (never your account, cookies or user ID) is sent to the judging sources automatically in the background; with several sources the same content may go to more than one and be billed more than once.

#### AI Insight video assistant

Adds Summary, Ask, Topics and Chapters to the detail page, generated only when tapped. It sends the current part's subtitles, title, description and up to 12 loaded comments and danmaku to the dedicated model configured in "AI Insight service config" (Topics reads up to 40 comments); without subtitles only metadata is used, and results are cached on this device. Native chapters need subtitles, a video of at least 3 minutes and no official chapters. This configuration is independent of the AI semantic judging sources.

#### AI task assistant

Search Bilibili, look up video information or operate the current interface from a natural-language goal, with capability-tested collaboration across multiple sources.

- Configure and tick API sources under "Module → Experimental features → Compatibility → AI task assistant", then enter a goal once the capability test passes; the original business tools need a Bilibili restart after first enabling them, while general interface actions also need the module accessibility service enabled.
- Task time and steps are adjustable; status, logs and stopping are available from the task notification. General actions stop when you switch to another app or lock the screen, and sensitive operations such as passwords, identity verification and payments are protected.
- Your goal and the necessary on-screen content go to the selected model, and capability tests and tasks consume API quota. Task logs keep only redacted status and usage, never goals, keys, screenshots or raw responses.

### 🎨 Appearance and beautify

Visual materials and motion are provided by [Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine).

#### Top and bottom bars

| Feature | Description |
| --- | --- |
| Floating top / bottom bar | Turns the top or bottom bar into a floating capsule with a live-sampled glass material that shows the content beneath; personalised theme artwork blends in automatically. Slightly raises rendering load |
| Glass material | The top and bottom bars can separately use soft light, transparent glass or liquid glass, with a choice of rendering mode |
| Compact bottom bar / icon-only bottom bar | Require the floating bottom bar; shrink the capsule height, or hide labels and keep icons only; the two can be combined |
| Touch glow | Touching a top-bar tab or bottom-bar icon blooms an adaptive glow that deforms in real time with your touch point |
| Accent-colored tab badges | Requires the floating bottom bar; badges use the accent colour and digits automatically pick black or white for contrast |
| Dim profile theme artwork `On by default` | Requires the floating bottom bar; dims the theme artwork behind the "My" page to improve text contrast |

#### Pages and cards

| Feature | Description |
| --- | --- |
| Host page background | Backgrounds for native pages including home, dynamic feeds, comments, details, profiles, history, favorites, search, messages and settings. Presets: Aurora, Sakura haze, Ocean glass, Peach sunset, Forest mist, Quiet starfield, Dream nebula, Meteor dusk, or a custom image; images stay on this device and settings backups include effects only |
| Floating video cards | Adds large rounded corners and subtle soft shadows to recommendation and dynamic cards, with wider two-column spacing and screen-edge margins; recommendation corners are configurable from 0–40dp, dynamic cards use the 16dp preset. Located in advanced enhancement settings |
| Card material | Sets the material of video, anime & film and dynamic cards together, original by default; requires Floating video cards |
| Home card spacing | 0–40dp horizontally / vertically, blank keeps the default; usable on its own and affects only the home page |
| Host UI power saving `On by default` | Reuses glass and card materials while scrolling to cut power use; occasionally a frame arrives late, so turn it off if it feels less smooth |

#### Splash and module UI

| Feature | Description |
| --- | --- |
| Clean up splash ads | Filters splash ads |
| Skip brand splash | Skips Bilibili's brand display; the system launch screen still shows |
| Official splash selection | Choose among the official splash screens provided in Bilibili's settings, including premium material (no digital collectible benefits); the material must finish downloading first, and it shows once "Skip brand splash" is off |
| Match the splash screen to dark mode | The splash background follows dark / light mode (black / white), so switching from a dark UI no longer flashes white; the splash image and logo are unchanged |
| Module UI | UI aesthetics are fixed to Soft light; "advanced material" adds liquid glass (full-screen live sampling by default), panel window blur and the SPEC 2025 colour specification are optional, with appearance settings collected under "Experimental features → Appearance" |

### 🔒 Privacy and browsing

| Feature | Description |
| --- | --- |
| Browse without login | When signed out, hides login prompts but keeps manual login; history, favorites and follows stay on this device; signed-in accounts are unaffected and video quality is limited to what guests can get; incognito takes priority when both are on |
| Incognito mode | Uses a temporary guest session; turn it off and restart to get your account back. Session history, favorites and follows stay in memory and are discarded when the session ends; embedded pages stay offline so account web credentials are not reused; some live interactions and real-time updates are unavailable, and the server may still log visits |
| Skip basic information and interest setup | When any browsing mode is on and you are a guest, skips the gender, age and interest selection and goes straight to the home page, without filling in any profile |
| Clean up shared links | Removes tracking parameters from Bilibili links in the share panel link and share text, keeping only the page number and timestamp; **b23.tv short links are not expanded**, and no network request is made |
| Share mini-program cards as plain links | When sharing to WeChat or QQ a plain link card is sent instead; when the title only contains the app name, the real video title is filled back in and no attribution text is appended |
| Share to re-signed WeChat | Enable only if you trust the re-signed WeChat on this device. Keeps native friend and Moments sharing by skipping the WeChat certificate comparison for Bilibili's sharing instances; login and payment checks are unchanged. Supports audited Bilibili builds from 8.84.0 to 9.18.0; other builds keep their original behaviour |
| Open external links in the system browser | Web pages outside Bilibili no longer open in the built-in browser; Bilibili domains plus payment and UnionPay gateways stay inside the app, and when no browser is available the original behaviour is kept |

### 🧩 System and remote flags

| Feature | Description |
| --- | --- |
| Disable client update prompts | Blocks the Bilibili client's own update reminder; this module's update checks are unaffected |
| Disable the teen-mode prompt | Hides the teen mode notice page; teen mode's own state and rules are unchanged |
| Show full numbers | Counts such as views and likes show the full value instead of abbreviations like "wan" and "yi" |
| Show AV numbers instead of BV | Bilibili shows either an AV number or a BV number at the same place; enabling this always shows the AV number, and BV links in the body are still recognised and clickable |
| Use the system media notification | Enables the background playback system media-style notification that Bilibili ships but has switched off in its gradual rollout |
| Keep the legacy home frame | Blocks the remote flag that moves phones to the new home frame, so features built on the legacy home keep working; large screens are unaffected |
| Freeze chosen remote flags | Pages and buttons are often switched by remote flags and can appear or vanish overnight. Scan the on/off flags Bilibili reads and tick the ones to pin, or write rules as `key=value`; Bilibili then reads those fixed values and every other flag stays untouched. Network, sign-in, risk-control and startup flags cannot be frozen. See [docs/cloud-key-freeze.md](docs/cloud-key-freeze.md) |
| Block App component-library downloads | Pick the resource pools to block; blocked pools are no longer downloaded and Bilibili deletes what was already downloaded for them when it next refreshes its resource manifest, so the matching features lose their resources |

### 🌍 BiliRoaming version support extension

Works together with an installed and enabled BiliRoaming module, and provides no accounts, access keys or network proxy services of its own. It does not and will not provide any server-side parsing; look elsewhere if you need that.

When a newer Bilibili client changes its internal structure, an older BiliRoaming may fail to analyse it. Once the extension is enabled, this module repairs known problems and helps BiliRoaming rebuild a complete cache, so that its settings entry, page customisation and related features keep working on adapted versions:

- Re-analyses when the cache is damaged, expired or the client version changes, and cuts repeated full analyses and cold-start waiting;
- Adds a BiliRoaming settings entry on the "My" page;
- Keeps switch state in sync between the module and BiliRoaming;
- Handles app visibility and background launch restrictions in some MIUI/HyperOS environments;
- Restores native behaviour once the feature is turned off.

**Under NPatch 833**, when the APK contents, versions and manual rebuild generation of the host and BiliRoaming all match, a verified analysis cache is reused or restored, and an installation-time change caused only by re-patching needs no new full analysis; the first build, changed contents, a damaged cache or a manual rebuild still need analysis. First use "Check NPatch BiliRoaming cache" in the compatibility settings, and continue through the existing restart guide once a ready receipt arrives. A ready cache only means the analysis is done; it does not mean every BiliRoaming feature is in effect.

### 🔧 Module management and maintenance

| Feature | Description |
| --- | --- |
| Activation status | The module home page shows whether Xposed/LSPosed has activated it and, where available, the framework name and API version; if it shows as not activated, check the module switch, whether the scope includes Bilibili, and whether the target process has been restarted |
| One-tap restart of Bilibili | Saves the current settings first, then restarts in the order Root → Shizuku → manual; when automatic restart is not possible it guides you to the system details page to force-stop, then confirms the reopen with a receipt from the current process |
| Settings backup and restore | Exports module settings to a local file; before restoring, the file is validated and a compatibility preview is shown, and settings are written only after you confirm. App language, update channel, activation and scope state, logs and runtime caches are not included |
| Diagnostics Center | Read-only diagnostics such as activation, framework service and per-feature installation and hit status, to help locate problems; diagnostics never carry body text, accounts, setting values or tokens |
| Log settings | Log capture can be enabled independently and comes in "concise" and "full" levels: concise keeps notable errors and key runtime problems, while full adds hook registration, feature hits and interception information. Before publishing logs, check them yourself for client version, class names and runtime state |
| Update check | Supports Stable, Preview (including Alpha) and Canary channels, each checked infrequently, with a fallback mirror when the network fails; the module never installs an APK silently in the background, and downloading, installing or using a test build is up to you |
| Hide the launcher icon | Hides the module's launcher icon; the settings can still be reached from the LSPosed manager afterwards. If LSPosed has "force show launcher icon" enabled, turn that option off first |
| App language | The module UI supports Follow system, 简体中文, 繁體中文 and English |
| Predictive back animation `Experimental` | Going back from the module settings uses the system's predictive back transition preview. Android 14–15: an experimental switch; Android 16 and later: forced on by the system, so the switch is no longer shown (a saved preference is kept so old backups can be imported). Some vendor systems may modify or disable the animation |

#### Automatic version adaptation

After a Bilibili update, obfuscated class names, method signatures and page structures can change. The module relocates the entry points each feature needs from class structure, field types and method parameters, and caches the result; while the client version, APK and adaptation rules are unchanged, later launches use the cache directly. Each feature is installed and diagnosed independently.

Automatic adaptation only covers client versions with a similar structure; after a heavy host refactor, some features have to wait for a module update.

**If features stop working after upgrading Bilibili:**

| Step | Action |
| :---: | --- |
| **1** | Open the module settings and go to "Experimental features → Compatibility" |
| **2** | Choose "Re-adapt to the current version" and confirm clearing the records |
| **3** | Restart Bilibili; the module relocates the feature entry points for the current version on the next launch |

---

## Release channels

| | **Stable** | **Alpha pre-release** | **Canary** |
| --- | --- | --- | --- |
| **Purpose** | Everyday use | Early validation of new features, adaptation to new clients and testing of compatibility fixes | Follows the latest `main` development for quick validation of new features and fixes |
| **Update check** | The built-in check receives official Releases only by default | The Preview channel also compares official releases against Alpha pre-releases that follow the semantic version format | The Canary channel checks the most recent successful, unexpired Actions build produced by a `main` push |
| **Where to get it** | [Latest release](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/latest) | Alpha pre-releases in the [release list](https://github.com/jichuo1/Bilibili_Innocent_Lab/releases) | [Canary builds](https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/workflows/canary-build.yml) or the [Telegram release channel](https://t.me/Bilibili_Innocent_LabRelease) |
| **Recommendation** | ✅ Recommended for everyday use | May contain changes not yet widely validated; not recommended for long-term use on devices where you cannot export logs or restore an older version | Updated frequently with limited validation; for testing and feedback, prefer Stable for everyday use |

**Getting Canary**: choose Canary in the module's "update channel"; once an update is found, download it through the Telegram or Actions entry in the prompt. When using Actions directly, pick a successful run, download the `Bilibili_Innocent_Lab-vX.Y.Z-canary.N-<short commit SHA>` archive from Artifacts, unzip it and install the APK inside; downloading requires a GitHub sign-in, build artifacts are kept for 30 days, and the archive also contains build information, a SHA-256 checksum file and the change log.

**Release cadence**

- **Alpha** is published to GitHub Releases through a workflow once the developer confirms it.
- **Canary** is built automatically after a push to `main` and can also be triggered manually; artifacts are kept in Actions.
- The **Telegram release channel** syncs the latest Canary that passed its checks every day at 22:00 Beijing time; it is skipped when a manual run happened that day, one was already delivered that day, or the build is identical to the last release, and the developer can also pick a successful build to sync manually at any time. Scheduled runs may queue, so the channel version can lag behind the newest Actions build.
- **Version numbering**: Alpha and Canary both use the next patch version after the current stable one as their prefix. For example, while stable is `1.2.0`, Alpha uses `1.2.1-alpha.N` and Canary uses `1.2.1-canary.N` (Canary's `N` is the build sequence number). After `1.2.1` is released, development versions move on to the `1.2.2` prefix.

---

## FAQ

| Question | How to troubleshoot |
| --- | --- |
| The module shows as not activated | Make sure LSPosed has this module enabled and the scope includes the official Bilibili client; after changing the scope, restart the target app and reboot the device if needed |
| The host cold-starts very slowly, or the module cannot communicate with it | Try turning off host hiding in HMA-OSS |
| The libxposed service is not detected in a twin app | The framework delivers the service per Android user: install the module and enable it for the user running the twin client in the manager as well, then open the module settings once. Renamed clones and VirtualApp-style containers are out of scope, see [Cloning and multi-user](#cloning-and-multi-user) |
| A switch is on but the feature does not work | Force-stop and restart Bilibili first; right after a client upgrade try "re-adapt to the current version"; after the BiliRoaming extension's first analysis, check the cache status first and then restart as the ready prompt says |
| Long-pressing a comment does not show the free copy bubble | Make sure the comment free copy switch is on and the current page is an adapted official comment section; if only a few comments fail, note the comment type, whether it has images, whether it is in a reply list, and the client version |
| Images cannot be opened after using free copy | The current version specifically protects the media container of comments with images; check that you are on the latest build and fully restart the Bilibili process. If it still reproduces, describe the order of operations and the delay between closing the bubble and tapping the image |
| Text cannot be selected or copied inside the bubble | Make sure no other module also hooks the system clipboard, the text selection toolbar or PopupWindow; some vendor systems deeply customise the text selection component — if it crashes, provide the system version and the crash log |
| The home carousel still appears | The home layout can be affected by account experiment groups; provide the client version, a screenshot of the home layout and full logs, rather than only describing the feature as "not working" |
| The BiliRoaming extension is enabled but problems persist | Provide the versions of Bilibili, BiliRoaming, this module, LSPosed (including the branch) and Android/ROM, the cache check result and number of restarts, and the relevant LSPosed logs for the target main process |
| What to do about conflicts with other modules | When several modules modify the comment section, video detail page, clipboard, theme attributes or the BiliRoaming cache at the same time, execution-order conflicts can occur; temporarily disable the other modules and reproduce with only this one, then restore them one by one once this module alone behaves correctly |

---

## Reporting issues

Before filing an issue, please carry out the following checks where possible; clear, reproducible steps are far easier to act on than a bare "it does not work":

1. Use the latest stable version, or state which Alpha / Canary version you are running with the matching release or build link;
2. Confirm the problem still reproduces with only this module enabled;
3. Note the versions of Bilibili, Android, LSPosed and the module;
4. Describe the state of the specific feature switches;
5. Give the full sequence of steps from entering the page to the problem appearing;
6. Attach screenshots, screen recordings or LSPosed logs where necessary;
7. For crashes, provide the full stack trace from the crash buffer where possible;
8. **Do not publicly upload accounts, cookies, access keys or other personal sensitive data.**

---

## Notices and disclaimers

This chapter collects the project statements, the privacy note and the disclaimers in one place. **Please read it in full before downloading, installing, compiling, distributing or using this project.**

### Project statements

**To keep the project maintainable, please do not broadcast project-related content in any form — including but not limited to files, text, icons, links, images, videos and code — to non-specific audiences on public social media.**

**This is a non-profit project. It never charges for software or services, and it never collects or transmits your personal data without consent. If you suspect a file obtained from another channel contradicts these principles, always compare it against the Release hash of the matching version. If the hashes differ, the file may have been tampered with by a third party; this project cannot vouch for its safety, and if you know this and keep using it, you bear all resulting risk and consequences.**

### Privacy

The module's logic runs mainly on the local device and inside the target app process. Runtime logs are written to the LSPosed log system, and the update check fetches GitHub Release information. The module does not log in to Bilibili on your behalf and never asks you to send account credentials to this project.

Before exporting or sharing logs, you should still check them yourself for video links, device information, app versions or anything else you would rather not make public.

### Disclaimer

This project is an unofficial, non-commercial personal study and research project, and has no affiliation, authorisation, cooperation or endorsement relationship with Bilibili or its affiliates.

Using an Xposed/LSPosed module can change how the target app behaves and may be affected by client updates, system security policy, vendor ROMs, account experiment groups or other modules. You should assess the risk yourself and take responsibility for installation, enabling, data backup and your device environment.

This project provides no content resources, takes no part in account trading, offers no access credentials, and does not guarantee the availability of third-party extension services. Use it in compliance with the laws of your jurisdiction, the target platform's rules and the applicable open-source licences.

### Additional Disclaimer and Risk Notice

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

---

## For developers

<details>
<summary><b>Building</b></summary>

<br>

The Gradle project lives in `Bilibili_Innocent_Lab/`, which differs from the repository root.

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

The fast check enables the same rules as the full check; it differs only in its report directory and the tasks it may run alongside. Test sources are verified by compilation and tests, and are excluded from Lint. Before committing and in CI, run:

```powershell
.\gradlew.bat :app:lintDebug --console=plain --no-daemon
```

Fast reports are saved under `app/build/reports/lint/fast/`, and full reports under `app/build/reports/lint/full/`; both HTML files are named `lint-results-debug.html`. Use full task names. Only `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest` may accompany `lintFast`; run other tasks and full gates separately. On Linux / macOS, use `./gradlew` with the same tasks.

Use `--no-daemon` consistently while retaining dependency and task caches. Avoid routine `clean`. Add `--offline` when dependencies are cached.

**Verifying the artifact**

The debug APK is written to `Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk`.

Confirm that Gradle completed successfully and use the APK from that build. `UP-TO-DATE` and `FROM-CACHE` are valid incremental results; APK modification time alone does not prove whether an artifact includes the current inputs. After installation, compare the SHA-256 of the local APK with the installed `base.apk` to confirm that the intended artifact was installed.

</details>

<details>
<summary><b>Performance and compatibility design</b></summary>

<br>

The module's hooks run inside the target app process, so high-frequency callbacks only do the minimum lightweight checks.

| Aspect | Approach |
| --- | --- |
| **Hot paths and scheduling** | Fast-return conditions on scrolling, touch and text-binding paths; comment binding uses idle scheduling and batching |
| **Caching and lifecycle** | Methods and fields obtained through reflection are cached where possible; pages, dialogs and comment views are held through weak references; asynchronous tasks hold only the Application Context; adaptation results for a new version are written to a cache so scanning is not repeated on every launch |
| **Compatibility and exemptions** | Cross-process switch reads have several fallback levels; identity or origin exemptions are set for the module's own dialogs, the system selection handles and host media containers; explicit configuration is used for window, status bar and theme attributes that differ markedly between ROMs |

For entry points with a wide blast radius such as PopupWindow, Dialog, the clipboard, broadcasts and touch events, the module steps in only when object identity, call origin and gesture lifecycle all match, avoiding interference with image previews, system text selection and other host features.

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
- [Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine) (visual materials and motion for host and module surfaces, Apache-2.0)
- [BBZQ](https://github.com/HSSkyBoy/BBZQ)
- The Xposed / LSPosed community
- The Android Open Source Project and related developer documentation
- Everyone who tested, reported compatibility problems and provided logs

If this project helps you, you are welcome to file reproducible problems, compatibility information or improvement suggestions through an issue.
