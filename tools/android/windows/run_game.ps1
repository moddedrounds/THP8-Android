param([int]$Seconds = 30, [string]$Tag = 'run', [switch]$NoLaunch)
# Launch the game on the Thor, wait, then collect: logcat, the game's own log, a screenshot, process state.
# (cmd /c for anything binary or redirected: PowerShell 5.1 mangles both.)
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$pkg = 'org.project8recomp.android'
$dir = 'C:\thp8-work\android\runs'
New-Item -ItemType Directory -Force $dir | Out-Null
$base = Join-Path $dir ("$Tag-" + (Get-Date -Format 'HHmmss'))

if (-not $NoLaunch) {
  cmd /c "`"$adb`" shell am force-stop $pkg" | Out-Null
  cmd /c "`"$adb`" logcat -c" | Out-Null
  cmd /c "`"$adb`" shell rm -f /sdcard/Android/data/$pkg/files/logs/thps_p8.log /sdcard/Android/data/$pkg/files/perf/frames.csv" | Out-Null
  cmd /c "`"$adb`" shell am start -n $pkg/.LauncherActivity --ez play true" | Out-Null
  Start-Sleep -Seconds $Seconds
}
"--- processes"
cmd /c "`"$adb`" shell `"ps -A | grep -i project8recomp`""
cmd /c "`"$adb`" exec-out screencap -p > `"$base.png`""
cmd /c "`"$adb`" logcat -d -v threadtime > `"$base.logcat.txt`""
cmd /c "`"$adb`" pull /sdcard/Android/data/$pkg/files/logs/thps_p8.log `"$base.game.log`" > nul 2>&1"
cmd /c "`"$adb`" pull /sdcard/Android/data/$pkg/files/perf/frames.csv `"$base.frames.csv`" > nul 2>&1"
"--- saved: $base.*"
"--- interesting logcat lines"
Select-String -Path "$base.logcat.txt" -Pattern 'thps_p8|FATAL|SIGSEGV|SIGABRT|SIGBUS|SIGILL|Abort message|CANNOT LINK|dlopen failed|UnsatisfiedLink|AndroidRuntime|rex' |
  Select-Object -First 50 | ForEach-Object { $_.Line }
