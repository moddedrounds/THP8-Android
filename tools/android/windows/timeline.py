import csv, sys
# Per-2-second timeline of a frames.csv: fps, draws, CP cpu, ring idle, regmem wait, gpu busy.
rows = list(csv.DictReader(open(sys.argv[1])))
step = float(sys.argv[2]) if len(sys.argv) > 2 else 2.0
t = 0.0; acc = []; start = 0.0
def f(r, c):
    try: return float(r[c])
    except: return 0.0
for r in rows:
    ft = f(r, 'frame_time_us') / 1e6
    if ft <= 0: continue
    acc.append(r); t += ft
    if t - start >= step:
        n = len(acc)
        a = lambda c: sum(f(x, c) for x in acc) / n / 1000
        print('%6.1fs %5.1f fps draws %5.0f cp %5.1f idle %5.1f regmem %5.1f gpu %5.1f' % (
            start, n / (t - start), sum(f(x, 'draw_calls') for x in acc) / n, a('cp_cpu_us'),
            a('cp_ring_idle_us'), a('cp_regmem_wait_us'), a('gpu_busy_us')))
        acc = []; start = t
