"""Offline evaluation of the frame generator (frame_generation_shaders/*.comp re-implemented in numpy).

Takes consecutive frames of a real 60 fps recording (screenrecord -> ffmpeg -> PNGs at the game's
size), generates the middle frame of every (i, i+2) pair at t = 0.5 - what 30 + frame generation
does - and scores it against the real frame i+1 (mean absolute error, 0-255 scale):
  all     every pixel
  static  pixels identical in i, i+1, i+2 (HUD, menus, anything that does not move)
  moving  the rest
Usage: py fg_eval.py <frame dir> [variant ...] [--n N] [--dump DIR]
Variants: old (one vector per 16x16 block), pix (per-pixel choice among own / 4 neighbours / zero),
          copy (repeat frame i: the no-generation baseline), fade (plain cross-fade).
"""
import sys, os, glob
import numpy as np
from PIL import Image

KL = np.array([0.299, 0.587, 0.114], np.float32)


def load(p):
    return np.asarray(Image.open(p).convert('RGB'), np.float32) / 255.0


def luma4(img):
    h, w = img.shape[:2]
    h4, w4 = h // 4, w // 4
    y = img[:h4 * 4, :w4 * 4] @ KL
    return y.reshape(h4, 4, w4, 4).mean(axis=(1, 3))


def sample(img, x, y):
    """Bilinear, clamp to edge; x, y in pixel units (centres at +0.5)."""
    h, w = img.shape[:2]
    x = np.clip(x - 0.5, 0, w - 1)
    y = np.clip(y - 0.5, 0, h - 1)
    x0 = np.floor(x).astype(np.int32); y0 = np.floor(y).astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    a = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    b = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return a * (1 - fy) + b * fy


def match(lp, lc, mv_last, lam=0.004, r=6, rng=30):
    """fg_match.comp: 4x4 luma blocks, search +-r around the predicted vector, zero always tried."""
    h, w = lc.shape
    bh, bw = h // 4, w // 4
    cur = lc[:bh * 4, :bw * 4]
    pad = np.pad(lp, rng, mode='edge')
    if mv_last is None:
        pred = np.zeros((bh, bw, 2), np.float32)
    else:
        pred = -mv_last[:bh, :bw] * 0.25  # luma texels, cur -> prev
    centre = np.clip(np.round(np.clip(pred, -24, 24)), -24, 24).astype(np.int32)
    best = np.full((bh, bw), np.inf, np.float32)
    bestd = np.zeros((bh, bw, 2), np.int32)
    # zero first, like the shader
    sad0 = np.abs(cur - lp[:bh * 4, :bw * 4]).reshape(bh, 4, bw, 4).sum(axis=(1, 3))
    best = sad0 + lam * (np.abs(pred[..., 0]) + np.abs(pred[..., 1])) * 0.5
    for dy in range(-rng, rng + 1):
        for dx in range(-rng, rng + 1):
            ok = (np.abs(dx - centre[..., 0]) <= r) & (np.abs(dy - centre[..., 1]) <= r)
            if not ok.any():
                continue
            sh = pad[rng + dy:rng + dy + bh * 4, rng + dx:rng + dx + bw * 4]
            sad = np.abs(cur - sh).reshape(bh, 4, bw, 4).sum(axis=(1, 3))
            s = sad + lam * (np.abs(dx - pred[..., 0]) + np.abs(dy - pred[..., 1]))
            upd = ok & (s < best)
            best = np.where(upd, s, best)
            bestd[upd] = (dx, dy)
    return -bestd.astype(np.float32) * 4.0  # output pixels, prev -> cur


def median3(mv):
    h, w = mv.shape[:2]
    p = np.pad(mv, ((1, 1), (1, 1), (0, 0)), mode='edge')
    stack = np.stack([p[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy in (-1, 0, 1) for dx in (-1, 0, 1)])
    return np.median(stack, axis=0)


def mv_bilinear(mv, H, W):
    """texture(mv, uv) with a linear sampler: block centres at 16*(b+0.5)."""
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    bx = (xs + 0.5) / 16.0; by = (ys + 0.5) / 16.0  # in block units, centres at +0.5
    return sample(mv, bx, by)


def err(prev, cur, qx, qy, v, t):
    ax = qx - t * v[..., 0]; ay = qy - t * v[..., 1]
    cx = qx + (1 - t) * v[..., 0]; cy = qy + (1 - t) * v[..., 1]
    e = 0
    for i, wgt in ((-1, 1.0), (0, 2.0), (1, 1.0)):
        a = sample(prev, ax + i, ay); c = sample(cur, cx + i, cy)
        e = e + wgt * (np.abs(a - c) @ KL)
    return e * 0.25


def interp(prev, cur, mv, t, variant, bias=0.006, still=None):
    H, W = prev.shape[:2]
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    qx = xs + 0.5; qy = ys + 0.5
    v = mv_bilinear(mv, H, W)
    age = '+age' in variant
    fb = 'cur' if '+cur' in variant else ('prev' if '+prev' in variant else 'fade')
    variant = variant.replace('+age', '').replace('+cur', '').replace('+prev', '')
    name, _, opt = variant.partition(':')
    if opt:
        bias = float(opt)
    if name in ('pix', 'pixz'):
        best = err(prev, cur, qx, qy, v, t)
        own = v.copy()
        cands = [np.zeros_like(v)]
        bh, bw = mv.shape[:2]
        by = np.minimum((ys // 16).astype(np.int32), bh - 1); bx = np.minimum((xs // 16).astype(np.int32), bw - 1)
        if name == 'pix':
            for oy, ox in ((0, -1), (0, 1), (-1, 0), (1, 0)):
                cands.append(mv[np.clip(by + oy, 0, bh - 1), np.clip(bx + ox, 0, bw - 1)])
        for k, n in enumerate(cands):
            e = err(prev, cur, qx, qy, n, t) + bias
            upd = e < best
            if k > 0:
                upd &= ~np.all(np.abs(n - own) < 0.5, axis=-1)
            best = np.where(upd, e, best)
            v = np.where(upd[..., None], n, v)
    if age and still is not None:
        v = np.where(still[..., None], 0.0, v)
    a = sample(prev, qx - t * v[..., 0], qy - t * v[..., 1])
    c = sample(cur, qx + (1 - t) * v[..., 0], qy + (1 - t) * v[..., 1])
    warped = a * (1 - t) + c * t
    fade = prev * (1 - t) + cur * t
    if fb == 'cur':
        fade = cur
    elif fb == 'prev':
        fade = prev
    d = np.linalg.norm(a - c, axis=-1)
    w = np.clip((d - 0.08) / (0.25 - 0.08), 0, 1)
    w = (w * w * (3 - 2 * w))[..., None]
    return warped * (1 - w) + fade * w


def main():
    args = sys.argv[1:]
    n = 40; dump = None
    if '--n' in args:
        i = args.index('--n'); n = int(args[i + 1]); del args[i:i + 2]
    if '--dump' in args:
        i = args.index('--dump'); dump = args[i + 1]; del args[i:i + 2]
    d = args[0]
    variants = args[1:] or ['copy', 'fade', 'old', 'pix']
    files = sorted(glob.glob(os.path.join(d, '*.png')))
    frames = [load(f) for f in files[:2 * n + 3]]
    # drop recording duplicates (identical consecutive frames)
    uniq = [frames[0]]
    for f in frames[1:]:
        if np.abs(f - uniq[-1]).mean() > 0.0015:
            uniq.append(f)
    res = {v: [] for v in variants}
    mv_last = None
    for i in range(0, len(uniq) - 2, 2):
        p, mid, c = uniq[i], uniq[i + 1], uniq[i + 2]
        mv = median3(match(luma4(p), luma4(c), mv_last))
        mv_last = mv
        # still for two real frame pairs (i-2 -> i -> i+2): the static-age rule
        eps = 3 / 255
        still = (np.abs(p - c).max(-1) < eps) & ((np.abs(uniq[i - 2] - p).max(-1) < eps) if i >= 2 else False)
        static = (np.abs(p - mid).max(-1) < 2 / 255) & (np.abs(c - mid).max(-1) < 2 / 255)
        for v in variants:
            if v == 'copy':
                out = p
            elif v == 'fade':
                out = (p + c) * 0.5
            else:
                out = interp(p, c, mv, 0.5, v, still=still)
            e = np.abs(out - mid).mean(-1) * 255
            res[v].append((e.mean(), e[static].mean() if static.any() else 0, e[~static].mean(), static.mean()))
            if dump and i // 2 < 6:
                Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).save(os.path.join(dump, f'{i:03d}_' + v.replace(':', '_').replace('+', '_') + '.png'))
        if dump and i // 2 < 6:
            Image.fromarray((mid * 255).astype(np.uint8)).save(os.path.join(dump, f'{i:03d}_real.png'))
    print(f'{len(res[variants[0]])} triplets from {len(uniq)} unique frames')
    for v in variants:
        a = np.array(res[v])
        print(f'{v:6s} all {a[:,0].mean():6.2f}  static {a[:,1].mean():6.2f}  moving {a[:,2].mean():6.2f}  (static share {a[:,3].mean()*100:.1f} %)')


if __name__ == '__main__':
    main()
