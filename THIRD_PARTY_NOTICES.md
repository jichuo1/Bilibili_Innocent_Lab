# Third-party notices

## Material Design Icons

The enhancement, appearance and compatibility section icons use Google's
[Material Design Icons](https://github.com/google/material-design-icons), pinned
to commit `0cbb08816df07faaae3dca060d4ebb10b66c214f`:

- `src/image/auto_fix_high/materialicons/24px.svg`
- `src/image/palette/materialicons/24px.svg`
- `src/action/extension/materialicons/24px.svg`

The SVG paths were converted to Android VectorDrawable resources with their
original 24-by-24 geometry. Non-drawing SVG bounds were omitted, and the fill is
white so the existing theme tint can be applied. No icon font or runtime library
is included. Licensed under the Apache License, Version 2.0; the complete upstream
license is provided in `third_party/MaterialDesignIcons-LICENSE.txt`.

## AndroidLiquidGlass

The experimental Liquid skin contains an Android View/Drawable adaptation of the
rounded-rectangle refraction shader from
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass), based on
upstream commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`.

Copyright 2025 Kyant

Licensed under the Apache License, Version 2.0. The complete license text is
provided in `third_party/AndroidLiquidGlass-LICENSE.txt`.

The adapted implementation has been changed to use Android Views and Drawables,
an Activity-scoped static Monet backdrop source, bounded bitmap storage, explicit
API-level backends, and the module's own lifecycle and recovery protocol. It does
not include the upstream Compose Multiplatform component or rendering framework.

## AndroidLiquidGlassView

The Liquid skin also adapts the safe rounded-rectangle gradient and linear-sRGB
color treatment from
[QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView),
version `1.0.5`.

Shader portions: Copyright (c) 2025 QmDeve

Repository license: Copyright ©️ 2025 Donny Yale

Licensed under the MIT License. The complete license text is provided in
`third_party/AndroidLiquidGlassView-LICENSE.txt`.

The module keeps its existing Activity-scoped static underlay, bounded bitmap,
Drawable integration, API 31 blur fallback, API 27 translucent fallback, and
renderer recovery protocol. It does not include the upstream View component or
its per-frame View-tree recording implementation.

## Markwon

The module uses
[Markwon](https://github.com/noties/Markwon), version `4.6.2`, to parse GitHub
Release Markdown and render it as Android-native `Spanned` text. Only the core
artifact is included; HTML, image loading, WebView, syntax highlighting and
other optional extensions are not included.

Copyright 2019 Dimitry Ivanov

Licensed under the Apache License, Version 2.0.

## DexKit

The module uses
[DexKit](https://github.com/LuckyPray/DexKit), version `2.3.0`, only as a
background fallback for bounded host DEX adaptation queries. DexKit is not
created from the synchronous `quickLocate` path or any installed Hook callback,
and each bridge is closed immediately after its query.

Copyright © LuckyPray

Except for DexKit's `Core/` directory, the upstream project is licensed under
the Apache License, Version 2.0. The native core is licensed under the GNU
Lesser General Public License, Version 3.0. See the upstream repository for the
corresponding complete license texts and source code.

## Meguviz libraries

The module includes these libraries as runtime dependencies:

- [MeguvizRef](https://github.com/jichuo1/MeguvizRef), version `1.0.0`
  (`meguviz-ref-core`), for reflective member lookup.
- [MeguvizUI](https://github.com/jichuo1/MeguvizUI), version `1.0.0`
  (`meguviz-ui-core`, `meguviz-ui-prewarm`), for building the native View settings
  UI.
- [MeguvizAndroid](https://github.com/jichuo1/MeguvizAndroid), version `1.1.0`
  (`meguviz-android-runtime`, `meguviz-android-window`), for Android runtime and
  window helpers.

Copyright © jichuo1

Licensed under the Apache License, Version 2.0. See each upstream repository for
its complete license text and notices.

## Lumen Coacervation Engine

The module uses
[Lumen Coacervation Engine](https://github.com/jichuo1/LumenCoacervationEngine),
version `1.6.1` (`lumen-engine`, `lumen-motion`), for the visual materials and
motion of host and module surfaces.

Copyright 2026 jichuo1

Licensed under the Apache License, Version 2.0. `lumen-engine` also contains
portions adapted from AndroidLiquidGlass (Apache License, Version 2.0),
AndroidLiquidGlassView (MIT License) and the Android Open Source Project (Apache
License, Version 2.0). Its AAR carries the complete records at
`assets/lumen/licenses/`.

## Android 边界拉伸采样坐标

- 来源：Android Open Source Project 的 `EdgeEffect.java` 与 `StretchEffect.cpp`。
- 用途：保持系统回弹动画，补偿前景玻璃中背景图片的采样位置。
- 版权：Copyright 2010, 2021 The Android Open Source Project。
- 许可证：Apache License 2.0（项目已有对应许可证全文）。
- 原始工程：https://android.googlesource.com/platform/frameworks/base/
