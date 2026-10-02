#!/usr/bin/env python3
"""Release progress tracker for HelioFITS Studio.

Checks REAL state: the jar's embedded revision against git, the dylib on disk,
the notarization ticket stapled into the dmg, the asset timestamps on the live
GitHub release. Never trusts what an earlier turn in the conversation claimed.

The load-bearing checks are `jar` and `published`.

`jar` compares the revision baked into HFStudio.jar's manifest
(`git rev-list --count HEAD` at build time) against the current HEAD count.
That number is the only provenance link between a shipped binary and its
source. It caught a real drift the day this tracker was written: jar 13211,
HEAD 13215.

`published` compares each live release asset's updatedAt against the local
file's mtime. `deploy_release.sh publish` builds nothing; it uploads whatever
is on disk, and on 2026-07-14 it silently left a month-old dmg on the release
while reporting success. Asset dates are the only thing that catches that.

Every check is a Python function that returns PASS, FAIL or UNCHECKED with a detail. UNCHECKED
means the check could not reach what it grades (no network, no gh, not a Mac): neither done nor
failed, and `done` is false. --selftest grades every check against a tag that never existed and a
retired one and exits 1 if any of them passes there (a check that cannot fail) or raises.

Usage:
    python3 status.py [--done smoketest] [--json] [--emit] [--selftest]
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from typing import NamedTuple

# ─────────────────────────── CONFIG ───────────────────────────

TITLE = "Release HelioFITS Studio"

# This script lives at <repo>/release/skills/ship-hfstudio/scripts/, so the tooling and the
# source it packages are found from here rather than from a hardcoded checkout path.
DEPLOY = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
SRC = os.path.dirname(DEPLOY)

# REPO is defined once, in deploy_release.sh; read it from there so a rename is one edit.
with open(os.path.join(DEPLOY, "deploy_release.sh")) as _f:
    REPO = re.search(r'^REPO="([^"]+)"', _f.read(), re.M).group(1)

# The tag this run is graded against. Releases are per-build and immutable, and
# deploy_release.sh derives the tag from the repository's VERSION file, so the
# tracker does the same: it grades exactly the release publish would create, and
# a bump to VERSION moves it on to the next one.
with open(os.path.join(SRC, "VERSION")) as _f:
    VERSION = _f.read().strip()
TAG = f"v{VERSION}"

# The release's asset names live in one file, release/assets.txt, which deploy_release.sh publish and
# package.yml's attach job read too.
ASSETS_TXT = os.path.join(DEPLOY, "assets.txt")


def read_assets(version: str, source: str | None = None) -> list[str]:
    """Names from release/assets.txt with {v} replaced by version, in file order.

    source is "local" (publish attaches it), "ci" (package.yml attaches it) or None for every row.
    A row that is not "<pattern> local|ci" raises ValueError instead of being skipped.
    """
    names = []
    with open(ASSETS_TXT) as f:
        for n, line in enumerate(f, 1):
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) != 2 or parts[1] not in ("local", "ci"):
                raise ValueError(f"assets.txt:{n}: want '<pattern> local|ci', got {line.strip()!r}")
            if source is None or parts[1] == source:
                names.append(parts[0].replace("{v}", version))
    return names

DMG_NAME = f"HFStudio-{VERSION}.dmg"
ZIP_NAME = f"HFStudio-{VERSION}.zip"
PDF_NAME = "HFStudio-Guide.pdf"

# The slug lives in deploy_release.sh, as build_guide.py already reads it, so a rename is one edit.
with open(os.path.join(DEPLOY, "deploy_release.sh")) as _f:
    REPO = re.search(r'^REPO="([^"]+)"', _f.read(), re.M).group(1)

JAR = f"{SRC}/HFStudio.jar"
DYLIB = f"{SRC}/lib/natives-macos/libjhvmetalhost.dylib"
DMG = f"{DEPLOY}/{DMG_NAME}"
ZIP = f"{DEPLOY}/{ZIP_NAME}"
PDF = f"{DEPLOY}/{PDF_NAME}"

# Identify the candidate by the commit being shipped, not just the procedure
# name. On a dashboard of several runbooks "Release HelioFITS Studio" alone cannot
# tell you whether the card is today's work or last month's.
SUBTITLE_CMD = (
    f"cd {SRC} && echo \"candidate $(git rev-parse --short HEAD)"
    f"  r$(git rev-list --count HEAD)  ($(git branch --show-current))\""
)

PASS, FAIL, UNCHECKED = "PASS", "FAIL", "UNCHECKED"

# The live release carries every asset publish attaches; the Intel dmg is optional (RELEASING.md).
_MIN_LIVE_ASSETS = len([n for n in read_assets(VERSION, "local") if not n.endswith("-intel.dmg")])

# The download page. It names no release itself (it asks GitHub when it loads), apart from fixed
# fallback links used when GitHub cannot be reached.
PAGE_URL = "https://gilly.space/heliofits-studio/"


class Target(NamedTuple):
    """What one run grades: a tag, its version, the source checkout and the release/ folder."""
    tag: str
    version: str
    src: str
    deploy: str
    repo: str

    def path(self, name):
        return os.path.join(self.deploy, name)


def _run(args, cwd=None, timeout=60):
    """(returncode, stdout, stderr) of an argument list; returncode None if it could not run."""
    try:
        r = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout, r.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, "", str(e)


_GH_MISSING = re.compile(r"release not found|not found|HTTP 404", re.I)


def gh_json(args):
    """A read-only gh command that prints JSON. ("ok", data), ("missing", None) when gh answered
    that the thing does not exist, or ("unreachable", None): no gh, no network, not logged in."""
    code, out, err = _run(["gh", *args], timeout=45)
    if code == 0:
        try:
            return "ok", json.loads(out or "null")
        except ValueError:
            return "unreachable", None
    if code is not None and _GH_MISSING.search(err):
        return "missing", None
    return "unreachable", None


def http_get(url):
    """GET, following HTTP redirects. ("ok", text), ("missing", None) for an HTTP error status, or
    ("unreachable", None)."""
    req = urllib.request.Request(url, headers={"User-Agent": "ship-hfstudio-status"})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return "ok", r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError:
        return "missing", None
    except (urllib.error.URLError, OSError, ValueError):
        return "unreachable", None


_release_cache = {}


def release_view(t):
    """The release for t.tag, asked once per run."""
    key = (t.repo, t.tag)
    if key not in _release_cache:
        _release_cache[key] = gh_json(["release", "view", t.tag, "--repo", t.repo,
                                       "--json", "assets,isPrerelease,tagName"])
    return _release_cache[key]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def manifest_attr(jar, key):
    """One main-section attribute of a jar's manifest, or None."""
    try:
        with zipfile.ZipFile(jar) as z:
            text = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except (OSError, KeyError, zipfile.BadZipFile):
        return None
    for line in text.replace("\r", "").split("\n"):
        if line.startswith(key + ": "):
            return line[len(key) + 2:]
    return None


def _newer(a, b):
    """True when file a exists and is newer than file b (the shell's `a -nt b`)."""
    return os.path.exists(a) and os.path.getmtime(a) > os.path.getmtime(b)


def check_pushed(t):
    code, dirty, _ = _run(["git", "-C", t.src, "status", "--porcelain", "src", "resources", "VERSION"])
    if code != 0:
        return FAIL, "not a git checkout"
    if dirty.strip():
        return FAIL, "uncommitted changes in src, resources or VERSION"
    head = _run(["git", "-C", t.src, "rev-parse", "HEAD"])[1].strip()
    code, out, _ = _run(["git", "-C", t.src, "ls-remote", "origin", "refs/heads/master"], timeout=45)
    if code != 0:
        return UNCHECKED, "origin did not answer"
    remote = out.split()[0] if out.split() else ""
    if remote != head:
        return FAIL, f"HEAD {head[:12]} is not origin/master {remote[:12] or '(none)'}"
    return PASS, head[:12]


# Compare the jar's recorded revision against HEAD's commit count, both computed the way build.xml
# computes it, so a mismatch means the jar was built from a different tree than HEAD.
def check_jar(t):
    built = manifest_attr(os.path.join(t.src, "HFStudio.jar"), "revision")
    if built is None:
        return FAIL, "no HFStudio.jar with a revision"
    code, head, _ = _run(["git", "-C", t.src, "rev-list", "--count", "HEAD"])
    if code != 0:
        return FAIL, "not a git checkout"
    head = head.strip()
    return (PASS, f"revision {built}") if built == head else (FAIL, f"jar revision {built}, HEAD {head}")


# `ant clean` deletes this, and a jar without it launches and then dies with NoClassDefFoundError
# (2026-08-18). `jar` depends on build-metal-host in build.xml, so this is a backstop.
def check_dylib(t):
    if os.path.isfile(os.path.join(t.src, "lib", "natives-macos", "libjhvmetalhost.dylib")):
        return PASS, ""
    return FAIL, "missing; ant build-metal-host"


def check_guide(t):
    pdf = t.path("HFStudio-Guide.pdf")
    if not os.path.isfile(pdf):
        return FAIL, "no HFStudio-Guide.pdf"
    newer = [p for p in (t.path("guide_content.json"), os.path.join(t.src, "VERSION")) if _newer(p, pdf)]
    for root, _dirs, files in os.walk(t.path("guide_assets")):
        newer += [os.path.join(root, f) for f in files if _newer(os.path.join(root, f), pdf)]
    if newer:
        return FAIL, "older than " + ", ".join(sorted(os.path.basename(p) for p in newer))
    return PASS, ""


# Verified against the receipt notarize writes, not by re-running `stapler validate`, which talks to
# Apple's CloudKit and is wildly non-deterministic (0.3 s cached, 30 s warm, 60 s then exit 68 cold,
# measured 2026-08-23). The receipt is written only after the staple and the validate both pass, and
# pins the dmg by content hash. spctl is local, fast, and the verdict users get.
def notarized(t, dmg_name, receipt_name):
    dmg = t.path(dmg_name)
    if not os.path.isfile(dmg):
        return FAIL, f"no {dmg_name}"
    if _newer(os.path.join(t.src, "HFStudio.jar"), dmg):
        return FAIL, f"HFStudio.jar is newer than {dmg_name}"
    try:
        with open(t.path(receipt_name)) as f:
            want = json.load(f).get("dmg_sha256")
    except (OSError, ValueError):
        return FAIL, f"no readable {receipt_name}"
    if sha256_of(dmg) != want:
        return FAIL, f"{dmg_name} is not the file in {receipt_name}"
    code = _run(["spctl", "-a", "-t", "open", "--context", "context:primary-signature", dmg])[0]
    if code is None:
        return UNCHECKED, "spctl is not available (not a Mac)"
    return (PASS, "receipt and spctl agree") if code == 0 else (FAIL, "spctl rejects it")


def check_dmg(t):
    return notarized(t, f"HFStudio-{t.version}.dmg", ".notarize-run.json")


def check_dmg_intel(t):
    name = f"HFStudio-{t.version}-intel.dmg"
    if not os.path.isfile(t.path(name)) and not os.path.isdir(t.path(".jdk-x64")):
        return UNCHECKED, "no Intel JDK at release/.jdk-x64, so no Intel dmg is built on this machine (optional)"
    return notarized(t, name, ".notarize-run-intel.json")


def check_zip(t):
    z = t.path(f"HFStudio-{t.version}.zip")
    if not os.path.isfile(z):
        return FAIL, "no zip"
    if _newer(os.path.join(t.src, "HFStudio.jar"), z):
        return FAIL, "HFStudio.jar is newer than the zip"
    return PASS, ""


# `deploy_release.sh publish` uploads whatever is on disk; on 2026-07-14 it left a month-old dmg on
# the release while reporting success. An asset counts as published when the release's copy is at
# least as new as the local file (120 s slack for the upload finishing after the mtime).
def asset_current(t, name, assets):
    local = t.path(name)
    for a in assets:
        if a.get("name") == name:
            try:
                remote = datetime.datetime.fromisoformat(a.get("updatedAt", "").replace("Z", "+00:00")).timestamp()
            except ValueError:
                return FAIL, f"{name}: unreadable updatedAt"
            if remote + 120 >= os.path.getmtime(local):
                return PASS, ""
            return FAIL, f"{name} on the release is older than the local file"
    return FAIL, f"{name} is not on {t.tag}"


def check_published(t):
    names = (f"HFStudio-{t.version}.dmg", f"HFStudio-{t.version}.zip", "HFStudio-Guide.pdf")
    for name in names:
        if not os.path.exists(t.path(name)):
            return FAIL, f"no local {name}"
    state, data = release_view(t)
    if state == "unreachable":
        return UNCHECKED, "gh release view did not answer"
    if state == "missing":
        return FAIL, f"no release {t.tag}"
    for name in names:
        result, detail = asset_current(t, name, data.get("assets") or [])
        if result != PASS:
            return result, detail
    return PASS, "dmg, zip and guide are this build's"


# The download page names no release: every release so far is a pre-release, which GitHub's
# releases/latest skips, so the page asks for the newest one when it loads. Check both halves: the
# page still asks this repository, and the newest release carries this build's dmg. Checking only for
# a 200 passed for weeks while /jhv pointed at a retired tag (2026-08-23).
def shortlink_serves(t, dmg_name):
    state, page = http_get(PAGE_URL)
    if state == "unreachable":
        return UNCHECKED, "gilly.space did not answer"
    if state == "missing" or f"api.github.com/repos/{t.repo}/releases" not in page:
        return FAIL, "the download page no longer asks this repository for its releases"
    state, body = http_get(f"https://api.github.com/repos/{t.repo}/releases?per_page=1")
    if state == "unreachable":
        return UNCHECKED, "api.github.com did not answer"
    try:
        newest = json.loads(body or "[]")[0]
    except (ValueError, IndexError):
        return FAIL, "no releases"
    if any(a.get("name") == dmg_name for a in newest.get("assets", [])):
        return PASS, f"the newest release, {newest.get('tag_name')}, carries {dmg_name}"
    return FAIL, f"the newest release, {newest.get('tag_name')}, does not carry {dmg_name}"


# ANDed with `published` on purpose: "the release exists and the page answers" is green before the
# release even starts, because the previous release is always there.
def check_live(t):
    result, detail = check_published(t)
    if result != PASS:
        return result, detail
    data = release_view(t)[1]
    if data.get("isPrerelease") != t.version.startswith("0."):
        return FAIL, "the pre-release flag does not match the version"
    count = len(data.get("assets") or [])
    if count < _MIN_LIVE_ASSETS:
        return FAIL, f"{count} assets on {t.tag}, want at least {_MIN_LIVE_ASSETS}"
    return shortlink_serves(t, f"HFStudio-{t.version}.dmg")


def check_ci_package(t):
    # For a release event, a run's headBranch is the tag (not verified on a real run yet; RELEASING.md step 7).
    state, runs = gh_json(["run", "list", "--repo", t.repo, "--workflow", "package.yml", "--event", "release",
                           "--limit", "20", "--json", "headBranch,status,conclusion,url"])
    if state != "ok":
        return UNCHECKED, "gh run list did not answer"
    runs = [r for r in runs or [] if r.get("headBranch") == t.tag]
    if not runs:
        return FAIL, f"no package.yml release run for {t.tag}"
    run = runs[0]
    if run.get("status") != "completed":
        return FAIL, f"package.yml for {t.tag} is {run.get('status')}"
    if run.get("conclusion") != "success":
        return FAIL, f"package.yml for {t.tag} ended {run.get('conclusion')}: {run.get('url', '')}"
    return PASS, run.get("url", "")


def check_assets_all(t):
    want = read_assets(t.version)
    state, data = release_view(t)
    if state == "unreachable":
        return UNCHECKED, "gh release view did not answer"
    if state == "missing":
        return FAIL, f"no release {t.tag}"
    have = {a.get("name") for a in data.get("assets") or []}
    missing = [n for n in want if n not in have]
    summary = f"{len(want) - len(missing)} of {len(want)} assets in release/assets.txt are on {t.tag}"
    if any(not n.endswith("-intel.dmg") for n in missing):
        return FAIL, summary + "; missing " + ", ".join(missing)
    return PASS, summary + ("; the optional Intel dmg is absent" if missing else "")


def check_page_fallback(t):
    state, page = http_get(PAGE_URL)
    if state == "unreachable":
        return UNCHECKED, "gilly.space did not answer"
    if state == "missing":
        return FAIL, "the download page is gone"
    tags = sorted(set(re.findall(r"/releases/download/([^/\"']+)/", page)))
    if not tags:
        return FAIL, "the page has no fallback download links"
    if tags == [t.tag]:
        return PASS, f"fallback links name {t.tag}"
    return FAIL, "fallback links name " + ", ".join(tags) + f", not {t.tag}"


TARGET = Target(TAG, VERSION, SRC, DEPLOY, REPO)

# (key, label, check). The keys, their order and the meaning of `done` are what the Orrery reads:
# new rows are appended, existing ones are never renamed, removed or reordered.
MILESTONES = [
    ("pushed", "Source committed and pushed to origin/master", check_pushed),
    ("jar", "Jar built from this commit (manifest revision == HEAD count)", check_jar),
    ("dylib", "Metal host dylib present (ant build-metal-host ran)", check_dylib),
    ("guide", "Guide regenerated since its content last changed", check_guide),
    ("dmg", "Notarized dmg built from the current jar, stapled and accepted", check_dmg),
    ("zip", "Cross-platform zip repackaged from the current jar", check_zip),
    # Session-only: launching the mounted app and looking at it is a judgement, not a queryable
    # fact. Supplied via --done smoketest after it is actually done.
    ("smoketest", "Smoke-tested the app inside the dmg (not the bare jar)", None),
    ("published", "Release assets replaced with THIS build  (dmg + zip + guide)", check_published),
    ("live", "Public release serving THIS build, and the short link reaches it", check_live),
    ("dmg_intel", "Intel dmg notarized from the current jar (optional)", check_dmg_intel),
    ("ci_package", "package.yml finished on the release (Windows and Linux attached)", check_ci_package),
    ("assets_all", "Every asset in release/assets.txt is on the release", check_assets_all),
    ("page_fallback", "Download page fallback links name this release", check_page_fallback),
]

# HOW to advance each step. Taken from RELEASING.md rather than paraphrased: a
# command that only looks right is worse than none, and this renders on the
# Orrery's runbooks page where it will be copied.
#
# KIND says who can act:
#   shell  needs a real Claude Code session; the Orrery has no shell by design
#   human  a judgement or a physical action, not a command
#   gate   shown so you can SEE what would happen; deliberately no run affordance
HOW = {
    "pushed": ("shell",
        f"cd {SRC}\n"
        "git branch --show-current                 # must be master\n"
        "git status --short src resources VERSION  # must be empty: the jar is built from the\n"
        "                                          # WORKING TREE, not from HEAD\n"
        "git push origin master"),

    "jar": ("shell",
        f"cd {SRC}\n"
        "ant clean jar build-metal-host      # see the dylib step\n"
        "unzip -p HFStudio.jar META-INF/MANIFEST.MF | grep -i revision\n"
        "git rev-list --count HEAD           # must match"),

    "dylib": ("shell",
        f"cd {SRC} && ant build-metal-host\n"
        "# compiles native/macos/jhv_metal_host.m -> lib/natives-macos/libjhvmetalhost.dylib\n"
        "# `ant clean` deletes it; without it the app launches then dies with\n"
        "# NoClassDefFoundError: MacAngleBridge"),

    "guide": ("shell",
        f"cd {DEPLOY} && python3 build_guide.py\n"
        "# recapture guide_assets/*.png by hand FIRST if a feature changed how it looks,\n"
        "# or the guide documents a version that no longer exists\n"
        "# build_guide.py supports **bold** and `mono` only; *italic* renders literally"),

    "dmg": ("shell",
        f"cd {DEPLOY}\n"
        "JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \\\n"
        "  ./deploy_release.sh notarize\n"
        "# Temurin, NOT Homebrew openjdk@25: the Homebrew prefix has no lib/modules and\n"
        "# is not a valid jpackage --runtime-image root (2026-07-22)\n"
        "# does its own `ant clean jar build-metal-host` first, so the dmg is always fresh\n"
        "# takes minutes; talks to Apple twice and both steps fail transiently"),

    "zip": ("shell",
        f"cd {DEPLOY} && ./deploy_release.sh package\n"
        "# regenerates the guide and repackages the zip from the CURRENT jar. No network."),

    "smoketest": ("human",
        "Mount the dmg and launch the app inside it, not the jar you just built.\n"
        "Quit any running HelioFITS Studio first: a second instance cannot take the JPIP\n"
        "ehcache lock, and the failure is not contained (levelCache stays null, every\n"
        "image read throws, and it presents as a rendering bug).\n"
        "Run it from the mounted image, not /Applications: the dev launcher is\n"
        "HelioFITS Studio (dev).app in ~/Applications (extra/make-dev-launcher.sh).\n"
        "Then pass --done smoketest."),

    "published": ("gate",
        "PUBLIC. Ask Gilly in chat, this release, every time: a yes for one never\n"
        "carries to the next. Name the new tag AND the commit, e.g. 'this publishes a\n"
        "new release <tag> from commit <sha>, which becomes what gilly.space/heliofits-studio\n"
        "offers first'. Say which release stays behind it as the way back.\n"
        "The link has been sent to Sarah Gibson, Ian Hewins, Yara De Leo, Curt de Koning.\n"
        "Releases are immutable: the tag comes from VERSION, so bump it for a new one;\n"
        "publish refuses a tag that already has a release.\n"
        f"cd {DEPLOY} && ./deploy_release.sh publish    # creates {TAG}"),

    "live": ("shell",
        f"gh release view {TAG} --repo {REPO} \\\n"
        "  --json assets --jq '.assets[] | \"\\(.name)  \\(.size)  \\(.updatedAt)\"'\n"
        "# every updatedAt should be from this run; an older one was NOT replaced,\n"
        "# which is exactly the 2026-07-14 stale-dmg failure"),

    "dmg_intel": ("shell",
        f"cd {DEPLOY} && MAC_ARCH=x64 ./deploy_release.sh notarize\n"
        "# needs Temurin 25 for mac/x64 unpacked at release/.jdk-x64 (RELEASING.md step 4)"),

    "ci_package": ("shell",
        f"gh run list --repo {REPO} --workflow package.yml --event release --limit 3\n"
        "# runs on `release: published`; attaches the Windows and Linux packages about ten\n"
        "# minutes later, only if each started, drew an image and decoded a JPEG 2000 file"),

    "assets_all": ("shell",
        f"gh release view {TAG} --repo {REPO} --json assets --jq '.assets[].name'\n"
        "# every name in release/assets.txt; the Intel dmg is optional"),

    "page_fallback": ("human",
        "The fallback links in heliofits-studio/index.html (site repo GillySpace27.github.io)\n"
        "name an older release. Editing and pushing that page is its own change, with Gilly's yes."),
}

GATED = {"published"}

FOOTER_CMD = (
    f"gh release view {TAG} --repo {REPO} --json assets --jq "
    f"'[.assets[] | select(.name|endswith(\".dmg\"))][0] | \"dmg on release: \\(.updatedAt)\"' "
    f"2>/dev/null"
)
FOOTER_LABEL = "live"

# ───────────────────────── END CONFIG ─────────────────────────

BAR_WIDTH = 20


def run_out(cmd):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True,
                           text=True, timeout=120)
        return r.stdout.strip()
    except Exception:
        return ""


def evaluate(done_keys, target=None, milestones=None):
    """key -> (result, detail). A session-only milestone is PASS when given with --done, else
    UNCHECKED. A check that raises reads as UNCHECKED, never as done, and --selftest flags it."""
    t = target or TARGET
    out = {}
    for key, _label, check in (milestones or MILESTONES):
        if check is None:
            out[key] = (PASS, "--done") if key in done_keys else (UNCHECKED, f"session-only: --done {key}")
            continue
        try:
            out[key] = check(t)
        except Exception as e:
            out[key] = (UNCHECKED, f"check raised {type(e).__name__}: {e}")
    return out


SELFTEST_TAGS = ("v0.0.0-hfs-selftest-nonexistent", "v5.6a-coronal-research")


def selftest(milestones=None):
    """Grade every check against a tag that never existed and a retired one, with empty source and
    release folders. Returns 1 if any check passes there (it cannot fail) or raises (it is broken):
    RELEASING.md, 2026-08-24, negative-test every check."""
    bad = 0
    with tempfile.TemporaryDirectory(prefix="hfs-selftest-") as empty:
        for tag in SELFTEST_TAGS:
            t = Target(tag, tag[1:], empty, empty, REPO)
            for key, (result, detail) in evaluate(set(), t, milestones).items():
                broken = detail.startswith("check raised")
                flag = "  <- cannot fail" if result == PASS else "  <- broken" if broken else ""
                bad += bool(flag)
                print(f"selftest {tag} {key}: {result} ({detail}){flag}")
    print(f"selftest: {'FAIL' if bad else 'ok'}, {bad} check(s) passed or broke on a bogus tag")
    return 1 if bad else 0


def is_done(state, key):
    return state.get(key, (FAIL, ""))[0] == PASS


def render(state):
    total = len(MILESTONES)
    done = sum(1 for k, _, _ in MILESTONES if is_done(state, k))
    filled = round(BAR_WIDTH * done / total) if total else 0
    bar = "█" * filled + "░" * (BAR_WIDTH - filled)

    lines = [TITLE]
    if SUBTITLE_CMD:
        sub = run_out(SUBTITLE_CMD)
        if sub:
            lines.append(sub)
    lines.append(f"[{bar}] {done}/{total}")
    lines.append("")

    pointed = False
    for key, label, _ in MILESTONES:
        suffix = "  (gated: needs your go-ahead)" if key in GATED else ""
        result, detail = state.get(key, (FAIL, ""))
        if result != PASS:
            suffix += f"  [{result}{': ' + detail if detail else ''}]"
        if result == PASS:
            mark = "✅"
        elif not pointed:
            mark = "▶"
            pointed = True
        else:
            mark = "⬜"
        lines.append(f"{mark} {label}{suffix}")
        # Only the NEXT step shows its how-to: all of them at once turns a
        # status read into a wall of commands.
        if mark == "▶" and key in HOW:
            kind, how = HOW[key]
            for i, ln in enumerate(how.split("\n")):
                lines.append(f"      {'[' + kind + '] ' if i == 0 else '      '}{ln}")

    if FOOTER_CMD:
        raw = run_out(FOOTER_CMD)
        if raw:
            lines.append("")
            lines.append(f"({FOOTER_LABEL}: {raw})")

    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--done", default="",
                   help="comma-separated keys for session-only milestones")
    p.add_argument("--json", action="store_true")
    p.add_argument("--selftest", action="store_true",
                   help="grade every check against a nonexistent and a retired tag; exit 1 if any passes")
    p.add_argument("--emit", action="store_true",
                   help="also write a snapshot to ~/.claude/runbooks/state/ for the "
                        "Orrery dashboard. The snapshot is a CACHE, never truth: it "
                        "records checked_at so the dashboard can show its age and grey "
                        "it out when stale.")
    args = p.parse_args()
    if args.selftest:
        sys.exit(selftest())

    done_keys = {k.strip() for k in args.done.split(",") if k.strip()}
    known = {k for k, _, c in MILESTONES if c is None}
    unknown = done_keys - known
    if unknown:
        print(f"warning: --done keys not declared session-only: "
              f"{', '.join(sorted(unknown))}", file=sys.stderr)

    state = evaluate(done_keys)

    if args.json:
        print(json.dumps({
            "title": TITLE,
            "complete": sum(1 for k, _, _ in MILESTONES if is_done(state, k)),
            "total": len(MILESTONES),
            "milestones": [
                {"key": k, "label": lb, "done": is_done(state, k), "gated": k in GATED,
                 "result": state[k][0], "detail": state[k][1],
                 "how_kind": HOW.get(k, ("", ""))[0], "how": HOW.get(k, ("", ""))[1]}
                for k, lb, _ in MILESTONES
            ],
        }, indent=2))
    else:
        print(render(state))

    if args.emit:
        sha = run_out(f"cd {SRC} && git rev-parse --short HEAD")
        rev = run_out(f"cd {SRC} && git rev-list --count HEAD")
        ident = f"{sha} r{rev}" if sha else "no candidate"
        snap = {
            "name": "ship-hfstudio",
            "title": f"HelioFITS Studio {TAG}, candidate {ident}",
            "checked_at": datetime.datetime.now(datetime.timezone.utc)
                            .isoformat(timespec="seconds"),
            "complete": sum(1 for k, _, _ in MILESTONES if is_done(state, k)),
            "total": len(MILESTONES),
            "next": next((lb for k, lb, _ in MILESTONES if not is_done(state, k)), None),
            "external_state": run_out(FOOTER_CMD) if FOOTER_CMD else None,
            "external_label": FOOTER_LABEL if FOOTER_CMD else None,
            "milestones": [
                {"key": k, "label": lb, "done": is_done(state, k),
                 "gated": k in GATED,
                 "result": state[k][0], "detail": state[k][1],
                 "how_kind": HOW.get(k, ("", ""))[0],
                 "how": HOW.get(k, ("", ""))[1]}
                for k, lb, _ in MILESTONES
            ],
        }
        d = os.path.expanduser("~/.claude/runbooks/state")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "ship-hfstudio.json"), "w") as f:
            json.dump(snap, f, indent=2)
        print(f"\n(snapshot written to {d}/ship-hfstudio.json)")


if __name__ == "__main__":
    main()
