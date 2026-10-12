param([int[]]$Scales = @(100, 85, 75, 60, 50), [double]$WalkSeconds = 5, [string]$Tag = 'rs',
      [string[]]$Extra = @())
# Same spot at several internal resolutions: launch at each scale (60 fps setup), walk the skater
# out of the garage spawn with the stick held forward for $WalkSeconds (deterministic from a fresh
# spawn), stand still, screenshot runs\<tag>_<scale>.png.
$s = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = '/dev/input/event9'
foreach ($sc in $Scales) {
  $a = @('--gpu_timestamp_queries=true', '--gpu_swap_fps_cap=60', '--android_affinity_mode=2', '--gpu_shadow_skip_level=1',
         '--gpu_frame_generation=false', "--android_render_scale=$sc", '--present_effect=fsr', '--gpu_vblank_hold_us=15000') + $Extra
  & "$s\to_gameplay.ps1" -Args2 $a -Tag "${Tag}_$sc" | Select-Object -Last 1
  Start-Sleep 5
  & $adb shell "sendevent $d 3 1 -30000; sendevent $d 0 0 0; sleep $WalkSeconds; sendevent $d 3 1 0; sendevent $d 0 0 0"
  Start-Sleep 4
  cmd /c "`"$adb`" exec-out screencap -p > `"C:\thp8-work\android\runs\${Tag}_$sc.png`""
  "scale $sc -> ${Tag}_$sc.png"
}
