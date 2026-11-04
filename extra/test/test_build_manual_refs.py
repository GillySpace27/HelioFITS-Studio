#!/usr/bin/env python3
"""release/build_manual_refs.py: generated pages current, menu paths real (DOCS-2)."""
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "release"))
import build_manual_refs as bmr  # noqa: E402


class BuildManualRefsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = self.tmp.name
        for rel in ("docs/actions.md", "docs/commands.json", "resources/data/tips.json", "changelog.md"):
            os.makedirs(os.path.join(root, os.path.dirname(rel)), exist_ok=True)
            shutil.copy(os.path.join(REPO, rel), os.path.join(root, rel))
        shutil.copytree(os.path.join(REPO, "resources", "data", "tours"), os.path.join(root, "resources", "data", "tours"))
        os.makedirs(os.path.join(root, "manual"))
        self.page = os.path.join(root, "manual", "page.md")
        self._root, self._gen = bmr.ROOT, bmr.GEN
        bmr.ROOT, bmr.GEN = root, os.path.join(root, "manual", "generated")

    def tearDown(self):
        bmr.ROOT, bmr.GEN = self._root, self._gen
        self.tmp.cleanup()

    def write_page(self, text):
        with open(self.page, "w", encoding="utf-8") as f:
            f.write(text)

    def test_write_then_check_is_clean_and_a_source_change_makes_it_stale(self):
        self.assertEqual(bmr.main([]), 0)
        self.assertEqual(bmr.main(["--check"]), 0)
        tips = os.path.join(bmr.ROOT, "resources", "data", "tips.json")
        with open(tips, encoding="utf-8") as f:
            text = f.read()
        with open(tips, "w", encoding="utf-8") as f:
            f.write(text.replace("Your layers are drawn", "Layers are drawn", 1))
        self.assertEqual(bmr.main(["--check"]), 1)

    def test_menu_paths_must_be_real(self):
        self.write_page("Use {menuselection}`Layers --> New PUNCH Layer...` and {menuselection}`File --> Quit`.\n")
        self.assertEqual(bmr.menu_problems(), [])
        self.write_page("Use {menuselection}`File --> New PUNCH Layer...`.\n")
        problems = bmr.menu_problems()
        self.assertEqual(len(problems), 1)
        self.assertIn("not a menu item: File --> New PUNCH Layer...", problems[0])

    def test_history_drops_docs_pointers_and_tours_keep_their_steps(self):
        self.assertNotIn("[docs:", bmr.history_page())
        tours = bmr.tours_page()
        self.assertIn("## Getting started", tours)
        self.assertIn("1. **The image canvas.**", tours)

    def test_commands_lists_every_command_once(self):
        page = bmr.commands_page()
        for cid, _, _ in bmr.actions():
            self.assertEqual(page.count(f"`{cid}`"), 1, cid)


if __name__ == "__main__":
    unittest.main()
