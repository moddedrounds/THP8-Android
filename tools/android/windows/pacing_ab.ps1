param([string[]]$Configs = @('gpu_vblank_display_lock=false', 'gpu_vblank_display_lock=true;gpu_vblank_display_phase_pct=50'),
      [int]$Window = 32, [int]$Cycles = 1, [switch]$Skate, [string]$Tag = 'pab')
# Presentation pacing A/B in one running session: applies each live cvar set, waits, then reads the
# "presenter pacing" log lines written during the window (600 paints each, ~10 s): new frames,
# repeats, never-shown guest frames, and the display-phase histograms.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$f = '/sdcard/Android/data/org.project8recomp.android/files'
$sum = @{}
for ($c = 0; $c -lt $Cycles; $c++) {
  foreach ($cfg in $Configs) {
    $tmp = Join-Path $env:TEMP 'pab.txt'
    [IO.File]::WriteAllText($tmp, (($cfg -split ';') -join "`n") + "`n")
    cmd /c "`"$adb`" push `"$tmp`" $f/live_cvars.txt > nul 2>&1"
    Start-Sleep 3
    $before = [int]((& $adb shell "grep -a -c 'presenter pacing' $f/logs/thps_p8.log") -join '').Trim()
    if ($Skate) { & "$sp\skate.ps1" -Seconds $Window | Out-Null } else { Start-Sleep $Window }
    $lines = & $adb shell "grep -a 'presenter pacing' $f/logs/thps_p8.log | tail -n +$($before + 2)"
    $rep = 0; $drop = 0; $n = 0
    foreach ($l in $lines) {
      if ($l -match '(\d+) repeats; (\d+) guest frames, (\d+) never shown') { $rep += [int]$Matches[1]; $drop += [int]$Matches[3]; $n++ }
    }
    if (-not $sum.ContainsKey($cfg)) { $sum[$cfg] = @(0, 0, 0) }
    $sum[$cfg] = @(($sum[$cfg][0] + $rep), ($sum[$cfg][1] + $drop), ($sum[$cfg][2] + $n))
    '{0,-70} {1} windows: {2} repeats, {3} never shown per 600 paints | last: {4}' -f $cfg, $n,
      $(if ($n) { [math]::Round($rep / $n, 1) } else { '-' }), $(if ($n) { [math]::Round($drop / $n, 1) } else { '-' }),
      (($lines | Select-Object -Last 1) -replace '.*refresh phase', 'refresh phase')
  }
}
cmd /c "`"$adb`" shell rm -f $f/live_cvars.txt"
'--- summary (per 600 paints = 10 s) ---'
foreach ($cfg in $Configs) { $s = $sum[$cfg]; '{0,-70} repeats {1,5:N1}  never shown {2,5:N1}' -f $cfg, ($s[0] / [math]::Max(1, $s[2])), ($s[1] / [math]::Max(1, $s[2])) }
