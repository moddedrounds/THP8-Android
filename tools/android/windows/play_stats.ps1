param([string]$Tag, [int]$Settle = 45, [int]$Window = 10)
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$old = $PSScriptRoot
$d = 'C:\thp8-work\android\runs'
Start-Sleep $Settle
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/logs/thps_p8.log `"$d\$Tag.log`" > nul 2>&1"
cmd /c "`"$adb`" exec-out screencap -p > `"$d\$Tag.end.png`""
& "$old\phase_android.ps1" -Csv "$d\$Tag.csv" -Window $Window | Select-Object -Last 7
