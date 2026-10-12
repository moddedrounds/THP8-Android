param([string[]]$Args2, [string]$Tag, [int]$Seconds = 20)
# Paused-world benchmark: GPU busy % (kgsl, sampled every 0.2 s) and frame stats, no skating noise.
$sp = $PSScriptRoot
$old = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
$f = '/sdcard/Android/data/org.project8recomp.android/files'
& "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
Start-Sleep 4
& "$sp\pad.ps1" -Keys START -Wait 2 | Out-Null
Start-Sleep 25
$n = $Seconds * 5
$raw = & $adb shell "i=0; while [ `$i -lt $n ]; do cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage; sleep 0.2; i=`$((i+1)); done"
$vals = $raw | ForEach-Object { if ($_ -match '(\d+)') { [double]$Matches[1] } }
$avg = ($vals | Measure-Object -Average).Average
cmd /c "`"$adb`" pull $f/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
$rows = Import-Csv "$d\$Tag.csv"
$last = $rows | Select-Object -Last ([int]($Seconds * 25))
$ft = $last | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
$s = $ft | Sort-Object
"== $Tag ($($Args2 -join ' ')): GPU busy {0:N1}% over {1} samples | fps {2:N1} | p50 {3:N1} p90 {4:N1} | >45ms {5}" -f $avg, $vals.Count, ($s.Count / (($s | Measure-Object -Sum).Sum / 1000)), $s[[int]($s.Count*0.5)], $s[[int]($s.Count*0.9)], ($s | Where-Object { $_ -gt 45 }).Count
