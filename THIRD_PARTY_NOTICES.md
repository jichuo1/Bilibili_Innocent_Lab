# Third-party notices

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
[DexKit](https://github.com/LuckyPray/DexKit), version `2.0.7`, only as a
background fallback for bounded host DEX adaptation queries. DexKit is not
created from the synchronous `quickLocate` path or any installed Hook callback,
and each bridge is closed immediately after its query.

Copyright © LuckyPray

Except for DexKit's `Core/` directory, the upstream project is licensed under
the Apache License, Version 2.0. The native core is licensed under the GNU
Lesser General Public License, Version 3.0. See the upstream repository for the
corresponding complete license texts and source code.
