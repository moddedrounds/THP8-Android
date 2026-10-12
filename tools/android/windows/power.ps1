param([int]$Seconds = 20, [string]$Label = '')
# System power estimate while on USB: USB input power minus the power going into the battery
# (battery current_now is positive while charging, negative while discharging; the USB input sits at
# its 1.4 A limit while the game runs). Charger losses make it a proxy, but it moves
# 1:1 with what the SoC draws, so it is good for A/B. Also reports the hottest CPU / GPU zone.
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$probe = 'i=0; while [ $i -lt ' + ($Seconds * 2) + ' ]; do echo $(cat /sys/class/power_supply/usb/current_now) $(cat /sys/class/power_supply/usb/voltage_now) $(cat /sys/class/power_supply/battery/current_now) $(cat /sys/class/power_supply/battery/voltage_now); sleep 0.5; i=$((i+1)); done; c=0; g=0; for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type); case $t in cpu-*) v=$(cat $z/temp); [ $v -gt $c ] && c=$v;; gpuss*) v=$(cat $z/temp); [ $v -gt $g ] && g=$v;; esac; done; echo T $c $g'
$lines = & $adb shell $probe
$p = @(); $t = $null
foreach ($l in $lines) {
  $v = $l.Trim() -split '\s+'
  if ($v[0] -eq 'T') { $t = $v; continue }
  if ($v.Count -lt 4) { continue }
  $usb = [double]$v[0] * [double]$v[1] / 1e12
  $bat = [double]$v[2] * [double]$v[3] / 1e12   # positive while charging
  $p += ($usb - $bat)
}
$m = ($p | Measure-Object -Average).Average
$sd = [math]::Sqrt((($p | ForEach-Object { ($_ - $m) * ($_ - $m) }) | Measure-Object -Average).Average)
'{0} power {1:N2} W (sd {2:N2}, {3} samples) | cpu max {4:N1} C, gpu max {5:N1} C' -f $Label, $m, $sd, $p.Count, ([double]$t[1] / 1000), ([double]$t[2] / 1000)
