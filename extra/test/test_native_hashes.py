"""Signing a jar's natives keeps LWJGL's recorded hashes true (no "Incompatible Java and native
library versions" line at launch), and release/check_native_hashes.py fails when they are not."""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / "release" / "check_native_hashes.py"
JAR = ROOT / "lib" / "lwjgl" / "lwjgl-3.4.3-natives-macos-arm64.jar"
ENTRY = "macos/arm64/org/lwjgl/liblwjgl.dylib"


def check(path):
    return subprocess.run([sys.executable, str(CHECK), str(path)], capture_output=True, text=True)


def rewrite(jar, entry, data):
    """A copy of jar with entry's bytes replaced, its META-INF .sha1 left alone (what signing did)."""
    with zipfile.ZipFile(jar) as src, zipfile.ZipFile(str(jar) + ".new", "w") as dst:
        for i in src.infolist():
            dst.writestr(i, data if i.filename == entry else src.read(i.filename))
    os.replace(str(jar) + ".new", jar)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.jar = self.dir / JAR.name
        shutil.copy(JAR, self.jar)

    def test_the_shipped_jar_passes(self):
        self.assertEqual(check(self.dir).returncode, 0)

    def test_a_changed_library_fails(self):
        with zipfile.ZipFile(self.jar) as z:
            data = z.read(ENTRY)
        rewrite(self.jar, ENTRY, data + b"signature")
        r = check(self.dir)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn(ENTRY, r.stdout)

    def test_no_recorded_hash_fails(self):
        self.jar.unlink()
        self.assertEqual(check(self.dir).returncode, 1)


@unittest.skipUnless(all(shutil.which(t) for t in ("zip", "unzip", "file", "shasum")),
                     "SKIP: needs zip, unzip, file and shasum")
class SignJarNativesTest(unittest.TestCase):
    """sign_jar_natives from deploy_release.sh, run with a codesign that appends bytes like a signature."""

    def test_signing_restamps_the_hash(self):
        text = (ROOT / "release" / "deploy_release.sh").read_text()
        fn = re.search(r"^sign_jar_natives\(\) \{\n.*?^\}\n", text, re.S | re.M).group(0)
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        (d / "app").mkdir()
        shutil.copy(JAR, d / "app" / JAR.name)
        script = ("retry_codesign() { printf 'signature' >> \"$1\"; }\n" + fn +
                  'sign_jar_natives "$1"\n')
        r = subprocess.run(["sh", "-c", script, "sh", str(d / "app")], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        with zipfile.ZipFile(d / "app" / JAR.name) as z:
            self.assertTrue(z.read(ENTRY).endswith(b"signature"), "the stub signer did not run")
        r = check(d / "app")
        self.assertEqual(r.returncode, 0, r.stdout)


if __name__ == "__main__":
    unittest.main()
