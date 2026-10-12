param([string[]]$Variants = @('--gpu_relaxed_interpolators=false', '--gpu_relaxed_interpolators=true'),
      [string[]]$Base = @('--gpu_timestamp_queries=true', '--gpu_swap_fps_cap=60', '--gpu_frame_generation=false', '--android_render_scale=100', '--present_effect=fsr', '--android_affinity_mode=2'),
      [int]$Rounds = 2, [int]$Window = 15, [switch]$ClearCaches, [switch]$Skate)
# A/B of startup-only settings: one launch per variant (alternating, $Rounds times), optionally
# deleting the shader / pipeline caches first (for settings that change translated shaders but
# not their cache keys). Measures the garage view (power_ab.ps1, 2 windows after a 40 s settle).
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$f = '/sdcard/Android/data/org.project8recomp.android/files'
for ($r = 0; $r -lt $Rounds; $r++) {
  foreach ($v in $Variants) {
    & $adb shell am force-stop org.project8recomp.android
    if ($ClearCaches) { & $adb shell "rm -rf $f/cache/*" }
    $a = @($Base) + @($v -split ' ')
    $out = & "$sp\power_ab.ps1" -Tag 'sab' -Args2 $a -Configs @('vsync=true') -Cycles 2 -Window $Window -Skate:$Skate
    "$v round $r : " + (($out | Select-Object -Last 1) -replace '^vsync=true\s*', '')
    cmd /c "`"$adb`" exec-out screencap -p > C:\thp8-work\android\runs\sab_r$r_$($v -replace '[^a-z0-9]', '').png"
  }
}
& $adb shell rm -f "$f/args.txt"
