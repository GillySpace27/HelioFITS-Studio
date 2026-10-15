"""deploy_release.sh's append_feed_record, run against the real writer (suite SU-11).

Needs a Website checkout with heliosoftware/feed/append_record.py and build_feed.py (set SITE, default
~/vscode/Website); skipped with a reason otherwise. No network, no gh, no signing, no publish.
Run: python3 extra/test/test_feed_record.py
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "release" / "deploy_release.sh"
WEBSITE = pathlib.Path(os.environ.get("SITE", os.path.expanduser("~/vscode/Website")))
FEED_SRC = WEBSITE / "heliosoftware" / "feed"
HAVE_WRITER = (FEED_SRC / "append_record.py").is_file() and (FEED_SRC / "build_feed.py").is_file()

# The shape of PLATFORMS on the Website's heliofits-studio/index.html; only the confirmed flags matter here.
PAGE = """<script>
const PLATFORMS = {
  mac:     { confirmed: %s,  asset: /^HFStudio-[\\d.]+\\.dmg$/, how: "a" },
  intel:   { confirmed: %s, asset: /^HFStudio-[\\d.]+-intel\\.dmg$/, how: "b" },
  windows: { confirmed: true,  asset: /-windows\\.(zip|msi|exe)$/i, how: "c" },
  linux:   { confirmed: false, asset: /-linux\\.(tar\\.gz|zip)$/i, how: "d" },
};
</script>
"""


def function_text():
    text = SCRIPT.read_text(encoding="utf-8")
    start = text.index('FEED_CONFIRMED="')          # the variable sits directly above the functions
    end = text.index("\n}\n", text.index("append_feed_record() {", start)) + 3
    return text[start:end]


@unittest.skipUnless(HAVE_WRITER, "no Website checkout with heliosoftware/feed/append_record.py (set SITE)")
class FeedRecord(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.t = pathlib.Path(tmp.name)
        self.site = self.t / "site"
        (self.site / "heliosoftware" / "feed").mkdir(parents=True)
        for n in ("append_record.py", "build_feed.py"):
            shutil.copy(FEED_SRC / n, self.site / "heliosoftware" / "feed" / n)
        (self.site / "heliosoftware" / "index.html").write_text(
            '<link rel="stylesheet" href="/assets/site.css?v=202610010000">\n', encoding="utf-8")
        self.write_page("true", "false")
        self.src = self.t / "src"
        self.src.mkdir()
        (self.src / "changelog.md").write_text(
            "# Changelog\n\n## 9.9.9 - 2026-10-02\n\n### Changed\n\n- First thing that changed.\n- Second.\n", encoding="utf-8")
        self.rel = self.t / "release"
        self.rel.mkdir()
        for name, data in (("HFStudio-9.9.9.dmg", b"dmg"), ("HFStudio-9.9.9.zip", b"zip"), ("HFStudio-Guide.pdf", b"pdf")):
            (self.rel / name).write_bytes(data)
        (self.t / "fn.sh").write_text(function_text(), encoding="utf-8")

    def write_page(self, mac, intel):
        page = self.site / "heliofits-studio" / "index.html"
        page.parent.mkdir(parents=True, exist_ok=True)
        page.write_text(PAGE % (mac, intel), encoding="utf-8")

    def run_fn(self, site=None, extra=""):
        script = f"""set -eu
. "{self.t}/fn.sh"
VERSION=9.9.9; TAG=v9.9.9; REPO=Test/Repo; PRERELEASE=--prerelease; SRC="{self.src}"
HERE="{self.rel}"; TOP=HFStudio-9.9.9; DMG_INTEL="{self.rel}/HFStudio-9.9.9-intel.dmg"
ZIP="{self.rel}/HFStudio-9.9.9.zip"; PDF="{self.rel}/HFStudio-Guide.pdf"
SITE="{site or self.site}"
{extra}
append_feed_record
echo "rc=$?"
"""
        return subprocess.run(["sh", "-c", script], capture_output=True, text=True)

    def records(self):
        return json.loads((self.site / "heliosoftware" / "feed" / "heliofits-studio.json").read_text(encoding="utf-8"))["records"]

    def confirmed(self):
        return {a["platform"]: a["confirmed"] for a in self.records()[0]["assets"]}

    def test_records_the_assets_that_exist_with_their_checksums(self):
        r = self.run_fn()
        self.assertIn("rc=0", r.stdout, r.stderr)
        rec = self.records()[0]
        self.assertEqual((rec["version"], rec["channel"], rec["tag"]), ("9.9.9", "github-prerelease", "v9.9.9"))
        self.assertEqual(rec["url"], "https://github.com/Test/Repo/releases/tag/v9.9.9")
        self.assertEqual(rec["notes"], "First thing that changed.")
        by = {a["platform"]: a for a in rec["assets"]}
        self.assertEqual(sorted(by), ["any-java25", "guide", "macos-arm64"])      # no Intel dmg was built
        self.assertEqual(by["macos-arm64"]["sha256"], hashlib.sha256(b"dmg").hexdigest())
        self.assertEqual(by["macos-arm64"]["url"], "https://github.com/Test/Repo/releases/download/v9.9.9/HFStudio-9.9.9.dmg")
        self.assertEqual((by["macos-arm64"]["confirmed"], by["guide"]["confirmed"], by["any-java25"]["confirmed"]),
                         (True, True, False))
        self.assertTrue((self.site / "heliosoftware" / "feed.xml").exists())

    def test_confirmed_follows_the_page_flags(self):
        (self.rel / "HFStudio-9.9.9-intel.dmg").write_bytes(b"intel")
        self.write_page("false", "true")
        r = self.run_fn()
        self.assertIn("rc=0", r.stdout, r.stderr)
        self.assertEqual(self.confirmed(),
                         {"macos-arm64": False, "macos-x64": True, "any-java25": False, "guide": True})

    def test_unreadable_page_confirms_only_the_guide(self):
        (self.site / "heliofits-studio" / "index.html").write_text("<p>no table here</p>\n", encoding="utf-8")
        r = self.run_fn()
        self.assertIn("rc=0", r.stdout, r.stderr)
        self.assertIn("could not read the PLATFORMS confirmed flags", r.stderr)
        self.assertEqual(self.confirmed(), {"macos-arm64": False, "any-java25": False, "guide": True})

    def test_a_failing_writer_never_fails_the_release(self):
        self.run_fn()                                              # first run appends
        r = self.run_fn()                                          # second is a duplicate: exit 3 inside
        self.assertIn("rc=0", r.stdout)
        self.assertIn("feed record NOT written", r.stderr)
        self.assertEqual(len(self.records()), 1)

    def test_missing_writer_is_loud_and_harmless(self):
        r = self.run_fn(site=self.t / "nowhere")
        self.assertIn("rc=0", r.stdout)
        self.assertIn("feed record NOT written", r.stderr)


if __name__ == "__main__":
    unittest.main()
