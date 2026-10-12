param([string[]]$Args2 = @('--gpu_timestamp_queries=true'), [string]$Tag = 'play', [switch]$NoRelaunch, [int]$MaxIter = 90,
      [string]$StopAt = '')
# Cold-start the game with the given args.txt settings and drive the menus to a real gameplay scene
# (Suburbia, New Career -> Start Game) with injected gamepad input, recognising each screen by
# average colours of a few regions. Leaves the game running in-level.
# -StopAt <state> (e.g. customize = the character creator, charselect) stops on that screen instead.
Add-Type -AssemblyName System.Drawing
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$pkg = 'org.project8recomp.android'
$runs = 'C:\thp8-work\android\runs'
$shot = Join-Path $env:TEMP 'tg_shot.png'

function Sig($path) {
  $bmp = New-Object System.Drawing.Bitmap $path
  $regs = @(@(0.30,0.25,0.70,0.55), @(0.07,0.18,0.40,0.50), @(0.10,0.80,0.90,0.92))
  $o = @()
  foreach ($r in $regs) {
    $x0=[int]($r[0]*$bmp.Width); $y0=[int]($r[1]*$bmp.Height); $x1=[int]($r[2]*$bmp.Width); $y1=[int]($r[3]*$bmp.Height)
    $sr=0;$sg=0;$sb=0;$n=0
    for ($y=$y0; $y -lt $y1; $y+=12) { for ($x=$x0; $x -lt $x1; $x+=12) { $c=$bmp.GetPixel($x,$y); $sr+=$c.R;$sg+=$c.G;$sb+=$c.B;$n++ } }
    $o += ,@(($sr/$n),($sg/$n),($sb/$n))
  }
  $bmp.Dispose()
  return ,$o
}
function Near($c, $r, $g, $b, $tol = 28) { return ([math]::Abs($c[0]-$r) + [math]::Abs($c[1]-$g) + [math]::Abs($c[2]-$b)) -lt $tol }

if (-not $NoRelaunch) {
  # On-screen FPS / frame time overlay (top right) in every test, unless the caller sets it.
  if (-not ($Args2 -match 'show_debug_overlay')) { $Args2 = @($Args2) + '--show_debug_overlay=true' }
  $tmp = Join-Path $env:TEMP 'args.txt'
  [IO.File]::WriteAllText($tmp, (($Args2 -join "`n") + "`n"))
  cmd /c "`"$adb`" push `"$tmp`" /sdcard/Android/data/$pkg/files/args.txt > nul 2>&1"
  cmd /c "`"$adb`" shell am force-stop $pkg"
  cmd /c "`"$adb`" shell rm -f /sdcard/Android/data/$pkg/files/perf/frames.csv /sdcard/Android/data/$pkg/files/logs/thps_p8.log"
  cmd /c "`"$adb`" shell am start -n $pkg/.LauncherActivity --ez play true" | Out-Null
  Start-Sleep 20
}
$t0 = Get-Date
$loadingStreak = 0
for ($i = 0; $i -lt $MaxIter; $i++) {
  cmd /c "`"$adb`" exec-out screencap -p > `"$shot`""
  try { $s = Sig $shot } catch { Start-Sleep 2; continue }
  $c = $s[0]; $m = $s[1]; $b = $s[2]
  $state = 'unknown'; $keys = @('START'); $wait = 2.5
  if (-not (Near $c 110 85 105 30)) { $loadingStreak = 0 }
  if ($c[0] -gt 160 -and $b[2] -gt 108 -and $b[0] -gt 80) { $state = 'GAMEPLAY' }
  elseif ($c[0] -lt 52 -and $m[2] -gt 95 -and $m[0] -lt 60) { $state = 'dialog'; $keys = @('DOWN','A'); $wait = 3 }
  elseif ($c[0] -lt 52 -and $c[1] -lt 52 -and $m[0] -gt 80 -and $m[0] -lt 110) { $state = 'warning'; $keys = @('DOWN','A'); $wait = 3 }
  elseif (Near $c 64 86 154 30 -and $m[0] -gt 110) { $state = 'career'; $keys = @('A'); $wait = 3 }
  elseif (Near $c 94 93 94 22) { $state = 'charselect'; $keys = @('A'); $wait = 5 }
  elseif (Near $c 44 27 31 22) { $state = 'name'; $keys = @('DOWN','DOWN','DOWN','DOWN','DOWN','A'); $wait = 5 }
  # The creator: bottom button bar ~62/51/34 and the menu panel; the skater no longer renders white
  # (the old test keyed on c ~177/161/144, the blown-out skater fixed in guest_vertex_unpack.h).
  elseif ((Near $b 62 51 34 15) -and $m[0] -gt 125 -and $m[0] -lt 150) { $state = 'customize'; $keys = @('B'); $wait = 5 }
  elseif ((Near $c 134 120 83 28) -or ((Near $b 41 26 13 15) -and $m[0] -gt 125 -and $m[0] -lt 160)) { $state = 'newcareer'; $keys = @('DOWN','DOWN','A'); $wait = 3 }
  elseif (Near $c 110 85 105 30) {
    # The main menu with CAREER highlighted has the same colours as the loading screen; if it does
    # not change for ~20 s it is the menu, so accept CAREER (input on a loading screen is ignored).
    $state = 'loading'; $keys = @(); $wait = 5; $loadingStreak++
    if ($loadingStreak -ge 4) { $keys = @('A'); $loadingStreak = 0 }
  }
  "{0,3} t={1,4:N0}s {2,-10} c={3:N0}/{4:N0}/{5:N0} m={6:N0} b={7:N0}/{8:N0}/{9:N0}" -f $i, ((Get-Date)-$t0).TotalSeconds, $state, $c[0],$c[1],$c[2], $m[0], $b[0],$b[1],$b[2]
  if ($state -eq 'GAMEPLAY' -or ($StopAt -and $state -eq $StopAt)) { break }
  if ($keys.Count) { & "$sp\pad.ps1" -Keys $keys -Wait 0.5 | Out-Null }
  Start-Sleep $wait
}
if ($StopAt -and $state -eq $StopAt) { Copy-Item $shot "$runs\$Tag.png" -Force; "at $state after $([int]((Get-Date)-$t0).TotalSeconds) s" }
elseif ($state -eq 'GAMEPLAY') { Copy-Item $shot "$runs\$Tag.png" -Force; "in gameplay after $([int]((Get-Date)-$t0).TotalSeconds) s" } else { "DID NOT REACH GAMEPLAY" }
