import csv, sys
from collections import Counter
# spikes.py frames.csv [skip]: classify frames > 18 ms by what else happened in them.
rows = list(csv.DictReader(open(sys.argv[1])))[int(sys.argv[2]) if len(sys.argv) > 2 else 0:]
def f(r, c):
    try: return float(r[c])
    except: return 0.0
slow = [r for r in rows if f(r, 'frame_time_us') > 18000]
print('frames', len(rows), 'slow', len(slow))
cls = Counter()
for r in slow:
    if f(r, 'pipeline_cache_misses') > 0: k = 'new pipeline (shader compile)'
    elif f(r, 'texture_cache_misses') > 5: k = 'new textures (>5)'
    elif f(r, 'cp_runq_us') > 3000 or f(r, 'vsync_runq_us') > 3000: k = 'run-queue wait > 3 ms'
    elif f(r, 'gpu_busy_us') > 15000: k = 'GPU > 15 ms'
    elif f(r, 'cp_cpu_us') > 15000: k = 'CP > 15 ms'
    elif f(r, 'cp_ring_idle_us') > 5000: k = 'CP waited for the game (ring idle > 5 ms)'
    else: k = 'other'
    cls[k] += 1
for k, v in cls.most_common():
    print('%5d  %s' % (v, k))
big = [r for r in rows if f(r, 'frame_time_us') > 35000]
print('> 35 ms:', len(big), ' with new pipelines:', sum(1 for r in big if f(r, 'pipeline_cache_misses') > 0),
      ' new textures:', sum(1 for r in big if f(r, 'texture_cache_misses') > 0),
      ' ring idle > 10 ms:', sum(1 for r in big if f(r, 'cp_ring_idle_us') > 10000))
