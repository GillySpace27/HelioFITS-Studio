import plistlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "release"))
import add_document_types  # noqa: E402


class DocumentTypesTest(unittest.TestCase):
    def test_adds_scene_and_fits_types(self):
        info = add_document_types.add_document_types({"CFBundleName": "x"})
        types = {t["CFBundleTypeName"]: t for t in info["CFBundleDocumentTypes"]}
        self.assertEqual(types["HelioFITS Studio Scene"]["CFBundleTypeExtensions"], ["jhv"])
        self.assertEqual(types["HelioFITS Studio Scene"]["CFBundleTypeRole"], "Editor")
        self.assertEqual(types["FITS Image"]["LSHandlerRank"], "Alternate")

    def test_idempotent_and_keeps_existing(self):
        info = {"CFBundleDocumentTypes": [{"CFBundleTypeName": "Other"}]}
        once = add_document_types.add_document_types(info)
        self.assertEqual(len(once["CFBundleDocumentTypes"]), 3)
        twice = add_document_types.add_document_types(once)
        self.assertEqual(len(twice["CFBundleDocumentTypes"]), 3)

    def test_script_edits_a_plist_file(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "Info.plist"
            p.write_bytes(plistlib.dumps({"CFBundleName": "x"}))
            subprocess.run([sys.executable, str(ROOT / "release" / "add_document_types.py"), str(p)], check=True)
            self.assertEqual(len(plistlib.loads(p.read_bytes())["CFBundleDocumentTypes"]), 2)


if __name__ == "__main__":
    unittest.main()
