param([int]$Interval = 5, [string]$Proc = 'org.project8recomp.android:game')
# Which core each thread of the game runs on, and how busy it is (two /proc samples).
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$procId = (cmd /c "`"$adb`" shell pidof $Proc").Trim()
$cmdline = "for t in /proc/$procId/task/*; do echo `$(basename `$t)'|'`$(cat `$t/comm)'|'`$(sed 's/.*) //' `$t/stat | cut -d' ' -f12,13,37); done"
function Sample { (cmd /c "`"$adb`" shell `"$cmdline`"") | ForEach-Object { $p = $_ -split '\|'; if ($p.Count -ge 3) { $f = $p[2] -split ' '; [pscustomobject]@{Tid=$p[0]; Name=$p[1]; Ticks=[int64]$f[0]+[int64]$f[1]; Core=$f[2]} } } }
$a = Sample; Start-Sleep -Seconds $Interval; $b = Sample
$prev = @{}; foreach ($x in $a) { $prev[$x.Tid] = $x }
$rows = foreach ($x in $b) { if ($prev.ContainsKey($x.Tid)) { [pscustomobject]@{ Thread=$x.Name; Tid=$x.Tid; CpuPct=[math]::Round(($x.Ticks - $prev[$x.Tid].Ticks) / $Interval, 0); Core=$x.Core } } }
"pid $procId; cores 0-2 = A510 (little, 2.0 GHz), 3-6 = A710/A715 (mid, 2.8 GHz), 7 = X3 (prime, 3.2 GHz)"
$rows | Where-Object { $_.CpuPct -ge 3 } | Sort-Object CpuPct -Descending | Format-Table -AutoSize | Out-String
