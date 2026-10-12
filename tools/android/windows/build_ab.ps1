param([string[]]$Apks, [string[]]$Args2 = @('--gpu_timestamp_queries=true', '--gpu_swap_fps_cap=60', '--gpu_frame_generation=false', '--android_render_scale=100', '--present_effect=fsr'),
      [int]$Rounds = 2, [int]$Window = 15, [int]$Cycles = 2, [switch]$Skate)
# A/B between builds (changes that cannot be switched live): for each round, for each APK in order,
# install it, reach the garage and measure $Cycles windows (power_ab.ps1). Prints one summary line
# per launch and a per-APK average at the end. The garage spawn view is deterministic; use it.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$res = @()
for ($r = 0; $r -lt $Rounds; $r++) {
  foreach ($apk in $Apks) {
    & $adb install -r $apk | Out-Null
    $name = [IO.Path]::GetFileNameWithoutExtension($apk)
    $out = & "$sp\power_ab.ps1" -Tag "bab_$name" -Args2 $Args2 -Configs @('vsync=true') -Cycles $Cycles -Window $Window -Skate:$Skate
    $line = ($out | Select-Object -Last 1) -replace '^vsync=true\s*', ''
    "$name round $r : $line"
    $res += [pscustomobject]@{ apk = $name; line = $line }
  }
}
& $adb shell rm -f /sdcard/Android/data/org.project8recomp.android/files/args.txt
