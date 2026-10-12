param([string[]]$Args2 = @('--gpu_timestamp_queries=true'), [string]$Tag = 'soak', [int]$Minutes = 15)
# Thermal soak: reach the garage, skate for $Minutes (scripted, repeated skate.ps1 rounds), sample
# the hottest CPU / GPU thermal zone and xo-therm every ~30 s, then summarise frames.csv
# (fps, slow frames, mean GPU busy). Output: runs\<tag>.temps.csv, .csv, .log.
$sp = $PSScriptRoot
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$d = 'C:\thp8-work\android\runs'
& "$sp\to_gameplay.ps1" -Args2 $Args2 -Tag $Tag | Select -Last 1
Start-Sleep 25
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/perf/frames.csv `"$d\$Tag.pre.csv`" > nul 2>&1"
$pre = (Get-Content "$d\$Tag.pre.csv" | Measure-Object -Line).Lines
$probe = 'c=0; g=0; for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type); v=$(cat $z/temp); case $t in cpu*) [ $v -gt $c ] && c=$v;; gpuss*) [ $v -gt $g ] && g=$v;; esac; done; x=$(for z in /sys/class/thermal/thermal_zone*; do [ "$(cat $z/type)" = xo-therm ] && cat $z/temp; done); echo $c $g $x'
$rows = @('seconds,cpu_max_c,gpu_max_c,xo_c')
$t0 = Get-Date
while (((Get-Date) - $t0).TotalMinutes -lt $Minutes) {
  & "$sp\skate.ps1" -Seconds 25 | Out-Null
  $v = ((& $adb shell $probe) -join ' ').Trim() -split '\s+'
  if ($v.Count -ge 3) {
    $rows += ('{0:N0},{1:N1},{2:N1},{3:N1}' -f ((Get-Date) - $t0).TotalSeconds, ([double]$v[0] / 1000), ([double]$v[1] / 1000), ([double]$v[2] / 1000)) -replace ' ', ''
  }
}
$rows | Set-Content "$d\$Tag.temps.csv"
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/perf/frames.csv `"$d\$Tag.csv`" > nul 2>&1"
cmd /c "`"$adb`" pull /sdcard/Android/data/org.project8recomp.android/files/logs/thps_p8.log `"$d\$Tag.log`" > nul 2>&1"
$sel = Import-Csv "$d\$Tag.csv" | Select-Object -Skip ($pre - 1)
$ft = $sel | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
$tot = ($ft | Measure-Object -Sum).Sum
$temps = Import-Csv "$d\$Tag.temps.csv"
"== $Tag : $Minutes min, $($ft.Count) frames, {0:N2} fps, >35 ms {1:P2}, gpu busy {2:N2} ms/frame | cpu max {3:N1} C, gpu max {4:N1} C, xo max {5:N1} C, last cpu {6:N1} gpu {7:N1}" -f `
  ($ft.Count / ($tot / 1000)), (($ft | Where-Object { $_ -gt 35 }).Count / $ft.Count), `
  (($sel | ForEach-Object { [double]$_.gpu_busy_us } | Measure-Object -Average).Average / 1000), `
  (($temps | ForEach-Object { [double]$_.cpu_max_c } | Measure-Object -Maximum).Maximum), `
  (($temps | ForEach-Object { [double]$_.gpu_max_c } | Measure-Object -Maximum).Maximum), `
  (($temps | ForEach-Object { [double]$_.xo_c } | Measure-Object -Maximum).Maximum), `
  ([double]$temps[-1].cpu_max_c), ([double]$temps[-1].gpu_max_c)
