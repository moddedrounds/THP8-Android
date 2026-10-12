param([string]$Csv, [int]$Window = 10)
$rows = Import-Csv $Csv
$t = 0.0; $win = @{}
foreach ($r in $rows) {
  $ft = [double]$r.frame_time_us / 1000
  if ($ft -le 0) { continue }
  $t += $ft / 1000
  $k = [int][math]::Floor($t / $Window)
  if (-not $win.ContainsKey($k)) { $win[$k] = @{n=0; ft=0.0; d=0.0; gpu=0.0; pm=0.0; tm=0.0; cp=0.0; stall=0.0; s33=0; big=0; maxft=0.0} }
  $w = $win[$k]; $w.n++; $w.ft += $ft; $w.d += [double]$r.draw_calls
  $w.gpu += [double]$r.gpu_frame_us / 1000; $w.pm += [double]$r.pipeline_cache_misses; $w.tm += [double]$r.texture_cache_misses
  $w.cp += [double]$r.cp_drain_us / 1000; $w.stall += [double]$r.command_buffer_stalls
  if ($ft -gt 25 -and $ft -lt 45) { $w.s33++ }
  if ($ft -ge 45) { $w.big++ }
  if ($ft -gt $w.maxft) { $w.maxft = $ft }
}
"win(s)    frames  fps  meanFt  draws  gpuMs  cpDrainMs  pipeMiss/f  texMiss/f  n~33  n>45  maxFt"
foreach ($k in ($win.Keys | Sort-Object)) { $w = $win[$k]
  "{0,4}-{1,-4} {2,6} {3,5} {4,7} {5,6} {6,6} {7,9} {8,10} {9,10} {10,5} {11,5} {12,6}" -f ($k*$Window), ($k*$Window+$Window), $w.n,
    [math]::Round($w.n/($w.ft/1000),1), [math]::Round($w.ft/$w.n,1), [math]::Round($w.d/$w.n), [math]::Round($w.gpu/$w.n,1),
    [math]::Round($w.cp/$w.n,1), [math]::Round($w.pm/$w.n,2), [math]::Round($w.tm/$w.n,2), $w.s33, $w.big, [math]::Round($w.maxft) }
