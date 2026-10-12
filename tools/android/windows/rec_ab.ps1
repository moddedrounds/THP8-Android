param([string[]]$Configs = @('gpu_shadow_skip_level=1', 'gpu_shadow_skip_level=0'), [string]$Tag = 'recab', [int]$Seconds = 4)
# While skating, record $Seconds of the screen per live cvar set and extract the frames at the
# game's size: runs\<tag>\f<i>\NNNN.png (for frame-by-frame comparisons).
$s = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$f = '/sdcard/Android/data/org.project8recomp.android/files'
$ff = 'C:\Program Files\Lian-Li\L-Connect 3\x64\ffmpeg.exe'
$d = "C:\thp8-work\android\runs\$Tag"
New-Item -ItemType Directory -Force $d | Out-Null
for ($i = 0; $i -lt $Configs.Count; $i++) {
  [IO.File]::WriteAllText("$env:TEMP\recab.txt", (($Configs[$i] -split ';') -join "`n") + "`n")
  cmd /c "`"$adb`" push `"$env:TEMP\recab.txt`" $f/live_cvars.txt > nul 2>&1"
  $job = Start-Job -ScriptBlock { param($p, $n) & "$p\skate.ps1" -Seconds $n } -ArgumentList $s, ($Seconds + 8)
  Start-Sleep 5
  & $adb shell "screenrecord --bit-rate 60000000 --time-limit $Seconds /sdcard/recab$i.mp4"
  Wait-Job $job | Out-Null; Remove-Job $job
  cmd /c "`"$adb`" pull /sdcard/recab$i.mp4 `"$d\rec$i.mp4`" > nul 2>&1"
  & $adb shell "rm -f /sdcard/recab$i.mp4"
  New-Item -ItemType Directory -Force "$d\f$i" | Out-Null
  & $ff -hide_banner -loglevel error -i "$d\rec$i.mp4" -vf "scale=1040:585" -vsync 0 "$d\f$i\%04d.png"
  "{0}: {1} frames" -f $Configs[$i], (Get-ChildItem "$d\f$i").Count
}
& $adb shell "rm -f $f/live_cvars.txt"
