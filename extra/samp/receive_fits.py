#!/usr/bin/env python3
"""Receive the science FITS files HelioFITS Studio announces over SAMP (HS-13).

Studio's "Send to Python" writes double-precision FITS under ~/HFStudio/Exports/ and sends one
`image.load.fits` notification per file (params: `url`, `name`). This script joins the hub, prints
what arrives and, with --collect, keeps the paths so a notebook can import `received`.

    python3 extra/samp/receive_fits.py            # needs Astropy; run until Ctrl-C
    python3 extra/samp/receive_fits.py --once     # exit after the first file

The files are physical values only; the manifest.json beside them says what was and was not applied.
RHEF output is a visualization, not a calibrated radiance.
"""
from __future__ import annotations

import argparse
import threading
from pathlib import Path
from urllib.parse import unquote, urlparse

MTYPE = "image.load.fits"
received: list[Path] = []


def url_to_path(url: str) -> Path:
    """A file: URL (what Studio sends) as a Path; anything else is refused."""
    parsed = urlparse(url)
    if parsed.scheme != "file":
        raise ValueError(f"not a file: URL: {url!r}")
    return Path(unquote(parsed.path))


def describe(path: Path) -> str:
    """One line about a received file, from its own header (needs Astropy)."""
    from astropy.io import fits

    with fits.open(path) as hdul:
        hdu = hdul[0]
        return f"{path.name}: shape {hdu.data.shape}, {hdu.data.dtype}, DATE-OBS {hdu.header.get('DATE-OBS', 'n/a')}"


def accept(params: dict, done: threading.Event | None = None, once: bool = False) -> Path | None:
    """Handle one notification's params; returns the path, or None when it is refused."""
    try:
        path = url_to_path(params.get("url", ""))
    except ValueError as err:
        print(f"ignored: {err}")
        return None
    if not path.is_file():
        print(f"ignored: no such file: {path}")
        return None
    received.append(path)
    print(describe(path))
    if once and done is not None:
        done.set()
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--once", action="store_true", help="exit after the first file")
    args = parser.parse_args()

    from astropy.samp import SAMPIntegratedClient

    done = threading.Event()
    client = SAMPIntegratedClient(name="HFStudio FITS receiver", description="Receives image.load.fits from HelioFITS Studio")
    client.connect()
    client.bind_receive_notification(MTYPE, lambda _key, _sender, _mtype, params, _extra: accept(params, done, args.once))
    print(f"listening for {MTYPE} (Ctrl-C to stop)")
    try:
        done.wait()
    except KeyboardInterrupt:
        pass
    finally:
        client.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
