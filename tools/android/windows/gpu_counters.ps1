param([string]$Csv, [int]$From = 0, [int]$To = 99999)
$rows = Import-Csv $Csv
$t = 0.0; $sel = @()
foreach ($r in $rows) { $ft = [double]$r.frame_time_us / 1000; if ($ft -le 0) { continue }; $t += $ft/1000; if ($t -ge $From -and $t -lt $To) { $sel += $r } }
"frames in [$From,$To) s: $($sel.Count)"
foreach ($c in 'draw_calls','render_pass_begins','vk_pipeline_barriers','native_draws','native_fallback_draws','memexport_draws','request_range_vertex_bytes','request_range_texture_bytes','native_bytes_uploaded','native_bytes_hashed','make_range_valid_calls','watch_rearms','shared_page_overlaps','texture_cache_hits','texture_cache_misses','vertices_processed','command_buffer_stalls','interrupt_dispatches','stale_protect_recoveries') {
  $v = $sel | ForEach-Object { [double]$_.$c }
  if ($v.Count) { "{0,-30} mean {1,12:N1}   max {2,12:N0}" -f $c, ($v | Measure-Object -Average).Average, ($v | Measure-Object -Maximum).Maximum }
}
