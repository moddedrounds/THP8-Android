param([int]$Seconds = 45, [string]$ShotPrefix = '')
# Drive the skater with injected pad input for $Seconds: stick forward, a push/ollie tap every ~1.5 s,
# and the stick swung left/right a little so the camera and scene keep changing. Takes a screenshot
# every 10 s if $ShotPrefix is set.
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = '/dev/input/event9'
$t0 = Get-Date
$i = 0
# stick forward
& $adb shell "sendevent $d 3 1 -30000; sendevent $d 0 0 0"
while (((Get-Date) - $t0).TotalSeconds -lt $Seconds) {
  $sway = @(0, -12000, 0, 12000)[$i % 4]
  $cmd = "sendevent $d 3 0 $sway; sendevent $d 0 0 0; sendevent $d 1 304 1; sendevent $d 0 0 0; sleep 0.25; sendevent $d 1 304 0; sendevent $d 0 0 0; sleep 1.2"
  & $adb shell $cmd
  $i++
  if ($ShotPrefix -and ($i % 7 -eq 0)) {
    cmd /c "`"$adb`" exec-out screencap -p > `"$ShotPrefix$([int]((Get-Date)-$t0).TotalSeconds).png`""
  }
}
& $adb shell "sendevent $d 3 1 0; sendevent $d 3 0 0; sendevent $d 0 0 0"
