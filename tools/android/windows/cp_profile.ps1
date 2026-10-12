param([string]$Tag = 'cpprof', [int]$Seconds = 10, [string]$Thread = 'GPU Commands', [switch]$Skate)
# Profiles one game thread (default: the GPU command processor) with simpleperf (fp call graphs) on
# the device, pulls the recording and prints a flat self-time report and a children (inclusive) report,
# symbolised with the unstripped libraries of the current WSL build (helpers/symrep.sh).
# Output: runs\<tag>.data, runs\<tag>.self.txt, runs\<tag>.children.txt
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$runs = 'C:\thp8-work\android\runs'
$pkg = 'org.project8recomp.android'
$procid = ((& $adb shell pidof "${pkg}:game") -join '').Trim()
$names = & $adb shell "for t in /proc/$procid/task/*; do echo `${t##*/} `$(cat `$t/comm); done"
$tid = ($names | Where-Object { $_ -match [regex]::Escape($Thread) } | Select-Object -First 1) -replace ' .*', ''
if (-not $tid) { "thread '$Thread' not found in $procid"; exit 1 }
"profiling tid $tid ($Thread) for $Seconds s"
if ($Skate) { $job = Start-Job -ScriptBlock { param($s, $n) & "$s\skate.ps1" -Seconds $n } -ArgumentList $sp, ($Seconds + 2) }
& $adb shell "simpleperf record --app $pkg -t $tid --duration $Seconds -f 2000 --call-graph fp -o /data/local/tmp/$Tag.data" 2>&1 | Select-Object -Last 1
if ($Skate) { Wait-Job $job | Out-Null; Remove-Job $job }
& $adb shell "run-as $pkg true" 2>$null
cmd /c "`"$adb`" pull /data/local/tmp/$Tag.data `"$runs\$Tag.data`" > nul 2>&1"
if (-not (Test-Path "$runs\$Tag.data")) {
  # simpleperf --app writes into the app's data dir when /data/local/tmp is not writable for it
  & $adb shell "cp /data/local/tmp/$Tag.data /sdcard/$Tag.data"
  cmd /c "`"$adb`" pull /sdcard/$Tag.data `"$runs\$Tag.data`" > nul 2>&1"
}
$appdir = ((& $adb shell pm path $pkg) -join '' -replace 'package:', '' -replace '/base.apk', '').Trim()
wsl -e bash /mnt/c/thp8-work/android/scripts/helpers/symrep.sh "/mnt/c/thp8-work/android/runs/$Tag.data" $appdir --sort symbol --percent-limit 0.4 | Out-File -Encoding utf8 "$runs\$Tag.self.txt"
wsl -e bash /mnt/c/thp8-work/android/scripts/helpers/symrep.sh "/mnt/c/thp8-work/android/runs/$Tag.data" $appdir --children --sort symbol --percent-limit 1.5 | Out-File -Encoding utf8 "$runs\$Tag.children.txt"
Get-Content "$runs\$Tag.self.txt" | Select-Object -First 45
