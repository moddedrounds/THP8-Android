param([string]$Csv, [string]$Log)
if ($Csv -and (Test-Path $Csv)) {
  $rows = Import-Csv $Csv
  $ft = $rows | ForEach-Object { [double]$_.frame_time_us / 1000 } | Where-Object { $_ -gt 0 }
  $s = $ft | Sort-Object; $c = $s.Count
  if ($c -gt 10) {
    function P($q){ [math]::Round($s[[int][math]::Floor(($c-1)*$q)],2) }
    "frames=$c duration_s=$([math]::Round(($s|Measure-Object -Sum).Sum/1000,1)) mean=$([math]::Round(($s|Measure-Object -Average).Average,2))ms p50=$(P 0.5) p95=$(P 0.95) p99=$(P 0.99) max=$(P 1)"
    "effective fps: $([math]::Round($c/(($s|Measure-Object -Sum).Sum/1000),1))   >20ms: $(($s|Where-Object{$_ -gt 20}).Count)  >34ms: $(($s|Where-Object{$_ -gt 34}).Count)  >100ms: $(($s|Where-Object{$_ -gt 100}).Count)"
    $d = $rows | ForEach-Object { [double]$_.draw_calls }
    "draws/frame mean=$([math]::Round(($d|Measure-Object -Average).Average)) max=$(($d|Measure-Object -Maximum).Maximum)"
    $w = [int]($c/10)
    "fps by tenth of the run: " + ((0..9 | ForEach-Object { $seg = $s; $a = $ft[($_*$w)..([math]::Min(($_+1)*$w-1,$c-1))]; [math]::Round(1000/(($a|Measure-Object -Average).Average),0) }) -join ' ')
  } else { "csv has only $c frames" }
} else { "no csv" }
if ($Log -and (Test-Path $Log)) {
  "--- game log: errors/warnings (excluding pipeline noise)"
  Get-Content $Log | Where-Object { $_ -match '\[(error|critical|warning)\]' -and $_ -notmatch 'Skipped .* Vulkan frame|Available Vulkan physical' } | Select-Object -First 25
  "--- audio / input lines"
  Get-Content $Log | Where-Object { $_ -match 'audio|sdl|gamepad|controller|joystick|input' } | Select-Object -First 12
}
