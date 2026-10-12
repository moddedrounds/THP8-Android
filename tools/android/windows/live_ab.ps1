param([string[]]$Args2 = @('--gpu_timestamp_queries=true'), [string]$Tag = 'ab', [int[]]$Flags = @(0, 128),
      [int]$Window = 15, [int]$Cycles = 3, [switch]$NoRelaunch, [switch]$Skate, [switch]$Idle, [string]$FlagFile = 'live_flags.txt')
# Live A/B in ONE game session: reach gameplay, pause (or skate with -Skate), then cycle the integer
# written to files/$FlagFile through $Flags, measuring a $Window-second window per value, $Cycles times.
# Prints per-window stats and a per-flag summary. Removes the flag file at the end.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
$f = '/sdcard/Android/data/org.project8recomp.android/files'
if (-not $NoRelaunch) {
  & "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
  Start-Sleep 4
  if (-not $Skate -and -not $Idle) { & "$sp\pad.ps1" -Keys START -Wait 2 | Out-Null }
  Start-Sleep 25
}
function Ticks($names) { $o = @{}; $pid2 = ((& $adb shell pidof org.project8recomp.android:game) -join '').Trim(); foreach ($l in (& $adb shell "for t in /proc/$pid2/task/*; do echo `$(cat `$t/comm)'|'`$(sed 's/.*) //' `$t/stat | cut -d' ' -f12,13); done")) { $q = $l -split '\|'; if ($q.Count -ge 2) { $n = $q[0]; foreach ($w in $names) { if ($n.StartsWith($w)) { $f = $q[1] -split ' '; $o[$w] = [int64]$f[0] + [int64]$f[1] } } } }; return $o }
function Rows { [int]((& $adb shell "wc -l < $f/perf/frames.csv") -join '').Trim() }
$marks = @()
if ($Skate) { & $adb shell "sendevent /dev/input/event9 3 1 -30000; sendevent /dev/input/event9 0 0 0" }
for ($c = 0; $c -lt $Cycles; $c++) {
  foreach ($v in $Flags) {
    cmd /c "`"$adb`" shell `"echo $v > $f/$FlagFile`""
    Start-Sleep 3
    $ta = Ticks @('GPU Commands', 'Main XThread')
    $a = Rows
    if ($Skate) {
      $t0 = Get-Date
      $i = 0
      while (((Get-Date) - $t0).TotalSeconds -lt $Window) {
        $sway = @(0, -12000, 0, 12000)[$i % 4]; $i++
        & $adb shell "sendevent /dev/input/event9 3 0 $sway; sendevent /dev/input/event9 0 0 0; sendevent /dev/input/event9 1 304 1; sendevent /dev/input/event9 0 0 0; sleep 0.25; sendevent /dev/input/event9 1 304 0; sendevent /dev/input/event9 0 0 0; sleep 1.2"
      }
    } else {
      $nS = $Window * 4
      $raw = & $adb shell "i=0; while [ `$i -lt $nS ]; do cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage; sleep 0.25; i=`$((i+1)); done"
      $busy = ($raw | ForEach-Object { if ($_ -match '(\d+)') { [double]$Matches[1] } } | Measure-Object -Average).Average
    }
    $b = Rows
    $tb = Ticks @('GPU Commands', 'Main XThread')
    $cpms = 10.0 * ($tb['GPU Commands'] - $ta['GPU Commands']) / [math]::Max(1, $b - $a)
    $mtms = 10.0 * ($tb['Main XThread'] - $ta['Main XThread']) / [math]::Max(1, $b - $a)
    $marks += ,@($v, $a, $b, $busy, $cpms, $mtms)
  }
}
if ($Skate) { & $adb shell "sendevent /dev/input/event9 3 1 0; sendevent /dev/input/event9 3 0 0; sendevent /dev/input/event9 0 0 0" }
cmd /c "`"$adb`" shell `"echo 0 > $f/$FlagFile`""
cmd /c "`"$adb`" shell unlink $f/$FlagFile"
cmd /c "`"$adb`" pull $f/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
cmd /c "`"$adb`" pull $f/logs/thps_p8.log `"$d\$Tag.log`" > nul 2>&1"
$all = Import-Csv "$d\$Tag.csv"
$agg = @{}; $aggG = @{}; $aggD = @{}
"== $Tag : $($Args2 -join ' ') | flags file $FlagFile"
foreach ($m in $marks) {
  $sel = $all[($m[1] - 1)..($m[2] - 2)]
  $ft = $sel | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
  $dr = ($sel | ForEach-Object { [double]$_.draw_calls } | Measure-Object -Average).Average
  $gb = ($sel | ForEach-Object { [double]$_.gpu_busy_us / 1000 } | Measure-Object -Average).Average
  $gs = ($sel | ForEach-Object { [double]$_.gpu_submissions } | Measure-Object -Average).Average
  $fw = ($sel | ForEach-Object { [double]$_.fence_wait_us / 1000 } | Measure-Object -Average).Average
  $s = $ft | Sort-Object; $n = $s.Count
  if ($n -lt 5) { "flag $($m[0]): too few frames"; continue }
  $tot = ($s | Measure-Object -Sum).Sum
  $k = "$($m[0])"
  if (-not $agg.ContainsKey($k)) { $agg[$k] = New-Object System.Collections.ArrayList }
  [void]$agg[$k].AddRange(@($s)); $aggG[$k] += $gb * $n; $aggD[$k] += $dr * $n
  "  flag {0,5}: {1,4} fr {2,5:N1} fps p50 {3,5:N1} p90 {4,5:N1} >34ms {5,4:P0} >45ms {6,4:P0} draws {7,5:N0} | gpu busy {8,5:N1} ms in {9:N1} subs | fence {10:N1} | CP cpu {11:N1} main {12:N1} ms/fr" -f $m[0], $n, ($n/($tot/1000)), $s[[int](($n-1)*0.5)], $s[[int](($n-1)*0.9)], (($s | ? { $_ -gt 34 }).Count/$n), (($s | ? { $_ -gt 45 }).Count/$n), $dr, $gb, $gs, $fw, $m[4], $m[5]
}
foreach ($k in $agg.Keys) {
  $s = $agg[$k] | Sort-Object; $n = $s.Count; $tot = ($s | Measure-Object -Sum).Sum
  "SUM flag {0,5}: {1,5} fr {2,5:N2} fps p50 {3,5:N1} p90 {4,5:N1} >34ms {5,5:P1} >45ms {6,5:P1} mean {7:N2} ms | gpu {8:N1} ms draws {9:N0} gpu/draw {10:N1} us" -f $k, $n, ($n/($tot/1000)), $s[[int](($n-1)*0.5)], $s[[int](($n-1)*0.9)], (($s | ? { $_ -gt 34 }).Count/$n), (($s | ? { $_ -gt 45 }).Count/$n), ($tot/$n), ($aggG[$k]/$n), ($aggD[$k]/$n), (1000*$aggG[$k]/[math]::Max(1,$aggD[$k]))
}
