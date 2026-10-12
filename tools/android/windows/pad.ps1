param([string[]]$Keys, [string]$Shot = '', [double]$Wait = 1.2)
# Press gamepad buttons on the Thor's built-in pad via sendevent. Keys: A B X Y START SELECT UP DOWN LEFT RIGHT
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$btn = @{ A = 304; B = 305; X = 307; Y = 308; START = 315; SELECT = 314 }
$hat = @{ UP = @(17, -1); DOWN = @(17, 1); LEFT = @(16, -1); RIGHT = @(16, 1) }
$d = '/dev/input/event9'
$sh = ''
foreach ($k in $Keys) {
  $k = $k.ToUpper()
  if ($btn.ContainsKey($k)) {
    $c = $btn[$k]
    $sh += "sendevent $d 1 $c 1; sendevent $d 0 0 0; sleep 0.12; sendevent $d 1 $c 0; sendevent $d 0 0 0; sleep $Wait; "
  } elseif ($hat.ContainsKey($k)) {
    $c = $hat[$k][0]; $v = $hat[$k][1]
    $sh += "sendevent $d 3 $c $v; sendevent $d 0 0 0; sleep 0.12; sendevent $d 3 $c 0; sendevent $d 0 0 0; sleep $Wait; "
  }
}
cmd /c "`"$adb`" shell `"$sh echo ok`""
if ($Shot) { Start-Sleep 1; cmd /c "`"$adb`" exec-out screencap -p > `"$Shot`"" }
