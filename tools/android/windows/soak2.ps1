param([string]$Apk, [string[]]$Args2, [string]$Tag = 'soak', [int]$Minutes = 10, [int]$CooldownSeconds = 240)
# Thermal soak for an A/B between builds: install $Apk, let the device cool with the game closed,
# reach the garage, then skate (scripted rounds) for $Minutes while sampling system power
# (power.ps1, 20 s per round) and the hottest CPU / GPU zone. Summary: fps, slow frames, GPU busy,
# CPU cores, mean power, temperatures at the end. Output: runs\<tag>.soak.csv + frames <tag>.csv.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
$f = '/sdcard/Android/data/org.project8recomp.android/files'
if ($Apk) { & $adb install -r $Apk | Out-Null }
& $adb shell am force-stop org.project8recomp.android
Start-Sleep $CooldownSeconds
$start = (& "$sp\power.ps1" -Seconds 5) -replace '.*\| ', ''
& "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
Start-Sleep 10
$pre = [int]((& $adb shell "wc -l < $f/perf/frames.csv") -join '').Trim()
function ProcTicks { $p = ((& $adb shell pidof org.project8recomp.android:game) -join '').Trim(); $s = ((& $adb shell "cat /proc/$p/stat") -join '') -replace '.*\) ', ''; $q = $s -split ' '; return [int64]$q[11] + [int64]$q[12] }
$t0 = Get-Date; $k0 = ProcTicks
$rows = @('seconds,watts,cpu_max_c,gpu_max_c')
while (((Get-Date) - $t0).TotalMinutes -lt $Minutes) {
  $job = Start-Job -ScriptBlock { param($s) & "$s\skate.ps1" -Seconds 22 } -ArgumentList $sp
  $pw = & "$sp\power.ps1" -Seconds 20
  Wait-Job $job | Out-Null; Remove-Job $job
  if ($pw -match 'power ([0-9.]+) W.*cpu max ([0-9.]+) C, gpu max ([0-9.]+) C') {
    $rows += '{0:N0},{1},{2},{3}' -f ((Get-Date) - $t0).TotalSeconds, $Matches[1], $Matches[2], $Matches[3] -replace ' ', ''
  }
}
$cores = (ProcTicks - $k0) / 100.0 / ((Get-Date) - $t0).TotalSeconds
$rows | Set-Content "$d\$Tag.soak.csv"
cmd /c "`"$adb`" pull $f/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
$sel = Import-Csv "$d\$Tag.csv" | Select-Object -Skip ($pre - 1)
$ft = $sel | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
$tot = ($ft | Measure-Object -Sum).Sum
$s = Import-Csv "$d\$Tag.soak.csv"
$last = $s | Select-Object -Last 3
"== $Tag ($Minutes min skating; start $start): {0:N1} fps, >18 ms {1:P1}, >35 ms {2:P1}, GPU {3:N1} ms/frame, CPU {4:N2} cores | power {5:N2} W mean | last minute: cpu {6:N1} C, gpu {7:N1} C (max cpu {8:N1}, gpu {9:N1})" -f `
  ($ft.Count / ($tot / 1000)), (($ft | Where-Object { $_ -gt 18 }).Count / $ft.Count), (($ft | Where-Object { $_ -gt 35 }).Count / $ft.Count), `
  (($sel | ForEach-Object { [double]$_.gpu_busy_us } | Measure-Object -Average).Average / 1000), $cores, `
  (($s | ForEach-Object { [double]$_.watts } | Measure-Object -Average).Average), `
  (($last | ForEach-Object { [double]$_.cpu_max_c } | Measure-Object -Average).Average), (($last | ForEach-Object { [double]$_.gpu_max_c } | Measure-Object -Average).Average), `
  (($s | ForEach-Object { [double]$_.cpu_max_c } | Measure-Object -Maximum).Maximum), (($s | ForEach-Object { [double]$_.gpu_max_c } | Measure-Object -Maximum).Maximum)
