param([string[]]$Args2 = @('--gpu_timestamp_queries=true'), [string]$Tag = 'pab',
      [string[]]$Configs = @('vblank_spin_us=3000', 'vblank_spin_us=0'),
      [int]$Window = 16, [int]$Cycles = 3, [switch]$NoRelaunch, [switch]$Skate)
# Live A/B of engine settings in ONE game session, measuring system power (power.ps1), frame rate
# and process CPU per window. Each config is "name=value;name=value", written to
# files/live_cvars.txt (applied by the game within ~1 s). Idle in the garage by default (the
# heaviest view, deterministic); -Skate drives the scripted route during each window.
# Prints one line per window and a per-config summary. Removes live_cvars.txt at the end.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$f = '/sdcard/Android/data/org.project8recomp.android/files'
$d = 'C:\thp8-work\android\runs'
if (-not $NoRelaunch) {
  & "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
  Start-Sleep 20
}
function ProcTicks { $p = ((& $adb shell pidof org.project8recomp.android:game) -join '').Trim(); $s = ((& $adb shell "cat /proc/$p/stat") -join '') -replace '.*\) ', ''; $q = $s -split ' '; return [int64]$q[11] + [int64]$q[12] }
function Rows { [int]((& $adb shell "wc -l < $f/perf/frames.csv") -join '').Trim() }
$res = @()
for ($c = 0; $c -lt $Cycles; $c++) {
  foreach ($cfg in $Configs) {
    $tmp = Join-Path $env:TEMP 'live_cvars.txt'
    [IO.File]::WriteAllText($tmp, (($cfg -split ';') -join "`n") + "`n")
    cmd /c "`"$adb`" push `"$tmp`" $f/live_cvars.txt > nul 2>&1"
    Start-Sleep 4
    $a = Rows; $t0 = ProcTicks; $w0 = Get-Date
    if ($Skate) { $job = Start-Job -ScriptBlock { param($s, $n) & "$s\skate.ps1" -Seconds $n } -ArgumentList $sp, $Window }
    $pw = & "$sp\power.ps1" -Seconds $Window
    if ($Skate) { Wait-Job $job | Out-Null; Remove-Job $job }
    $t1 = ProcTicks; $secs = ((Get-Date) - $w0).TotalSeconds
    cmd /c "`"$adb`" pull $f/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
    $rows = Import-Csv "$d\$Tag.csv" | Select-Object -Skip ($a - 1)
    $ft = $rows | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
    $fps = $ft.Count / (($ft | Measure-Object -Sum).Sum / 1000)
    $slow = ($ft | Where-Object { $_ -gt 18 }).Count / [math]::Max(1, $ft.Count)
    $slow35 = ($ft | Where-Object { $_ -gt 35 }).Count / [math]::Max(1, $ft.Count)
    $gpu = ($rows | ForEach-Object { [double]$_.gpu_busy_us } | Measure-Object -Average).Average / 1000
    $cpu = ($t1 - $t0) / 100.0 / $secs   # cores busy (USER_HZ = 100)
    $cpms = ($rows | ForEach-Object { [double]$_.cp_cpu_us } | Measure-Object -Average).Average / 1000
    $w = [double](($pw -replace '.*power ([0-9.]+) W.*', '$1'))
    $r = [pscustomobject]@{ cfg = $cfg; fps = $fps; slow = $slow; slow35 = $slow35; cpms = $cpms; gpu = $gpu; cpu = $cpu; watts = $w; line = $pw }
    $res += $r
    '{0,-50} {1,5:N1} fps  >18ms {2,5:P1} >35ms {6,5:P1}  gpu {3,5:N1} ms  cp {7,4:N1} ms  cpu {4,4:N2} cores  {5}' -f $cfg, $fps, $slow, $gpu, $cpu, ($pw -replace '^\s*power', 'power'), $slow35, $cpms
  }
}
cmd /c "`"$adb`" shell rm -f $f/live_cvars.txt"
'--- summary ---'
foreach ($cfg in $Configs) {
  $g = $res | Where-Object { $_.cfg -eq $cfg }
  '{0,-50} {1,5:N1} fps  >18ms {2,5:P1} >35ms {6,5:P1}  gpu {3,5:N1} ms  cp {7,4:N1} ms  cpu {4,4:N2} cores  {5,5:N2} W' -f $cfg,
    ($g.fps | Measure-Object -Average).Average, ($g.slow | Measure-Object -Average).Average,
    ($g.gpu | Measure-Object -Average).Average, ($g.cpu | Measure-Object -Average).Average,
    ($g.watts | Measure-Object -Average).Average, ($g.slow35 | Measure-Object -Average).Average, ($g.cpms | Measure-Object -Average).Average
}
