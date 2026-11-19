param([string]$InputDirectory='captures/audio-benchmark/fixtures', [string]$OutputDirectory='captures/audio-benchmark/revised', [string]$RenderJava='C:/Program Files/Java/jdk-17/bin/java.exe', [switch]$Baseline, [string]$CaseFilter='^', [switch]$ReplayPriorNeural)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$cache='G:/src/Bilibili_Innocent_Lab/.gradle-user-home/caches/modules-2/files-2.1'
function CachedJar([string]$Group,[string]$Artifact,[string]$Version) {
    (Get-ChildItem -LiteralPath "$cache/$Group/$Artifact/$Version" -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
$stdlib=CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.4.21'
$annotations=CachedJar 'org.jetbrains' 'annotations' '26.0.2'
if(!$annotations) {$annotations=(Get-ChildItem "$cache/org.jetbrains/annotations" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName}
$jars=@('kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-reflect') | ForEach-Object {
    $version=if($_ -eq 'kotlin-reflect'){'1.6.10'}else{'2.4.21'}
    CachedJar 'org.jetbrains.kotlin' $_ $version
}
$jars+=(Get-ChildItem "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$jars+=$annotations
$target=Join-Path $root 'captures/audio-benchmark/renderer.jar'
$source=Join-Path $root 'Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/audio'
$onnx=Join-Path $root 'captures/audio-benchmark/onnxruntime.jar'
if($Baseline) {
    $snapshot=Join-Path $root 'captures/audio-benchmark/baseline-source'
    New-Item -ItemType Directory -Path $snapshot -Force | Out-Null
    foreach($file in @('AudioRescueConfig.kt','AudioRescueProcessor.kt')) {
        $text=git -C $root show "c1c91b50:Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/audio/$file"
        if($LASTEXITCODE) {throw 'Unable to read baseline revision'}
        [IO.File]::WriteAllText((Join-Path $snapshot $file), ($text -join "`n"), [Text.UTF8Encoding]::new($false))
    }
    & 'C:/Program Files/Java/jdk-17/bin/java.exe' -cp ($jars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations" -jvm-target 17 -d $target "$snapshot/AudioRescueConfig.kt" "$snapshot/AudioRescueProcessor.kt" "$PSScriptRoot/RenderBaseline.kt"
    if($LASTEXITCODE) {throw 'Baseline compilation failed'}
    & 'C:/Program Files/Java/jdk-17/bin/java.exe' -cp "$target;$stdlib" com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.RenderBaselineKt (Join-Path $root $InputDirectory) (Join-Path $root $OutputDirectory)
    if($LASTEXITCODE) {throw 'Baseline render failed'}
    return
}
& 'C:/Program Files/Java/jdk-17/bin/java.exe' -cp ($jars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations;$onnx" -jvm-target 17 -d $target "$source/AudioRescueConfig.kt" "$source/AudioRescueProcessor.kt" "$source/AudioSpeechEnhancer.kt" "$source/GtcrnSpeechModel.kt" "$PSScriptRoot/Render.kt"
if($LASTEXITCODE) {throw 'Production DSP compilation failed'}
& $RenderJava -cp "$target;$stdlib;$onnx" com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.RenderKt (Join-Path $root $InputDirectory) (Join-Path $root $OutputDirectory) (Join-Path $root 'captures/audio-benchmark/sources/gtcrn.onnx') $CaseFilter $(if ($ReplayPriorNeural) { 'prior-neural' } else { 'current' })
if($LASTEXITCODE) {throw 'Production DSP render failed'}
