#!/usr/bin/env python3
"""Dev-only: cut the small IMAP fixtures in extra/test/data/ from real SPDF files.

Needs `pip install cdflib numpy`. Not run by any check. Usage:
  make_imap_fixtures.py <swapi proton-sw .cdf> <mag norm-rtn .cdf> <imap_merged_hr2026.txt> [outdir]
Source files: https://spdf.gsfc.nasa.gov/pub/data/imap/swapi/l3a/proton-sw/2026/ ,
https://spdf.gsfc.nasa.gov/pub/data/imap/mag/l2/norm-rtn/2026/ ,
https://spdf.gsfc.nasa.gov/pub/data/imap/coho1hr_magplasma/ascii/ . It never overwrites: move old files aside first.
"""
import sys
from pathlib import Path

import cdflib
import cdflib.cdfwrite as cw
import numpy as np

def cut(src, dst, keep, edit=None):
    """Copy the record-varying variables of src at record indices keep (sorted) into a new gzip CDF."""
    c = cdflib.CDF(src)
    info = c.cdf_info()
    out = cw.CDF(dst, cdf_spec={"Compressed": 6})
    g = c.globalattsget()
    ga = {"Logical_source": {0: g["Logical_source"][0]},
          "Acknowledgement": {0: g["Acknowledgement"][0]}}
    out.write_globalattrs(ga)
    for name in info.zVariables:
        inf = c.varinq(name)
        if inf.Last_Rec < 1:  # label / direction variables: not needed by the reader
            continue
        data = np.asarray(c.varget(name))[keep]
        if edit:
            data = edit(name, data)
        attrs = {k: v for k, v in c.varattsget(name).items() if k in ("VAR_TYPE", "FILLVAL", "UNITS", "DEPEND_0")}
        attrs = {k: (str(v) if k != "FILLVAL" else v) for k, v in attrs.items()}
        spec = {"Variable": name, "Data_Type": cw.CDF._datatype_token(inf.Data_Type_Description), "Num_Elements": 1, "Rec_Vary": True,
                "Dim_Sizes": list(inf.Dim_Sizes)}
        out.write_var(spec, var_attrs=attrs, var_data=data)
    out.close()


def swapi(src, dst):
    c = cdflib.CDF(src)
    t = np.asarray(c.varget("epoch"))
    flags = np.asarray(c.varget("swp_flags"))
    gap = int(np.argmax(np.diff(t)))
    keep = sorted(set(range(0, 12)) | set(range(gap - 5, gap + 6)) | set(np.flatnonzero(flags != 0)[:10].tolist()))
    cut(src, dst, keep)


def mag(src, dst):
    c = cdflib.CDF(src)
    n = len(c.varget("epoch"))
    keep = list(range(0, 300)) + list(range(900, 1200))  # minutes 0-2 and 7-9; 3-6 absent (a gap)

    def edit(name, d):
        d = d.copy()
        if name == "quality_flags":
            d[:6] = 1  # six bad samples at the start of minute 0
        if name == "b_rtn":
            d[10, :] = -1e31  # one fill sample in minute 0
        return d

    assert n >= 1200
    cut(src, dst, keep, edit)


def coho(src, dst):
    lines = Path(src).read_text().splitlines()
    out = ["# header line that is not data"] + lines[:6]
    f = lines[6].split()  # a row with fills in B (words 7-10), V_N (14) and T (16), as the file writes them
    out.append(" ".join(f[:6] + ["9999.99"] * 4 + f[10:13] + ["99999.9", f[14], "9999999."]))
    out += lines[7:9]
    Path(dst).write_text("\n".join(out) + "\n")


LISTING = """<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN">
<html><head><title>Index of /pub/data/imap/swapi/l3a/proton-sw/2026</title></head><body>
<h1>Index of /pub/data/imap/swapi/l3a/proton-sw/2026</h1>
<table>
   <tr><th><a href="?C=N;O=D">Name</a></th><th><a href="?C=M;O=A">Last modified</a></th><th><a href="?C=S;O=A">Size</a></th></tr>
<tr><td><a href="/pub/data/imap/swapi/l3a/proton-sw/">Parent Directory</a></td><td>&nbsp;</td><td align="right">  - </td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260101_v002.0011.cdf">imap_swapi_l3a_proton-sw_20260101_v002.0011.cdf</a></td><td align="right">2026-11-05 10:48  </td><td align="right">174K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260102_v001.cdf">imap_swapi_l3a_proton-sw_20260102_v001.cdf</a></td><td align="right">2026-10-01 08:00  </td><td align="right">170K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260102_v002.0011.cdf">imap_swapi_l3a_proton-sw_20260102_v002.0011.cdf</a></td><td align="right">2026-11-05 10:48  </td><td align="right">172K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260102_v002.0012.cdf">imap_swapi_l3a_proton-sw_20260102_v002.0012.cdf</a></td><td align="right">2026-11-06 09:30  </td><td align="right">173K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260105_v002.cdf">imap_swapi_l3a_proton-sw_20260105_v002.cdf</a></td><td align="right">2026-11-05 10:48  </td><td align="right"> 62K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260105_v002.0011.cdf">imap_swapi_l3a_proton-sw_20260105_v002.0011.cdf</a></td><td align="right">2026-11-05 10:49  </td><td align="right"> 64K</td></tr>
<tr><td><a href="imap_swapi_l3a_alpha-sw_20260101_v002.0011.cdf">imap_swapi_l3a_alpha-sw_20260101_v002.0011.cdf</a></td><td align="right">2026-11-05 10:48  </td><td align="right">150K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20260301_v002.0011.cdf.tmp">imap_swapi_l3a_proton-sw_20260301_v002.0011.cdf.tmp</a></td><td align="right">2026-11-05 10:48  </td><td align="right">1K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw_20261340_v002.cdf">imap_swapi_l3a_proton-sw_20261340_v002.cdf</a></td><td align="right">2026-11-05 10:48  </td><td align="right">1K</td></tr>
<tr><td><a href="imap_swapi_l3a_proton-sw-ialirt_20260110_v001.cdf">imap_swapi_l3a_proton-sw-ialirt_20260110_v001.cdf</a></td><td align="right">2026-11-05 10:48  </td><td align="right">2K</td></tr>
<tr><td><a href="readme.txt">readme.txt</a></td><td align="right">2026-11-05 10:48  </td><td align="right">1.2K</td></tr>
</table>
</body></html>
"""


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    outdir = Path(sys.argv[4] if len(sys.argv) > 4 else Path(__file__).resolve().parents[1] / "test" / "data")
    jobs = [("imap-swapi-proton-sample.cdf", lambda d: swapi(sys.argv[1], d)),
            ("imap-mag-rtn-sample.cdf", lambda d: mag(sys.argv[2], d)),
            ("imap-merged-hr-sample.txt", lambda d: coho(sys.argv[3], d)),
            ("imap-spdf-listing.html", lambda d: Path(d).write_text(LISTING))]
    for name, job in jobs:
        dst = outdir / name
        if dst.exists():
            print("exists, not overwriting:", dst)
            continue
        job(str(dst))
        print("wrote", dst)


if __name__ == "__main__":
    main()
