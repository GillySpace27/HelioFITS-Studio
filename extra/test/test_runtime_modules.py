#!/usr/bin/env python3
"""Every JDK module the source imports must be in the trimmed runtime (release/make-runtime.sh).

The dev build runs on the full JDK, so a missing module shows up only in a packaged build, as a
NoClassDefFoundError (0.8.6: java.net.http, FeedbackReport). This maps imported packages to their
module and fails on any module MODULES does not carry, directly or through `requires transitive`.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Package prefix -> module, for the modules outside java.base the application can plausibly use.
PACKAGES = {
    "java.net.http": "java.net.http",
    "java.sql": "java.sql",
    "javax.sql": "java.sql",
    "java.util.logging": "java.logging",
    "java.lang.management": "java.management",
    "javax.management": "java.management",
    "javax.naming": "java.naming",
    "java.util.prefs": "java.prefs",
    "java.awt": "java.desktop",
    "javax.swing": "java.desktop",
    "javax.imageio": "java.desktop",
    "javax.sound": "java.desktop",
    "java.beans": "java.desktop",
    "javax.xml": "java.xml",
    "org.w3c.dom": "java.xml",
    "org.xml.sax": "java.xml",
    "javax.script": "java.scripting",
    "java.rmi": "java.rmi",
    "javax.tools": "java.compiler",
    "java.lang.instrument": "java.instrument",
    "com.sun.net.httpserver": "jdk.httpserver",
    "jdk.jfr": "jdk.jfr",
    "javax.crypto": "java.base",
    "javax.net": "java.base",
    "javax.security.auth": "java.base",
}
# Modules a listed module brings with it (`requires transitive`).
TRANSITIVE = {
    "java.desktop": {"java.datatransfer", "java.xml"},
    "java.sql": {"java.logging", "java.transaction.xa", "java.xml"},
    "jdk.management": {"java.management"},
}
IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)", re.M)


def runtime_modules(text):
    m = re.search(r"^MODULES=([\w.,]+)", text, re.M)
    mods = set(m.group(1).split(","))
    for mod in list(mods):
        mods |= TRANSITIVE.get(mod, set())
    return mods


def needed_modules(sources):
    needed = {}
    for path in sources:
        for name in IMPORT.findall(path.read_text(encoding="utf-8", errors="replace")):
            for prefix, mod in PACKAGES.items():
                if name == prefix or name.startswith(prefix + "."):
                    needed.setdefault(mod, path)
    return needed


class RuntimeModules(unittest.TestCase):
    def test_source_imports_are_in_the_runtime(self):
        have = runtime_modules((ROOT / "release/make-runtime.sh").read_text())
        needed = needed_modules(sorted((ROOT / "src").rglob("*.java")))
        missing = {m: str(p.relative_to(ROOT)) for m, p in needed.items() if m not in have}
        self.assertEqual(missing, {}, "add these modules to MODULES in release/make-runtime.sh")

    def test_check_can_fail(self):
        have = runtime_modules("MODULES=java.base,java.desktop\n")
        self.assertNotIn("java.net.http", have)
        self.assertIn("java.xml", have)


if __name__ == "__main__":
    unittest.main()
