param([string]$Tag = 'acc', [int]$Runs = 3)
# Classifies the slow (> 35 ms) frames of accept30 runs <Tag>1..<Tag>N by the per-frame columns.
$d = 'C:\thp8-work\android\runs'
for ($i = 1; $i -le $Runs; $i++) {
  $t = "$Tag$i"
  if (-not (Test-Path "$d\$t.csv")) { continue }
  $rows = Import-Csv "$d\$t.csv"
  $pre = (Get-Content "$d\$t.pre.csv" | Measure-Object -Line).Lines
  $sel = @($rows | Select-Object -Skip ($pre - 1))
  $slow = @($sel | ? { [double]$_.frame_time_us -gt 35000 })
  $c = @{ 'gpu>30ms' = 0; 'cp preempted' = 0; 'vsync preempted' = 0; 'cap late' = 0; 'cp work' = 0; 'other' = 0 }
  foreach ($r in $slow) {
    if ([double]$r.gpu_busy_us -gt 30000) { $c['gpu>30ms']++ }
    elseif ($r.cp_runq_us -and [double]$r.cp_runq_us -gt 5000) { $c['cp preempted']++ }
    elseif ($r.vsync_runq_us -and [double]$r.vsync_runq_us -gt 5000) { $c['vsync preempted']++ }
    elseif ([double]$r.cp_cap_wait_us -gt 18000) { $c['cap late']++ }
    elseif ([double]$r.cp_cpu_us -gt 18000) { $c['cp work']++ }
    else { $c['other']++ }
  }
  $mid = @($slow | ? { [double]$_.frame_time_us -lt 40000 }).Count
  "{0}: {1} frames, slow {2} ({3:P1}; {4} of them 35-40 ms) = {5}" -f $t, $sel.Count, $slow.Count, ($slow.Count / [math]::Max(1, $sel.Count)), $mid, (($c.GetEnumerator() | ? { $_.Value } | Sort-Object Value -Descending | % { "$($_.Key) $($_.Value)" }) -join ', ')
}
