#!/usr/bin/env python3
"""Minimal XDVDFS (Xbox / Xbox 360 game disc) lister / extractor.

  xdvdfs.py <image.iso> [out_dir]     no out_dir -> list only
"""
import os
import struct
import sys

MAGIC = b"MICROSOFT*XBOX*MEDIA"
SECTOR = 2048
# Game partition offsets: plain XISO, XGD3, XGD2, XGD1 (redump layouts).
OFFSETS = [0, 0x2080000, 0xFD90000, 0x18300000]


def find_partition(f):
    for off in OFFSETS:
        f.seek(off + 32 * SECTOR)
        if f.read(20) == MAGIC:
            root_sector, root_size = struct.unpack("<II", f.read(8))
            return off, root_sector, root_size
    raise SystemExit("no XDVDFS volume found")


def walk(f, base, sector, size, path, out):
    if size == 0:
        return
    f.seek(base + sector * SECTOR)
    table = f.read(size)
    seen = set()

    def node(o):
        if o in seen or o + 14 > len(table):
            return
        seen.add(o)
        left, right, start, length, attr, nlen = struct.unpack_from("<HHIIBB", table, o)
        if left == 0xFFFF and right == 0xFFFF and start == 0xFFFFFFFF:
            return
        name = table[o + 14:o + 14 + nlen].decode("latin-1")
        if left:
            node(left * 4)
        rel = f"{path}/{name}" if path else name
        if attr & 0x10:
            out.append((rel, None, None))
            walk(f, base, start, length, rel, out)
        else:
            out.append((rel, start, length))
        if right:
            node(right * 4)

    node(0)


def main():
    iso = sys.argv[1]
    dest = sys.argv[2] if len(sys.argv) > 2 else None
    with open(iso, "rb") as f:
        base, rs, rz = find_partition(f)
        entries = []
        walk(f, base, rs, rz, "", entries)
        files = [e for e in entries if e[1] is not None]
        total = sum(e[2] for e in files)
        print(f"partition 0x{base:X}: {len(files)} files, {total / 2**30:.2f} GiB")
        if not dest:
            for rel, s, n in entries[:60]:
                print(rel if s is None else f"{rel}  {n}")
            return
        for rel, s, n in entries:
            p = os.path.join(dest, rel)
            if s is None:
                os.makedirs(p, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(p), exist_ok=True)
            f.seek(base + s * SECTOR)
            with open(p, "wb") as o:
                left = n
                while left:
                    chunk = f.read(min(left, 8 << 20))
                    if not chunk:
                        raise SystemExit(f"short read in {rel}")
                    o.write(chunk)
                    left -= len(chunk)
        print(f"extracted to {dest}")


if __name__ == "__main__":
    main()
