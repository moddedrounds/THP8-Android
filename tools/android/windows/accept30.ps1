param([string[]]$Args2 = @('--gpu_timestamp_queries=true'), [int]$Seconds = 120, [int]$Runs = 3, [string]$Tag = 'acc')
# "Locked 30 fps" acceptance: runs the scripted skating benchmark $Runs times (each is a cold start,
# so the pipeline caches are warm from the second launch on) and prints PASS/FAIL per run:
#   share of frames > 35 ms <= 1 %, p99 <= 40 ms, mean fps >= 29.5.
# UNTESTED when written: if it misbehaves, fix it - the criteria are the point.
$sp = $PSScriptRoot
$d = 'C:\thp8-work\android\runs'
$allPass = $true
for ($i = 1; $i -le $Runs; $i++) {
  $t = "$Tag$i"
  & "$sp\run_skate.ps1" -Args2 $Args2 -Tag $t -Seconds $Seconds | Out-Null
  $rows = Import-Csv "$d\$t.csv"
  $pre = (Get-Content "$d\$t.pre.csv" | Measure-Object -Line).Lines
  $ft = $rows | Select-Object -Skip ($pre - 1) | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
  $s = $ft | Sort-Object
  $n = $s.Count
  if ($n -lt 100) { "run ${i}: too few frames ($n)"; $allPass = $false; continue }
  $total = ($s | Measure-Object -Sum).Sum
  $fps = $n / ($total / 1000)
  $slow = ($s | Where-Object { $_ -gt 35 }).Count / $n
  $p99 = $s[[int][math]::Floor(($n - 1) * 0.99)]
  $ok = ($slow -le 0.01) -and ($p99 -le 40) -and ($fps -ge 29.5)
  if (-not $ok) { $allPass = $false }
  "run {0}: {1} frames, {2:N1} fps, {3:P1} of frames >35 ms, p99 {4:N1} ms  => {5}" -f $i, $n, $fps, $slow, $p99, $(if ($ok) { 'PASS' } else { 'FAIL' })
}
if ($allPass) { "LOCKED 30: PASS" } else { "LOCKED 30: FAIL" }
