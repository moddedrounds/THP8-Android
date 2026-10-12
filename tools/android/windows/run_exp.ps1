param([string[]]$Args2, [string]$Tag)
$sp = $PSScriptRoot
$old = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
& "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
Start-Sleep 5
& "$sp\pad.ps1" -Keys START -Wait 2 | Out-Null
Start-Sleep 45
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/logs/thps_p8.log `"$d\$Tag.log`" > nul 2>&1"
cmd /c "`"$adb`" exec-out screencap -p > `"$d\$Tag.end.png`""
"== $Tag : $($Args2 -join ' ')"
& "$old\phase_android.ps1" -Csv "$d\$Tag.csv" -Window 5 | Select -Last 6
