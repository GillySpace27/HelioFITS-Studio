#!/usr/bin/env python3
"""Build resources/kernels/outer_planets_de440s.bsp from JPL's de440s.

WHY THIS EXISTS
---------------
The shipped de432s_reduced.bsp spans 1990-2050. Sixty years cannot hold one orbit of Saturn
(29.5 years), Uranus (84) or Neptune (164.8), so their orbit traces stop mid-curve. JPL's de440s
covers 1849-2150 and closes all three, but it is 31 MB and most of that is not needed: the Earth
and Moon segments alone are 17 MB and are already covered by the existing kernel.

So this cuts out the four segments that actually need the longer span -- Saturn, Uranus and
Neptune barycentres, plus the Sun they are measured against -- giving 3.5 MB instead of 31.

The result is a build product, not a source file: it is derived entirely from JPL's kernel and is
deliberately kept out of git, since a binary in history cannot be shrunk again without a rewrite.

WHAT IT IS NOT
--------------
Not a re-computation. Segment data is copied word for word out of de440s; the Chebyshev
coefficients are JPL's, untouched. Only the DAF wrapper is rebuilt.

IF IT CANNOT RUN
----------------
Nothing breaks. AppInit treats this kernel as optional, and without it the outer planets fall
back to open orbit arcs, which is the honest picture when the ephemeris does not reach.

Usage: build_outer_planet_kernel.py <output.bsp> [download-cache-dir]
"""
import os
import struct
import sys
import urllib.request

SOURCE_URL = "https://naif.jpl.nasa.gov/pub/naif/generic_kernels/spk/planets/de440s.bsp"
# Saturn, Uranus and Neptune barycentres, and the Sun. Jupiter's 11.9 years already close
# inside the existing kernel's span, so it is not worth the 0.7 MB.
WANT = {6, 7, 8, 10}


def read_summaries(f):
    """Every array in a DAF: its descriptor doubles, its integers, and its name record entry."""
    f.seek(0)
    rec = f.read(1024)
    if rec[0:7] != b'DAF/SPK':
        raise SystemExit("not a DAF/SPK file")
    nd, ni = struct.unpack('<II', rec[8:16])
    fward = struct.unpack('<I', rec[76:80])[0]
    ss = nd + (ni + 1) // 2
    out, n = [], fward
    while n:
        f.seek((n - 1) * 1024)
        r = f.read(1024)
        nxt, _prv, nsum = struct.unpack('<ddd', r[0:24])
        f.seek(n * 1024)                       # the name record follows its summary record
        names = f.read(1024)
        for i in range(int(nsum)):
            off = 24 + i * ss * 8
            dbl = struct.unpack('<%dd' % nd, r[off:off + nd * 8])
            ints = list(struct.unpack('<%di' % ni, r[off + nd * 8: off + nd * 8 + ni * 4]))
            out.append((dbl, ints, names[i * ss * 8:(i + 1) * ss * 8]))
        n = int(nxt)
    return nd, ni, ss, out


def fetch(cache_dir):
    path = os.path.join(cache_dir, "de440s.bsp")
    if os.path.isfile(path) and os.path.getsize(path) > 30_000_000:
        return path
    os.makedirs(cache_dir, exist_ok=True)
    sys.stderr.write("downloading de440s.bsp (31 MB) from NAIF...\n")
    tmp = path + ".part"                        # so an interrupted download is not mistaken for one
    urllib.request.urlretrieve(SOURCE_URL, tmp)
    os.replace(tmp, path)
    return path


def build(src_path, out_path):
    src = open(src_path, 'rb')
    nd, ni, ss, sums = read_summaries(src)
    keep = [s for s in sums if s[1][0] in WANT]
    if len(keep) != len(WANT):
        raise SystemExit(f"expected {len(WANT)} segments, found {len(keep)}")

    DATA_START_REC = 4                          # 1 file, 2 summary, 3 names, 4+ data
    addr = (DATA_START_REC - 1) * 128 + 1       # 1-based word address of the first data word
    blocks = []
    for _dbl, ints, _nm in keep:
        words = ints[5] - ints[4] + 1
        src.seek((ints[4] - 1) * 8)
        blocks.append(src.read(words * 8))
        ints[4], ints[5] = addr, addr + words - 1
        addr += words

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, 'wb') as out:
        src.seek(0)
        filerec = bytearray(src.read(1024))     # keep JPL's file record, including its FTP check
        struct.pack_into('<III', filerec, 76, 2, 2, addr)   # FWARD, BWARD, FREE
        out.write(filerec)

        summary = bytearray(b'\x00' * 1024)
        struct.pack_into('<ddd', summary, 0, 0.0, 0.0, float(len(keep)))
        names = bytearray(b' ' * 1024)
        for i, (dbl, ints, nm) in enumerate(keep):
            off = 24 + i * ss * 8
            struct.pack_into('<%dd' % nd, summary, off, *dbl)
            struct.pack_into('<%di' % ni, summary, off + nd * 8, *ints)
            names[i * ss * 8:(i + 1) * ss * 8] = nm
        out.write(summary)
        out.write(names)
        for b in blocks:
            out.write(b)
        pad = (-out.tell()) % 1024              # DAF files are whole records
        if pad:
            out.write(b'\x00' * pad)
    src.close()


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    out_path = sys.argv[1]
    cache = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.expanduser("~"), ".cache", "jhv-kernels")
    if os.path.isfile(out_path):
        sys.stderr.write(f"{out_path} already present, leaving it alone\n")
        return
    build(fetch(cache), out_path)
    sys.stderr.write(f"wrote {out_path} ({os.path.getsize(out_path) / 1048576:.2f} MB)\n")


if __name__ == "__main__":
    main()
