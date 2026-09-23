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

Usage:
    python3 status.py [--done smoketest] [--json] [--emit]
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys

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

# Compare the jar's recorded revision against HEAD's commit count. Both sides
# are computed the same way `build.xml` computes it, so a mismatch means the
# jar was built from a different tree than the commit now checked out.
_JAR_FRESH = f"""
cd {SRC} || exit 1
test -f HFStudio.jar || exit 1
built=$(unzip -p HFStudio.jar META-INF/MANIFEST.MF 2>/dev/null \
        | tr -d '\\r' | awk -F': ' '/^revision:/ {{print $2}}')
head=$(git rev-list --count HEAD 2>/dev/null)
test -n "$built" && test -n "$head" && test "$built" = "$head"
"""

# An asset counts as published when the live release's copy is at least as new
# as the local file. Anything older means publish did not replace it.
def _asset_current(local_path, asset_name):
    return f"""
python3 - <<'EOF'
import json, os, subprocess, sys
local = "{local_path}"
if not os.path.exists(local):
    sys.exit(1)
try:
    out = subprocess.run(
        ["gh", "release", "view", "{TAG}", "--repo", "{REPO}", "--json", "assets"],
        capture_output=True, text=True, timeout=45)
    if out.returncode != 0:
        sys.exit(1)
    assets = json.loads(out.stdout).get("assets") or []
except Exception:
    sys.exit(1)
import datetime
for a in assets:
    if a.get("name") == "{asset_name}":
        up = a.get("updatedAt", "").replace("Z", "+00:00")
        try:
            remote = datetime.datetime.fromisoformat(up).timestamp()
        except Exception:
            sys.exit(1)
        # 120s slack: the upload finishes slightly after the local file's mtime.
        sys.exit(0 if remote + 120 >= os.path.getmtime(local) else 1)
sys.exit(1)
EOF
"""


# gilly.space/heliofits-studio is a download page, not a redirect (/hfs, /punchstudio and /jhv forward to
# it), and it names no release: every release so far is a pre-release, which GitHub's
# releases/latest skips, so the page asks for the newest one when it loads. Check both
# halves of that: the page is up and still asks this repository, and the newest release
# carries this build's dmg. This is the check that would have caught the dead-tag redirect.
_SHORTLINK_SERVES_DMG = r"""
python3 - <<'EOF'
import json, subprocess, sys
def get(u):
    r = subprocess.run(["curl", "-fsSL", "--max-time", "25", u],
                       capture_output=True, text=True, timeout=40)
    return r.stdout if r.returncode == 0 else ""
if "api.github.com/repos/@REPO@/releases" not in get("https://gilly.space/heliofits-studio/"):
    sys.exit(1)
try:
    newest = json.loads(get("https://api.github.com/repos/@REPO@/releases?per_page=1"))[0]
except Exception:
    sys.exit(1)
sys.exit(0 if any(a["name"] == "@DMG_NAME@" for a in newest["assets"]) else 1)
EOF
""".strip().replace("@DMG_NAME@", DMG_NAME).replace("@REPO@", REPO)

# The dmg on disk is the one a notarize run stapled and validated, and is not
# older than the jar it should contain.
# The heredoc goes LAST and its exit status is the script's. Appending `&& ...`
# after the EOF terminator instead makes the heredoc swallow it as Python source,
# which fails with a SyntaxError that reads exactly like a missing dmg.
_DMG_NOTARIZED = f"""
test -f {DMG} || exit 1
test ! {JAR} -nt {DMG} || exit 1
spctl -a -t open --context context:primary-signature {DMG} >/dev/null 2>&1 || exit 1
python3 - <<'EOF'
import hashlib, json, sys
try:
    rec = json.load(open("{DEPLOY}/.notarize-run.json"))
except Exception:
    sys.exit(1)
h = hashlib.sha256()
with open("{DMG}", "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
sys.exit(0 if h.hexdigest() == rec.get("dmg_sha256") else 1)
EOF
""".strip()

# All three shipped artifacts must be at least as new as their local source of
# truth. Used twice: as its own milestone, and ANDed into `live` so the final
# tick cannot go green on a release that was never refreshed.
# Each leg ends in a heredoc, and its terminator ("EOF") must be alone on its
# own line or bash never recognizes it and the whole thing free-falls into a
# SyntaxError that looks exactly like a failed check (the same bug already
# fixed once in _DMG_NOTARIZED). `.strip()` eats the newline after EOF, so the
# joins below put it back before appending ") && (".
_PUBLISHED = (
    "(" + _asset_current(DMG, DMG_NAME).strip() + "\n) && ("
        + _asset_current(ZIP, ZIP_NAME).strip() + "\n) && ("
        + _asset_current(PDF, PDF_NAME).strip() + "\n)"
)

# (key, label, check)
MILESTONES = [
    ("pushed", "Source committed and pushed to origin/master",
     f"cd {SRC} && test -z \"$(git status --porcelain src resources VERSION)\" "
     f"&& test \"$(git rev-parse HEAD)\" = \"$(git ls-remote origin refs/heads/master 2>/dev/null | awk '{{print $1}}')\""),

    ("jar", "Jar built from this commit (manifest revision == HEAD count)",
     _JAR_FRESH.strip()),

    # `ant clean` deletes this, and a jar without it launches and then dies with
    # NoClassDefFoundError (2026-08-18). `jar` now depends on build-metal-host in
    # build.xml, so this is a backstop rather than the usual failure.
    ("dylib", "Metal host dylib present (ant build-metal-host ran)",
     f"test -f {DYLIB}"),

    ("guide", "Guide regenerated since its content last changed",
     f"test -f {PDF} && test ! {DEPLOY}/guide_content.json -nt {PDF} "
     f"&& test ! {SRC}/VERSION -nt {PDF} "
     f"&& test -z \"$(find {DEPLOY}/guide_assets -newer {PDF} -type f 2>/dev/null)\""),

    # Verified against the receipt notarize writes, not by re-running `stapler
    # validate`: that talks to Apple's CloudKit and is wildly non-deterministic
    # (0.3s cached, 30s warm, 60s-then-exit-68 cold, all measured 2026-08-23).
    # A check that intermittently calls a good dmg unnotarized gets ignored.
    # The receipt is only written after the staple AND the validate both pass
    # under `set -e`, and it pins the dmg by content hash, so this asserts THIS
    # file is that one. spctl is kept because it is local, fast and is the
    # user-facing verdict.
    ("dmg", "Notarized dmg built from the current jar, stapled and accepted",
     _DMG_NOTARIZED),

    ("zip", "Cross-platform zip repackaged from the current jar",
     f"test -f {ZIP} && test ! {JAR} -nt {ZIP}"),

    # Session-only: launching the mounted app and looking at it is a judgement,
    # not a queryable fact. Supplied via --done smoketest after it is actually done.
    ("smoketest", "Smoke-tested the app inside the dmg (not the bare jar)", None),

    ("published", "Release assets replaced with THIS build  (dmg + zip + guide)",
     _PUBLISHED),

    # Deliberately ANDed with the published check. On its own "the release
    # exists and gilly.space/heliofits-studio answers 200" is green before the release even
    # starts, because the previous release is always sitting there. That would
    # show a reassuring final tick for work not yet done, which is precisely
    # the failure this whole pattern exists to prevent.
    #
    # The short-link half FOLLOWS the redirect and asserts the destination
    # actually offers the dmg. Checking only that gilly.space/jhv returns 200
    # passed for weeks while it pointed at a retired tag: GitHub renders a page
    # for any tag that exists, so the link "worked" and served nothing to
    # download (found 2026-08-23). A check that cannot fail is worse than none.
    ("live", "Public release serving THIS build, and the short link reaches it",
     # _PUBLISHED already opens with "(" (it's its own "&&" chain of
     # subshells). Wrapping it in an outer "(...)" here made the string start
     # "((python3...", and bash parses a leading "((" as arithmetic-evaluation
     # syntax, not two nested subshells -- a real syntax error, not a check
     # failure, that read exactly like a missing published asset.
     f"{_PUBLISHED} "
     f"&& gh release view {TAG} --repo {REPO} --json isPrerelease,assets "
     f"--jq 'select((.isPrerelease == {'true' if TAG.startswith('v0.') else 'false'}) and (.assets|length)>=5)' | grep -q . "
     f"&& {_SHORTLINK_SERVES_DMG}"),
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
        "Run it from the mounted image. HelioFITS Studio.app sits beside the launcher tile\n"
        "HelioFITS Studio Dev.app in /Applications rather than replacing it.\n"
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


def run_ok(cmd):
    """True when the command exits 0. Never raises; a broken check reads as
    'not done', which is the safe direction to fail."""
    try:
        return subprocess.run(cmd, shell=True, capture_output=True,
                              timeout=120).returncode == 0
    except Exception:
        return False


def run_out(cmd):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True,
                           text=True, timeout=120)
        return r.stdout.strip()
    except Exception:
        return ""


def evaluate(done_keys):
    state = {}
    for key, _label, check in MILESTONES:
        state[key] = key in done_keys if check is None else run_ok(check)
    return state


def render(state):
    total = len(MILESTONES)
    done = sum(1 for k, _, _ in MILESTONES if state.get(k))
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
        if state.get(key):
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
    p.add_argument("--emit", action="store_true",
                   help="also write a snapshot to ~/.claude/runbooks/state/ for the "
                        "Orrery dashboard. The snapshot is a CACHE, never truth: it "
                        "records checked_at so the dashboard can show its age and grey "
                        "it out when stale.")
    args = p.parse_args()

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
            "complete": sum(1 for k, _, _ in MILESTONES if state.get(k)),
            "total": len(MILESTONES),
            "milestones": [
                {"key": k, "label": lb, "done": state.get(k), "gated": k in GATED,
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
            "complete": sum(1 for k, _, _ in MILESTONES if state.get(k)),
            "total": len(MILESTONES),
            "next": next((lb for k, lb, _ in MILESTONES if not state.get(k)), None),
            "external_state": run_out(FOOTER_CMD) if FOOTER_CMD else None,
            "external_label": FOOTER_LABEL if FOOTER_CMD else None,
            "milestones": [
                {"key": k, "label": lb, "done": bool(state.get(k)),
                 "gated": k in GATED,
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
