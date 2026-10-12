param([string[]]$Zones = @('houses','factory','ghetto','shops','center','citypark','cretepark','funpark','outschool','inschool','parking','training'),
      [string]$Tag = 'zb', [int]$LoadSeconds = 45, [int]$Seconds = 20, [switch]$NoSkate, [string]$Live = '')
# Multi-area benchmark. Needs a running game in gameplay launched with --dev_console=true (e.g.
# to_gameplay.ps1 -Args2 @(..., '--dev_console=true')). For each zone: load it through the game's
# Free Skate level loader (live_cvars.txt "!cheat_zone <zone>"), wait, skate (or stand) for $Seconds,
# then print fps, slow frames, GPU / CP ms, draws per frame from frames.csv and save a screenshot.
# $Live: extra live cvar lines applied with the command (e.g. 'gpu_vblank_hold_us=0').
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$f = '/sdcard/Android/data/org.project8recomp.android/files'
$d = 'C:\thp8-work\android\runs'
function Rows { [int]((& $adb shell "wc -l < $f/perf/frames.csv") -join '').Trim() }
$out = @()
foreach ($z in $Zones) {
  $tmp = Join-Path $env:TEMP 'zb.txt'
  $lines = @("!cheat_zone $z") + ($Live -split ';' | Where-Object { $_ })
  [IO.File]::WriteAllText($tmp, ($lines -join "`n") + "`n")
  cmd /c "`"$adb`" push `"$tmp`" $f/live_cvars.txt > nul 2>&1"
  Start-Sleep $LoadSeconds
  $a = Rows
  if (-not $NoSkate) { & "$sp\skate.ps1" -Seconds $Seconds | Out-Null } else { Start-Sleep $Seconds }
  cmd /c "`"$adb`" exec-out screencap -p > `"$d\${Tag}_$z.png`""
  cmd /c "`"$adb`" pull $f/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
  $rows = Import-Csv "$d\$Tag.csv" | Select-Object -Skip ($a - 1)
  $rows | Export-Csv -NoTypeInformation "$d\${Tag}_$z.csv"
  $ft = $rows | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
  if ($ft.Count -lt 10) { "{0,-10} no frames" -f $z; continue }
  $fps = $ft.Count / (($ft | Measure-Object -Sum).Sum / 1000)
  $slow = ($ft | Where-Object { $_ -gt 18 }).Count / $ft.Count
  $slow35 = ($ft | Where-Object { $_ -gt 35 }).Count / $ft.Count
  $avg = { param($c) ($rows | ForEach-Object { [double]$_.$c } | Measure-Object -Average).Average }
  $r = '{0,-10} {1,5:N1} fps  >18ms {2,5:P1}  >35ms {3,5:P1}  gpu {4,5:N1} ms  cp {5,5:N1} ms  draws {6,5:N0}  cp_runq {7,4:N1} ms' -f $z, $fps, $slow, $slow35,
    ((& $avg 'gpu_busy_us') / 1000), ((& $avg 'cp_cpu_us') / 1000), (& $avg 'draw_calls'), ((& $avg 'cp_runq_us') / 1000)
  $r
  $out += $r
}
cmd /c "`"$adb`" shell rm -f $f/live_cvars.txt"
$out | Set-Content "$d\$Tag.zones.txt"
