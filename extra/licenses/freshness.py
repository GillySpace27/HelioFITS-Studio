#!/usr/bin/env python3
"""Report which bundled JARs have a newer release on Maven Central.

Informational only: reads lib/, never modifies a JAR, always exits 0.
Python 3.11+, standard library only.

Coordinates come from the JAR's own META-INF/maven/<group>/<artifact>/pom.properties
when exactly one of them names the JAR, else from the optional hand-filled map
(extra/licenses/maven-coordinates.json). A mapped coordinate counts only when Maven
Central's published SHA-1 for that coordinate equals the local JAR's SHA-1, so a
wrong guess prints "not verified" instead of a misleading answer.

Output, one tab-separated row per JAR:
    <jar>\t<local version>\t<latest>\t<status>
status is "current", "newer available" or "not verified"; "-" marks an unknown field.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CENTRAL = "https://repo1.maven.org/maven2"
TIMEOUT = 20
PRERELEASE = re.compile(r"(alpha|beta|rc|cr|m\d|snapshot|preview)", re.I)


def fetch(url):
    with urllib.request.urlopen(url, timeout=TIMEOUT) as response:
        return response.read()


def pom_coordinates(jar):
    """(group, artifact, version) from the one pom.properties that names this JAR, else None."""
    stem = jar.name.removesuffix(".jar")
    found = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if not re.fullmatch(r"META-INF/maven/[^/]+/[^/]+/pom\.properties", entry):
                continue
            props = {}
            for line in archive.read(entry).decode("utf-8", "replace").splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    props[key.strip()] = value.strip()
            group, artifact = props.get("groupId"), props.get("artifactId")
            version = props.get("version")
            if group and artifact and version and stem.startswith(f"{artifact}-{version}"):
                found.append((group, artifact, version))
    return found[0] if len(found) == 1 else None


def version_key(version):
    return tuple(int(n) for n in re.findall(r"\d+", version))


def qualifier(version):
    return re.sub(r"^[\d.]+", "", version)


def latest_release(group, artifact, local):
    """Newest listed version shaped like the local one (same -jre/.Final suffix), no pre-releases."""
    url = f"{CENTRAL}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    root = ET.fromstring(fetch(url))
    listed = [v.text.strip() for v in root.findall("./versioning/versions/version") if v.text]
    same = [
        v
        for v in listed
        if qualifier(v) == qualifier(local) and not PRERELEASE.search(v)
    ]
    return max(same, key=version_key) if same else None


def central_sha1(group, artifact, version, classifier=None):
    name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    url = f"{CENTRAL}/{group.replace('.', '/')}/{artifact}/{version}/{name}.sha1"
    return fetch(url).decode("ascii", "replace").split()[0].lower()


def row(jar, root, mapping):
    relative = jar.relative_to(root).as_posix()
    coordinates = pom_coordinates(jar)
    if coordinates:
        group, artifact, version = coordinates
    else:
        entry = mapping.get(relative)
        if not entry:
            return [relative, "-", "-", "not verified"]
        group, artifact, version = entry["group"], entry["artifact"], entry["version"]
        try:
            local = hashlib.sha1(jar.read_bytes()).hexdigest()
            if central_sha1(group, artifact, version, entry.get("classifier")) != local:
                return [relative, version, "-", "not verified"]
        except (OSError, ValueError, IndexError):
            return [relative, version, "-", "not verified"]
    try:
        latest = latest_release(group, artifact, version)
    except (OSError, ValueError, ET.ParseError):
        return [relative, version, "-", "not verified"]
    if not latest:
        return [relative, version, "-", "not verified"]
    status = "newer available" if version_key(latest) > version_key(version) else "current"
    return [relative, version, latest, status]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--map",
        type=Path,
        default=ROOT / "extra/licenses/maven-coordinates.json",
        help="hand-filled coordinates for JARs without a pom.properties",
    )
    args = parser.parse_args()
    mapping = {}
    if args.map.is_file():
        mapping = json.loads(args.map.read_text(encoding="utf-8")).get("artifacts", {})
    for jar in sorted((ROOT / "lib").rglob("*.jar")):
        try:
            cells = row(jar, ROOT, mapping)
        except Exception:  # informational: one unreadable JAR must not hide the others
            cells = [jar.relative_to(ROOT).as_posix(), "-", "-", "not verified"]
        print("\t".join(cells), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
