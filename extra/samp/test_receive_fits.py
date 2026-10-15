import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import receive_fits  # noqa: E402


class ReceiveFitsTest(unittest.TestCase):
    def test_file_url_becomes_path(self):
        self.assertEqual(receive_fits.url_to_path("file:///tmp/a%20b_phys.fits"), Path("/tmp/a b_phys.fits"))

    def test_other_schemes_are_refused(self):
        with self.assertRaises(ValueError):
            receive_fits.url_to_path("https://example.invalid/a.fits")

    def test_missing_file_is_ignored(self):
        self.assertIsNone(receive_fits.accept({"url": "file:///nonexistent/none.fits"}))
        self.assertIsNone(receive_fits.accept({}))

    def test_existing_file_is_collected(self):
        receive_fits.describe = lambda p: p.name  # no Astropy needed for the unit test
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "x_phys.fits"
            f.write_bytes(b"x")
            self.assertEqual(receive_fits.accept({"url": f.as_uri()}), f)
            self.assertIn(f, receive_fits.received)


if __name__ == "__main__":
    unittest.main()
