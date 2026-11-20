#!/usr/bin/env python3
"""Capture the JPL Horizons and SBDB answers that the comet 3D checks read (initiative comet3d, PR1).

The cloud machines that build Studio cannot reach ssd.jpl.nasa.gov, so this runs once on a machine
that can. Standard library only; nothing in the repository needs to be built first.

    python3 extra/tools/capture_horizons_fixtures.py --des "C/2025 R3" \
        --start 2026-04-20 --end 2026-04-30 --out extra/test/data

For every request it writes <name>.txt (the Horizons "result" text, or the SBDB JSON) and
<name>.url (the exact URL asked), then prints a summary. A Horizons refusal is still written, so the
answer can be read, and is reported as a failure at the end.

Request shapes follow the comet3d Phase 1 plan (revision 2, sections 1.3 and 1.6) and the API page
https://ssd-api.jpl.nasa.gov/doc/horizons.html: Sun-centred geometric state vectors (VEC_TABLE 2),
J2000 ecliptic, ICRF, km and km/s, UT. The parameter order and quoting match io/JplHorizons.java so
the Java request builder can be checked against the .url files.
"""

import argparse
import json
import math
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

HORIZONS = "https://ssd.jpl.nasa.gov/api/horizons.api"
SBDB = "https://ssd-api.jpl.nasa.gov/sbdb.api"

# Constants (read): Sun.java uses the IAU nominal radius; GM is the IAU 2015 nominal solar GM.
RSUN_M = 6.957e8
GM_SUN = 1.3271244e20  # m^3 s^-2
AU_M = 1.495978707e11
JD_UNIX_EPOCH = 2440587.5
TDB_MINUS_UTC_S = 69.184  # TT-UTC for 2017 onward (37 leap seconds + 32.184 s); TDB-TT is under 2 ms

# Step policy, plan revision 2 section 1.4 (POLICY 2): minutes = clamp(floor(7.5 r^1.25), 2, 1440).
STEP_K = 7.5
STEP_MIN, STEP_MAX = 2, 1440
MAX_STEPS = 2000  # io/JplHorizons.java MAX_STEPS: Studio's own cap, not Horizons'
DENSE_HALF = timedelta(days=2)
PAD = timedelta(days=1)

TIME = "%Y-%m-%d %H:%M:%S"  # io/JplHorizons.java TIME
OBS_TIME = "%Y-%m-%d %H:%M"  # astronomy/Comets.java HORIZONS_IN


def q(key, value):
    return "&" + key + "=" + urllib.parse.quote_plus(value, safe="")


def states_url(command, start, end, step):
    """step: "<n> m" for minutes, or a bare integer string for that many equal intervals."""
    return (HORIZONS + "?format=json" + q("COMMAND", "'" + command + "'") + q("EPHEM_TYPE", "VECTORS")
            + q("CENTER", "'500@10'") + q("REF_PLANE", "ECLIPTIC") + q("REF_SYSTEM", "ICRF") + q("OUT_UNITS", "KM-S")
            + q("VEC_TABLE", "2") + q("VEC_CORR", "NONE") + q("CSV_FORMAT", "YES") + q("TIME_TYPE", "UT")
            + q("OBJ_DATA", "NO") + q("START_TIME", "'" + start.strftime(TIME) + "'")
            + q("STOP_TIME", "'" + end.strftime(TIME) + "'") + q("STEP_SIZE", "'" + step + "'"))


def frame_url(command, start, end, step):
    """The same request in the ICRF equatorial frame, for the obliquity check."""
    return states_url(command, start, end, step).replace(q("REF_PLANE", "ECLIPTIC"), q("REF_PLANE", "FRAME"))


def observer_url(command, quantities, start, end, step_minutes):
    """As astronomy/Comets.java ephemerisText builds it: observer table from SOHO (500@-21)."""
    pairs = [("format", "text"), ("COMMAND", command), ("OBJ_DATA", "NO"), ("MAKE_EPHEM", "YES"),
             ("EPHEM_TYPE", "OBSERVER"), ("CENTER", "'500@-21'"), ("START_TIME", "'" + start.strftime(OBS_TIME) + "'"),
             ("STOP_TIME", "'" + end.strftime(OBS_TIME) + "'"), ("STEP_SIZE", "'" + str(step_minutes) + " m'"),
             ("QUANTITIES", quantities), ("CSV_FORMAT", "'YES'")]
    return HORIZONS + "?" + "&".join(k + "=" + urllib.parse.quote(v, safe="") for k, v in pairs)


def step_minutes(r_rsun):
    return int(min(max(math.floor(STEP_K * r_rsun ** 1.25), STEP_MIN), STEP_MAX))


def parabola_r(q_rsun, dt_s):
    """Heliocentric distance on a parabola of perihelion q at time dt from perihelion (Barker)."""
    q_m = q_rsun * RSUN_M
    w = dt_s / math.sqrt(2 * q_m ** 3 / GM_SUN)  # D + D^3/3 = w
    y = (1.5 * w + math.sqrt(2.25 * w * w + 1)) ** (1 / 3)
    d = y - 1 / y
    return q_rsun * (1 + d * d)


def segments(q_rsun, tp, start, end):
    """Pieces split at tp -/+ 2 d, each uniform at the step of its smallest r, on whole minutes.

    The piece before perihelion ends exactly where the dense piece starts, and the piece after
    starts exactly where the dense piece ends, so neighbours share their boundary sample.
    """
    lo, hi = floor_minute(start), ceil_minute(end)
    if tp is None or not math.isfinite(q_rsun):
        step = max(1, math.ceil(minutes(lo, hi) / MAX_STEPS))
        return [(lo, lo + timedelta(minutes=step * math.ceil(minutes(lo, hi) / step)), step)]
    a, b = floor_minute(tp - DENSE_HALF), ceil_minute(tp + DENSE_HALF)
    out = []
    d_lo, d_hi = max(lo, a), min(hi, b)
    if d_hi > d_lo:  # dense piece, anchored at its start
        step = capped(step_minutes(q_rsun if d_lo <= tp <= d_hi else
                                   parabola_r(q_rsun, (d_hi - tp if d_hi < tp else d_lo - tp).total_seconds())), d_lo, d_hi)
        d_hi = d_lo + timedelta(minutes=step * math.ceil(minutes(d_lo, d_hi) / step))
        out.append((d_lo, d_hi, step))
    if lo < a:  # before: anchored at its end, which is the dense start when there is one
        e = min(hi, a) if not out else out[0][0]
        step = capped(step_minutes(parabola_r(q_rsun, (e - tp).total_seconds())), lo, e)
        out.insert(0, (e - timedelta(minutes=step * math.ceil(minutes(lo, e) / step)), e, step))
    if hi > b:  # after: anchored at its start, which is the dense end when there is one
        s = max(lo, b) if not out or out[-1][1] <= a else out[-1][1]
        step = capped(step_minutes(parabola_r(q_rsun, (s - tp).total_seconds())), s, hi)
        out.append((s, s + timedelta(minutes=step * math.ceil(minutes(s, hi) / step)), step))
    return out


def minutes(a, b):
    return (b - a).total_seconds() / 60


def capped(step, a, b):
    return step if minutes(a, b) / step <= MAX_STEPS else math.ceil(minutes(a, b) / MAX_STEPS)


def floor_minute(t):
    return t.replace(second=0, microsecond=0)


def ceil_minute(t):
    f = floor_minute(t)
    return f if f == t else f + timedelta(minutes=1)


def get(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": "HFStudio-fixture-capture"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def horizons_result(url):
    body = get(url)
    if "format=json" in url:
        data = json.loads(body)
        if "result" not in data:
            raise RuntimeError("Horizons answered without a result: " + body[:300])
        return data["result"]
    return body


def slug(text):
    return re.sub(r"[^A-Za-z0-9-]+", "_", text).strip("_")


def utc(text):
    t = datetime.fromisoformat(text)
    return t.replace(tzinfo=timezone.utc) if t.tzinfo is None else t.astimezone(timezone.utc)


def sbdb(des):
    url = SBDB + "?" + urllib.parse.urlencode({"des": des, "full-prec": "1", "cd-tp": "1"})
    body = get(url)
    data = json.loads(body)
    if "list" in data:
        raise SystemExit("SBDB matched several objects for " + des + ": "
                         + ", ".join(e.get("pdes", "?") + " (" + e.get("name", "") + ")" for e in data["list"])
                         + ". Re-run with --des set to one pdes.")
    if "orbit" not in data:
        raise SystemExit("SBDB has no orbit for " + des + ": " + body[:300])
    elements = {e["name"]: e for e in data["orbit"]["elements"]}
    return url, body, data, elements


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--des", required=True, help='comet designation as SBDB knows it, e.g. "C/2025 R3"')
    ap.add_argument("--start", required=True, help="movie start, UTC, ISO (2026-04-20 or 2026-04-20T07:00)")
    ap.add_argument("--end", required=True, help="movie end, UTC, ISO")
    ap.add_argument("--out", default="extra/test/data", help="folder to write into")
    ap.add_argument("--dry-run", action="store_true", help="print the URLs only")
    ap.add_argument("--q-au", type=float, help="skip SBDB (dry runs only): perihelion distance, au")
    ap.add_argument("--tp-jd", type=float, help="skip SBDB (dry runs only): perihelion time, JD TDB")
    args = ap.parse_args()

    start, end = utc(args.start), utc(args.end)
    if end <= start:
        raise SystemExit("--end must be after --start")
    out = Path(args.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    name = slug(args.des).lower()

    if args.q_au is not None and args.tp_jd is not None:
        if not args.dry_run:
            raise SystemExit("--q-au and --tp-jd are for dry runs; a capture takes them from SBDB")
        sb_url, sb_body, sb = "(SBDB skipped)", "", {"orbit": {}}
        q_au, tp_jd = args.q_au, args.tp_jd
    else:
        sb_url, sb_body, sb, el = sbdb(args.des)
        q_au = float(el["q"]["value"])
        tp_jd = float(el["tp"]["value"])  # TDB Julian day (SBDB API page)
    tp = (datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(days=tp_jd - JD_UNIX_EPOCH)
          - timedelta(seconds=TDB_MINUS_UTC_S))
    q_rsun = q_au * AU_M / RSUN_M
    print("SBDB:", sb.get("object", {}).get("fullname", args.des))
    print("  q  = %.6f au = %.2f Rsun" % (q_au, q_rsun))
    print("  tp = JD %.5f TDB = %s UTC (approx.)" % (tp_jd, tp.strftime(TIME)))
    print("  orbit_id %s, source %s" % (sb["orbit"].get("orbit_id"), sb["orbit"].get("source")))

    padded = (start - PAD, end + PAD)
    requests = []  # (fixture name, url)
    earth0, earth1 = utc("2025-06-20"), utc("2025-06-22")
    requests.append(("horizons-earth-2025-06-21-ecliptic", states_url("399", earth0, earth1, "360 m")))
    requests.append(("horizons-earth-2025-06-21-frame", frame_url("399", earth0, earth1, "360 m")))
    command = "DES=" + args.des + "; CAP; NOFRAG;"
    for i, (lo, hi, step) in enumerate(segments(q_rsun, tp, *padded)):
        requests.append(("horizons-comet-%s-states-%d" % (name, i), states_url(command, lo, hi, "%d m" % step)))
    for body, cmd in (("soho", "-21"), ("stereo-a", "-234")):
        requests.append(("horizons-%s-%s-states" % (body, name), states_url(cmd, padded[0], padded[1], "60 m")))
    obs_step = min(max((end - start).total_seconds() // 60 // 2000, 1), 30)  # Comets.ephemeris
    requests.append(("horizons-comet-%s-observer-soho" % name,
                     observer_url("'DES=" + args.des + "; CAP;'", "'23,27'", start, end, int(obs_step))))
    requests.append(("horizons-sun-%s-observer-soho" % name, observer_url("'10'", "'17,20'", start, end, int(obs_step))))
    p0, p1 = utc("2023-10-01"), utc("2023-10-02")
    for form in ("2P", "P/2P"):  # Comets.catalog builds prefix + '/' + pdes; which form resolves?
        requests.append(("horizons-probe-%s" % slug(form), states_url("DES=" + form + "; CAP; NOFRAG;", p0, p1, "360 m")))

    if args.dry_run:
        print(sb_url)
        for n, u in requests:
            print(n + "\t" + u)
        return 0

    (out / ("sbdb-%s.json" % name)).write_text(sb_body, encoding="utf-8")
    (out / ("sbdb-%s.url" % name)).write_text(sb_url + "\n", encoding="utf-8")
    failed = []
    for n, u in requests:
        try:
            text = horizons_result(u)
        except (urllib.error.URLError, RuntimeError, json.JSONDecodeError, TimeoutError) as e:
            failed.append((n, str(e)))
            print("FAIL " + n + ": " + str(e))
            continue
        (out / (n + ".txt")).write_text(text, encoding="utf-8")
        (out / (n + ".url")).write_text(u + "\n", encoding="utf-8")
        target = next((ln.strip() for ln in text.splitlines() if ln.startswith("Target body name")), "")
        rows = text.count("\n", text.find("$$SOE"), text.find("$$EOE")) - 1 if "$$SOE" in text else 0
        ok = rows > 0
        if not ok:
            failed.append((n, "no ephemeris table"))
        print(("ok   " if ok else "FAIL ") + n + ": " + str(rows) + " rows; " + target)
    print()
    print("Wrote %d files to %s" % (2 + 2 * (len(requests) - len(failed)), out))
    if failed:
        print("Failures (the probe for one of 2P and P/2P is expected to fail; that is what it asks):")
        for n, why in failed:
            print("  " + n + ": " + why)
    return 0


if __name__ == "__main__":
    sys.exit(main())
