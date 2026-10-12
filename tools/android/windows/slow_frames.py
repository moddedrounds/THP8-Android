import csv, sys
# Characterise slow frames in a frames.csv: runs of consecutive >18 ms frames, and the columns in them.
rows = list(csv.DictReader(open(sys.argv[1])))
skip = int(sys.argv[2]) if len(sys.argv) > 2 else 0
rows = rows[skip:]
cols = ['frame_time_us', 'draw_calls', 'gpu_busy_us', 'cp_cpu_us', 'cp_ring_idle_us', 'cp_regmem_wait_us',
        'cp_cap_wait_us', 'swap_time_us', 'cp_runq_us', 'vsync_runq_us', 'pipeline_cache_misses', 'texture_cache_misses']
def f(r, c):
    try: return float(r[c])
    except: return 0.0
ft = [f(r, 'frame_time_us') / 1000 for r in rows]
n = len(ft)
slow = [i for i in range(n) if ft[i] > 18]
print('frames', n, 'slow', len(slow), '%.2f%%' % (100 * len(slow) / max(n, 1)))
# runs
runs = []
i = 0
while i < n:
    if ft[i] > 18:
        j = i
        while j < n and ft[j] > 18: j += 1
        runs.append((i, j - i))
        i = j
    else:
        i += 1
from collections import Counter
print('run length histogram', sorted(Counter(min(l, 20) for _, l in runs).items()))
# histogram of slow frame times
print('slow ft hist', sorted(Counter(int(ft[i] // 4 * 4) for i in slow).items()))
def avg(idx, c):
    return sum(f(rows[i], c) for i in idx) / max(len(idx), 1) / (1000 if c.endswith('_us') else 1)
fast = [i for i in range(n) if ft[i] <= 18]
for c in cols:
    print('%-24s fast %8.2f  slow %8.2f' % (c, avg(fast, c), avg(slow, c)))
# longest runs detail
for s, l in sorted(runs, key=lambda x: -x[1])[:6]:
    print('run at', s, 'len', l)
    for k in range(max(0, s - 2), min(n, s + min(l, 6) + 2)):
        r = rows[k]
        print('  ', k, ' '.join('%s=%.1f' % (c[:10], f(r, c) / (1000 if c.endswith('_us') else 1)) for c in cols))
