param([int]$Seconds = 60)
# Samples SurfaceFlinger's present timestamps of the game layer every 1.5 s (it keeps the last 128)
# and prints the histogram of on-screen frame intervals over the whole period.
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$layer = ((& $adb shell dumpsys SurfaceFlinger --list) | ? { $_ -match 'GameActivity\]\(BLAST\)' } | Select-Object -First 1).Trim()
$seen = @{}
$t0 = Get-Date
while (((Get-Date) - $t0).TotalSeconds -lt $Seconds) {
  $out = & $adb shell "dumpsys SurfaceFlinger --latency '$layer'"
  foreach ($l in ($out | Select-Object -Skip 1)) {
    $p = $l -split '\s+'
    if ($p.Count -ge 3) { $v = [int64]$p[1]; if ($v -gt 0 -and $v -lt 9000000000000000000) { $seen[$v] = 1 } }
  }
  Start-Sleep -Milliseconds 1500
}
$ts = $seen.Keys | Sort-Object
$hist = @{}
for ($i = 1; $i -lt $ts.Count; $i++) {
  $d = [math]::Round(($ts[$i] - $ts[$i - 1]) / 1e6 / 8.333) * 8.333
  $hist[[math]::Round($d, 1)]++
}
$n = $ts.Count - 1
"display: {0} frames in {1:N1} s => {2:N1} fps on screen" -f $n, (($ts[-1] - $ts[0]) / 1e9), ($n / (($ts[-1] - $ts[0]) / 1e9))
$hist.GetEnumerator() | Sort-Object { [double]$_.Key } | % { "  {0,6:N1} ms: {1,5} ({2:P1})" -f $_.Key, $_.Value, ($_.Value / $n) }
