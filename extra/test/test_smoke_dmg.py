"""release/smoke_dmg.sh --grade fails a log with an uncaught exception, a missing class, or no drawing."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = os.environ.get("SMOKE_SCRIPT", str(Path(__file__).resolve().parents[2] / "release" / "smoke_dmg.sh"))
DREW = "2026-10-11 08:48:47.675 [AWT-EventQueue-0] INFO  AngleRenderer.<init> - OpenGL context: vendor=Google\n"


class GradeTest(unittest.TestCase):
    def grade(self, text):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
            f.write(text)
        self.addCleanup(os.unlink, f.name)
        return subprocess.run(["sh", SCRIPT, "--grade", f.name], capture_output=True, text=True).returncode

    def test_a_clean_log_passes(self):
        self.assertEqual(self.grade(DREW + "[LWJGL] [ERROR] Incompatible Java and native library versions detected.\n"), 0)

    def test_the_0_8_6_crash_fails(self):
        self.assertEqual(self.grade(DREW + "2026-10-10 23:15:14.936 [HFS-FeedbackEndpoint] SEVERE  "
                                    "JHVUncaughtExceptionHandler$Base.uncaughtException - Uncaught exception\n"
                                    "java.lang.NoClassDefFoundError: java/net/http/HttpClient\n"), 1)

    def test_a_missing_class_alone_fails(self):
        self.assertEqual(self.grade(DREW + "Caused by: java.lang.ClassNotFoundException: java.net.http.HttpClient\n"), 1)

    def test_no_drawing_fails(self):
        self.assertEqual(self.grade("2026-10-11 INFO  UpdateCheck.show - Update check: 0.8.6 is current\n"), 1)


if __name__ == "__main__":
    unittest.main()
