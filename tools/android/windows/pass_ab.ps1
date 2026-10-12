param([string[]]$Args2, [string]$Tag, [int[]]$Flags, [int]$Settle = 14, [switch]$NoRelaunch)
# Reach the garage, then for each live flag value: write it, wait $Settle s, record device time.
# Prints the last "gpu pass timing" report logged before each recorded time.
$sp = 'C:\thp8-work\android\scripts'
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
$f = '/sdcard/Android/data/org.project8recomp.android/files'
if (-not $NoRelaunch) { & "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1; Start-Sleep 25 }
$marks = @()
foreach ($v in $Flags) {
  cmd /c "`"$adb`" shell `"echo $v > $f/live_flags.txt`""
  Start-Sleep $Settle
  $marks += ,@($v, ((& $adb shell date +%H:%M:%S.%N) -join '').Trim().Substring(0,12))
}
cmd /c "`"$adb`" shell `"echo 0 > $f/live_flags.txt`""
cmd /c "`"$adb`" shell unlink $f/live_flags.txt"
cmd /c "`"$adb`" pull $f/logs/thps_p8.log `"$d\$Tag.log`" > nul 2>&1"
$lines = Get-Content "$d\$Tag.log"
$armed = ($lines | Select-String 'perf: csv armed' | Select -Last 1).LineNumber
foreach ($m in $marks) {
  $best = $null
  for ($i = $armed; $i -lt $lines.Count; $i++) {
    $l = $lines[$i]
    if ($l -match '^\[\d+-\d+-\d+ (\d\d:\d\d:\d\d\.\d\d\d)\].*gpu pass timing') { if ($Matches[1] -lt $m[1]) { $best = $i } }
  }
  "=== flag $($m[0]) (until $($m[1]))"
  if ($best -ne $null) { $j = $best; do { $lines[$j]; $j++ } while ($j -lt $lines.Count -and $lines[$j] -notmatch '^\[') }
}
