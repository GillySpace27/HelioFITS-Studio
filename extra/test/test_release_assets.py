#!/usr/bin/env python3
"""release/assets.txt is the one list of release asset names, and publish refuses what it cannot prove.

Run from anywhere:  python3 extra/test/test_release_assets.py

AssetListTest pins the list against the scripts that produce the files. AttachTest checks that publish
and package.yml attach exactly the listed names. PreflightTest runs `deploy_release.sh publish --dry-run`
in a throwaway repository with a bare local origin and fake gh and spctl first on PATH, so nothing here
can tag, push or upload anything real. RunbookTest keeps the runbook on the dry run and off deletion.
"""
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / "release"

# The contract as first written. Rows are append-only, so later rows may follow these eight.
INITIAL_ROWS = [
    ("HFStudio-{v}.dmg", "local"),
    ("HFStudio-{v}-intel.dmg", "local"),
    ("HFStudio-{v}.zip", "local"),
    ("HFStudio-{v}-windows.zip", "ci"),
    ("HFStudio-{v}-linux.tar.gz", "ci"),
    ("HFStudio-Guide.pdf", "local"),
    ("HFStudio-Guide.md", "local"),
    ("fabric_suvi.json.gz", "local"),
]


def rows():
    out = []
    for line in (RELEASE / "assets.txt").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            out.append(tuple(line.split()))
    return out


def version():
    return (ROOT / "VERSION").read_text().strip()


def load_status():
    spec = importlib.util.spec_from_file_location(
        "hfs_status", RELEASE / "skills" / "ship-hfstudio" / "scripts" / "status.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AssetListTest(unittest.TestCase):
    def test_rows_are_the_contract(self):
        found = rows()
        for row in found:
            self.assertEqual(len(row), 2, f"row {row} is not '<pattern> local|ci'")
            self.assertIn(row[1], ("local", "ci"), f"row {row}")
        self.assertEqual(found[:len(INITIAL_ROWS)], INITIAL_ROWS)

    def test_ci_rows_are_what_package_app_builds(self):
        text = (RELEASE / "package-app.sh").read_text()
        built = {m.replace("$VERSION", "{v}")
                 for m in re.findall(r'ARCHIVE="(HFStudio-\$VERSION-[^"]+)"', text)}
        self.assertEqual(built, {p for p, s in rows() if s == "ci"})

    def test_local_rows_are_what_deploy_release_names(self):
        text = (RELEASE / "deploy_release.sh").read_text()
        top = re.search(r'^TOP="([^"]+)"', text, re.M).group(1).replace("$VERSION", "{v}")
        made = set()
        for var in ("ZIP", "PDF", "MD", "CLOUD", "DMG"):
            for path in re.findall(r"\b" + var + r'="\$HERE/([^"]+)"', text):
                made.add(path.replace("$TOP", top))
        self.assertEqual(made, {p for p, s in rows() if s == "local"})

    def test_tracker_reads_the_list(self):
        status = load_status()
        v = status.VERSION
        self.assertEqual(status.read_assets(v), [p.replace("{v}", v) for p, _ in rows()])
        self.assertEqual(status.read_assets(v, "ci"),
                         [p.replace("{v}", v) for p, s in rows() if s == "ci"])
        local = status.read_assets(v, "local")
        for name in (status.DMG_NAME, status.ZIP_NAME, status.PDF_NAME):
            self.assertIn(name, local)


def workflow_step(text, name):
    """The body of the package.yml step called name, up to the next step."""
    m = re.search(r"- name: " + re.escape(name) + r"\n(.*?)(?=\n      - |\Z)", text, re.S)
    if not m:
        raise AssertionError(f"package.yml has no step named {name!r}")
    return m.group(1)


class AttachTest(unittest.TestCase):
    def test_publish_attaches_the_local_rows(self):
        out = subprocess.run(["sh", str(RELEASE / "deploy_release.sh"), "assets", "local"],
                             capture_output=True, text=True, check=True, timeout=60).stdout.split()
        self.assertEqual(out, [p.replace("{v}", version()) for p, s in rows() if s == "local"])
        text = (RELEASE / "deploy_release.sh").read_text()
        body = re.search(r"^publish\(\) \{\n(.*?)^\}", text, re.S | re.M).group(1)
        self.assertIn("asset_names local", body)
        self.assertNotIn('"$ZIP" "$PDF" "$MD"', body)

    def test_package_yml_attaches_the_ci_rows(self):
        text = (ROOT / ".github" / "workflows" / "package.yml").read_text()
        check = workflow_step(text, "Check the packages belong to this release")
        attach = workflow_step(text, "Attach to the release")
        for name, body in (("check", check), ("attach", attach)):
            self.assertNotRegex(body, r"-windows\.zip|-linux\.tar\.gz", f"the {name} step names an asset itself")
        self.assertIn("release/assets.txt", check)
        self.assertIn("ci-assets.txt", attach)


GIT = ["git", "-c", "user.name=check", "-c", "user.email=check@localhost", "-c", "commit.gpgsign=false",
       "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null"]


@unittest.skipIf(os.name == "nt", "SKIP: needs a POSIX sh and executable shims")
class PreflightTest(unittest.TestCase):
    """publish --dry-run in a throwaway repository: a bare origin beside it, fake gh and spctl on PATH."""

    V = "9.9.9"
    OLD_COMMIT = "1ce8edfead42eb8bb8d7349779a6a081321b50e3"   # the 0.8.3 receipt's build_sha

    def git(self, *args, cwd=None):
        return subprocess.run([*GIT, *args], cwd=cwd or self.repo, capture_output=True, text=True,
                              check=True, timeout=60).stdout.strip()

    def fake(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def write_jar(self, **attrs):
        manifest = "Manifest-Version: 1.0\r\n" + "".join(f"{k}: {v}\r\n" for k, v in attrs.items())
        with zipfile.ZipFile(self.repo / "HFStudio.jar", "w") as z:
            z.writestr("META-INF/MANIFEST.MF", manifest)

    def write_receipt(self, dmg, build_sha, name=".notarize-run.json"):
        (self.repo / "release" / name).write_text(json.dumps({
            "dmg_sha256": hashlib.sha256(dmg.read_bytes()).hexdigest(),
            "build_sha": build_sha, "build_revision": "1", "notarized_at": "2026-10-01T00:00:00Z"}))

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="hfs-preflight-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / "repo"
        (self.repo / "release").mkdir(parents=True)
        for name in ("deploy_release.sh", "assets.txt"):
            shutil.copy2(RELEASE / name, self.repo / "release" / name)
        (self.repo / "VERSION").write_text(self.V + "\n")
        (self.repo / "src").mkdir()
        (self.repo / "src" / "A.java").write_text("class A {}\n")
        (self.repo / "resources").mkdir()
        (self.repo / "resources" / "r.txt").write_text("r\n")
        (self.repo / "release" / "fabric_suvi.json.gz").write_bytes(b"cloud")
        (self.repo / ".gitignore").write_text("HFStudio.jar\nrelease/*.dmg\nrelease/.notarize-run*.json\n")
        self.git("init", "-q", "-b", "master")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("init", "-q", "--bare", str(self.tmp / "origin.git"), cwd=self.tmp)
        self.git("remote", "add", "origin", str(self.tmp / "origin.git"))
        self.git("push", "-q", "origin", "master")
        self.head = self.git("rev-parse", "HEAD")
        self.write_jar(revision=self.git("rev-list", "--count", "HEAD"))
        self.dmg = self.repo / "release" / f"HFStudio-{self.V}.dmg"
        self.dmg.write_bytes(b"the notarized dmg")
        self.write_receipt(self.dmg, self.head)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.gh_log = self.tmp / "gh.log"
        self.fake("gh", 'echo "$*" >> "$GH_LOG"\ncase "$1 $2" in\n'
                        '  "release view") exit "${FAKE_GH_VIEW_RC:-1}" ;;\n'
                        '  "release list") [ -z "${FAKE_GH_LIST_FAIL:-}" ] || exit 1; echo v9.9.8 ;;\n'
                        '  *) exit 3 ;;\nesac\n')
        self.fake("spctl", 'exit "${FAKE_SPCTL_RC:-0}"\n')
        if not shutil.which("shasum"):
            self.fake("shasum", '[ "$1" = -a ] && shift 2\nexec sha256sum "$@"\n')

    def publish(self, *args, **env):
        e = dict(os.environ, PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}", GH_LOG=str(self.gh_log),
                 HFS_FAKE_DOW="3", HFS_FAKE_HOUR="10")
        e.pop("HFS_ALLOW_FRIDAY", None)
        e.update(env)
        self.assertEqual(shutil.which("gh", path=e["PATH"]), str(self.bin / "gh"), "the fake gh is not first")
        tags = self.git("tag", "-l")
        remote = self.git("ls-remote", "--tags", "origin")
        r = subprocess.run(["sh", "release/deploy_release.sh", "publish", *args], cwd=self.repo, env=e,
                           capture_output=True, text=True, timeout=120)
        self.assertEqual(self.git("tag", "-l"), tags, "the run changed the local tags")
        self.assertEqual(self.git("ls-remote", "--tags", "origin"), remote, "the run changed origin's tags")
        log = self.gh_log.read_text() if self.gh_log.exists() else ""
        self.assertNotRegex(log, r"release (create|upload|edit|delete)", "the run called a writing gh command")
        return r

    def dry_run(self, **env):
        return self.publish("--dry-run", **env)

    def refused(self, r, text):
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn(text, r.stderr)

    def test_clean_tree_prints_the_gate(self):
        r = self.dry_run()
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = r.stdout.splitlines()
        self.assertEqual(lines[0], f"GATE: publish v{self.V} at {self.head[:12]} from master; way back: v9.9.8")
        self.assertIn(f"  {hashlib.sha256(self.dmg.read_bytes()).hexdigest()}  {self.dmg.name}", lines)
        self.assertIn(f"  (attached by package.yml)  HFStudio-{self.V}-linux.tar.gz", lines)
        self.assertEqual(lines[-1], "guide will be regenerated")

    def test_unknown_publish_option_refuses(self):
        self.refused(self.publish("--dryrun"), "unknown option '--dryrun'")

    def test_missing_dmg_refuses(self):
        self.dmg.rename(self.tmp / "aside.dmg")
        self.refused(self.dry_run(), f"no HFStudio-{self.V}.dmg in release/")

    def test_missing_receipt_refuses(self):
        (self.repo / "release" / ".notarize-run.json").rename(self.tmp / "aside.json")
        self.refused(self.dry_run(), "has no receipt .notarize-run.json")

    def test_dmg_that_is_not_the_notarized_one_refuses(self):
        self.dmg.write_bytes(b"a different dmg")
        self.refused(self.dry_run(), "is not the notarized one")

    def test_receipt_from_another_commit_refuses(self):
        # 2026-09-18: the receipt matches the file, but the file was notarized from an older commit.
        self.write_receipt(self.dmg, self.OLD_COMMIT)
        self.refused(self.dry_run(), f"was notarized from {self.OLD_COMMIT}, not HEAD")

    def test_intel_dmg_needs_its_own_receipt(self):
        (self.repo / "release" / f"HFStudio-{self.V}-intel.dmg").write_bytes(b"intel")
        self.refused(self.dry_run(), "has no receipt .notarize-run-intel.json")

    def test_spctl_rejection_refuses(self):
        self.refused(self.dry_run(FAKE_SPCTL_RC="3"), "spctl rejects")

    def test_uncommitted_source_refuses(self):
        (self.repo / "src" / "A.java").write_text("class A {} \n")
        self.refused(self.dry_run(), "uncommitted changes in src, resources or VERSION")

    def test_head_ahead_of_origin_refuses(self):
        self.git("commit", "-q", "--allow-empty", "-m", "ahead")
        self.refused(self.dry_run(), "is not origin/master")

    def test_other_branch_refuses(self):
        self.git("checkout", "-q", "-b", "feature")
        self.refused(self.dry_run(), "on branch 'feature'")

    def test_stale_jar_refuses(self):
        self.write_jar(revision="0")
        self.refused(self.dry_run(), "HFStudio.jar has revision '0'")

    def test_jar_commit_is_checked_once_stamped(self):
        count = self.git("rev-list", "--count", "HEAD")
        self.write_jar(revision=count, commit="0123456789ab", dirty="false")
        self.refused(self.dry_run(), "was built from commit 0123456789ab, not HEAD")
        self.write_jar(revision=count, commit=self.head[:12], dirty="true")
        self.refused(self.dry_run(), "was built from a dirty tree")
        self.write_jar(revision=count, commit=self.head[:12], dirty="false")
        self.assertEqual(self.dry_run().returncode, 0)

    def test_friday_afternoon_refuses_unless_allowed(self):
        self.refused(self.dry_run(HFS_FAKE_DOW="5", HFS_FAKE_HOUR="13"), "Friday afternoon")
        self.assertEqual(self.dry_run(HFS_FAKE_DOW="5", HFS_FAKE_HOUR="09").returncode, 0)
        self.assertEqual(self.dry_run(HFS_FAKE_DOW="5", HFS_FAKE_HOUR="13", HFS_ALLOW_FRIDAY="1").returncode, 0)

    def test_tag_at_head_is_reused_and_tag_elsewhere_refuses(self):
        self.git("tag", "-a", f"v{self.V}", "-m", "stopped after tagging")
        self.assertEqual(self.dry_run().returncode, 0)
        self.git("push", "-q", "origin", f"v{self.V}")
        self.assertEqual(self.dry_run().returncode, 0)
        self.git("commit", "-q", "--allow-empty", "-m", "next")
        self.git("push", "-q", "origin", "master")
        self.refused(self.dry_run(), f"tag v{self.V} already exists (local)")

    def test_existing_release_refuses(self):
        self.refused(self.dry_run(FAKE_GH_VIEW_RC="0"), f"release v{self.V} already exists")

    def test_unreadable_previous_release_refuses(self):
        self.refused(self.dry_run(FAKE_GH_LIST_FAIL="1"), "cannot read the previous release")


class RunbookTest(unittest.TestCase):
    def test_runbook_gates_on_the_dry_run(self):
        for rel in ("release/RELEASING.md", "release/skills/ship-hfstudio/SKILL.md"):
            self.assertIn("publish --dry-run", (ROOT / rel).read_text(), rel)

    def test_nothing_tells_the_operator_to_delete_a_release(self):
        for rel in ("release/RELEASING.md", "release/deploy_release.sh"):
            self.assertNotRegex((ROOT / rel).read_text(), r"delete (that|it)\s+(release\s+)?deliberately", rel)


SHIM_ENV_DROP = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")


def shim(folder, name, body):
    """Write an executable /bin/sh stand-in for a tool, first on PATH in the tests below."""
    path = Path(folder) / name
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(0o755)
    return path


def env_with(bin_dir, **extra):
    e = {k: v for k, v in os.environ.items() if k not in SHIM_ENV_DROP}
    e["PATH"] = f"{bin_dir}{os.pathsep}{e['PATH']}"
    e.update(extra)
    return e


def notarize_body():
    text = (RELEASE / "deploy_release.sh").read_text()
    return re.search(r"^notarize_mac\(\) \{\n(.*?)^\}", text, re.S | re.M).group(1)


class EntitlementsTest(unittest.TestCase):
    def test_tracked_plist_holds_the_three_keys(self):
        import plistlib
        with open(RELEASE / "entitlements.plist", "rb") as f:
            got = plistlib.load(f)
        self.assertEqual(got, {"com.apple.security.cs.allow-jit": True,
                               "com.apple.security.cs.allow-unsigned-executable-memory": True,
                               "com.apple.security.cs.disable-library-validation": True})

    def test_notarize_signs_with_the_tracked_file_and_verifies_first(self):
        body = notarize_body()
        self.assertFalse("<<'PLIST'" in body, "the entitlements are still a heredoc")
        self.assertIn('ENT="$HERE/entitlements.plist"', body)
        for line in body.splitlines():
            if line.strip().startswith("rm "):
                self.assertNotIn("$ENT", line, "notarize would remove the tracked entitlements")
        self.assertLess(body.index("update_ffmpeg.py --check"), body.index("notarize_preconditions"))
        self.assertLess(body.index("sync_licenses.py --check"), body.index("notarize_preconditions"))
        self.assertLess(body.index('verify_signatures.sh" "$APP"'), body.index("hdiutil create"))
        self.assertLess(body.index("--entitlements - --xml"), body.index("hdiutil create"))


@unittest.skipIf(os.name == "nt", "SKIP: needs a POSIX sh and executable shims")
class VerifySignaturesTest(unittest.TestCase):
    """verify_signatures.sh against a fake bundle, with codesign and file replaced by shims."""

    def setUp(self):
        if not shutil.which("jar"):
            self.skipTest("SKIP: no jar tool (JDK) on PATH")
        self.tmp = Path(tempfile.mkdtemp(prefix="hfs-verify-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        shim(self.bin, "file", '[ "$1" = -b ] && shift\n'
                               'head -c5 "$1" | grep -q MACHO && echo "Mach-O 64-bit dynamically linked shared library arm64" || echo data\n')
        shim(self.bin, "codesign",
             'f=""; for a in "$@"; do f="$a"; done\n'
             'if grep -q TEAMSIGNED "$f"; then printf "CodeDirectory v=20500 size=1 flags=0x10000(runtime) hashes=1\\nTeamIdentifier=UB45PPC2JS\\n" >&2\n'
             'elif grep -q ADHOC "$f"; then printf "CodeDirectory v=20400 size=1 flags=0x2(adhoc) hashes=1\\nTeamIdentifier=not set\\n" >&2\n'
             'else echo "$f: code object is not signed at all" >&2; exit 1; fi\n')
        self.app = self.tmp / "HelioFITS Studio.app"
        (self.app / "Contents" / "MacOS").mkdir(parents=True)
        (self.app / "Contents" / "app").mkdir(parents=True)
        (self.app / "Contents" / "Info.plist").write_text("not a binary")
        self.exe = self.app / "Contents" / "MacOS" / "HelioFITS Studio"
        self.exe.write_text("MACHO TEAMSIGNED")
        self.make_jar("MACHO TEAMSIGNED")

    def make_jar(self, dylib_text):
        with zipfile.ZipFile(self.app / "Contents" / "app" / "natives.jar", "w") as z:
            z.writestr("jhv/macos-arm64/libjhvmetalhost.dylib", dylib_text)
            z.writestr("README.txt", "not a binary")

    def verify(self, path=None):
        before = sorted((p.relative_to(self.app), p.read_bytes()) for p in self.app.rglob("*") if p.is_file())
        r = subprocess.run(["sh", str(RELEASE / "verify_signatures.sh"), str(path or self.app)],
                           capture_output=True, text=True, timeout=120, env=env_with(self.bin))
        after = sorted((p.relative_to(self.app), p.read_bytes()) for p in self.app.rglob("*") if p.is_file())
        self.assertEqual(before, after, "verify_signatures.sh changed the bundle")
        return r

    def test_signed_bundle_passes(self):
        r = self.verify()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("verify_signatures: 2 Mach-O checked, 0 failed", r.stdout)

    def test_unsigned_library_inside_a_jar_fails(self):
        self.make_jar("MACHO")
        r = self.verify()
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("natives.jar!/jhv/macos-arm64/libjhvmetalhost.dylib: team is not UB45PPC2JS", r.stdout)

    def test_ad_hoc_executable_fails(self):
        # What jpackage leaves when nothing signs the bundle (package-app.sh macos-x64).
        self.exe.write_text("MACHO ADHOC")
        r = self.verify()
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("(TeamIdentifier=not set); no hardened runtime", r.stdout)

    def test_folder_without_mach_o_fails(self):
        empty = self.tmp / "Empty.app"
        empty.mkdir()
        r = subprocess.run(["sh", str(RELEASE / "verify_signatures.sh"), str(empty)],
                           capture_output=True, text=True, timeout=60, env=env_with(self.bin))
        self.assertEqual(r.returncode, 1)
        self.assertIn("no Mach-O found", r.stderr)


if __name__ == "__main__":
    unittest.main()
