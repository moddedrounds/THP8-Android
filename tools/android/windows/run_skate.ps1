param([string[]]$Args2, [string]$Tag, [int]$Seconds = 45)
$sp = $PSScriptRoot
$old = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
& "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
Start-Sleep 25   # let the level finish streaming in
$mark = (Get-Content (Join-Path $env:TEMP 'dummy') -ErrorAction SilentlyContinue)
# the frames.csv keeps growing; remember how many rows exist so the skating part can be cut out
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/perf/frames.csv `"$d\$Tag.pre.csv`" > nul 2>&1"
$pre = (Get-Content "$d\$Tag.pre.csv" | Measure-Object -Line).Lines
& "$sp\skate.ps1" -Seconds $Seconds -ShotPrefix "$d\$Tag.s"
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/logs/thps_p8.log `"$d\$Tag.log`" > nul 2>&1"
cmd /c "`"$adb`" exec-out screencap -p > `"$d\$Tag.end.png`""
$all = Import-Csv "$d\$Tag.csv"
$sel = $all | Select-Object -Skip ($pre - 1)
$ft = $sel | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
$g = $sel | ForEach-Object { [double]$_.gpu_frame_us / 1000 }
$dr = $sel | ForEach-Object { [double]$_.draw_calls }
$s = $ft | Sort-Object
$c = $s.Count
if ($c -gt 10) {
  function P($q) { [math]::Round($s[[int][math]::Floor(($c - 1) * $q)], 1) }
  $tot = ($s | Measure-Object -Sum).Sum
  "== $Tag : $($Args2 -join ' ')"
  "skating window: {0} frames over {1:N1}s => {2:N1} fps | frame ms p50 {3} p90 {4} p99 {5} | >34ms: {6} ({7:P0}) >45ms: {8} | draws mean {9:N0} | gpu ms mean {10:N1}" -f $c, ($tot/1000), ($c/($tot/1000)), (P 0.5), (P 0.9), (P 0.99), ($s | Where-Object { $_ -gt 34 }).Count, ((($s | Where-Object { $_ -gt 34 }).Count)/$c), ($s | Where-Object { $_ -gt 45 }).Count, ($dr | Measure-Object -Average).Average, ($g | Measure-Object -Average).Average
}
