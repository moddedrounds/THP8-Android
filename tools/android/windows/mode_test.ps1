param([int[]]$Indexes = @(8, 7, 11), [string[]]$Extra = @('--gpu_swap_fps_cap=60', '--gpu_frame_generation=false', '--android_affinity_mode=2'), [int]$Window = 20, [switch]$Skate)
# One launch per display mode table entry (android_video_mode_index): reach the garage, measure fps /
# GPU busy / CPU / power for $Window seconds (power_ab.ps1), keep a screenshot runs\mode<i>.png.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
foreach ($i in $Indexes) {
  $a = @('--gpu_timestamp_queries=true', '--android_render_scale=100', "--android_video_mode_index=$i") + $Extra
  $out = & "$sp\power_ab.ps1" -Tag "mode$i" -Args2 $a -Configs @("android_affinity_mode=$(if ($Extra -match 'affinity_mode=2') {2} else {1})") -Cycles 1 -Window $Window -Skate:$Skate
  cmd /c "`"$adb`" exec-out screencap -p > C:\thp8-work\android\runs\mode$i.png"
  "index ${i}: " + (($out | Select-Object -Last 1) -replace '^android_affinity_mode=\d\s*', '')
}
cmd /c "`"$adb`" shell rm -f /sdcard/Android/data/org.project8recomp.android/files/args.txt"
