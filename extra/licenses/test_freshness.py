#!/usr/bin/env python3
"""Offline tests for freshness.py: Maven Central is replaced by a dictionary."""

import hashlib
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import freshness

CENTRAL = freshness.CENTRAL


def metadata(*versions):
    listed = "".join(f"<version>{v}</version>" for v in versions)
    return f"<metadata><versioning><versions>{listed}</versions></versioning></metadata>".encode()


class FreshnessTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "lib").mkdir()
        self.central = {}

    def jar(self, name, poms=()):
        path = self.root / "lib" / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("A.class", b"code")
            for group, artifact, version in poms:
                archive.writestr(
                    f"META-INF/maven/{group}/{artifact}/pom.properties",
                    f"groupId={group}\nartifactId={artifact}\nversion={version}\n",
                )
        return path

    def fetch(self, url):
        if url not in self.central:
            raise urllib.error.URLError("offline")
        return self.central[url]

    def row(self, jar, mapping=None):
        with patch.object(freshness, "fetch", self.fetch):
            return freshness.row(jar, self.root, mapping or {})

    def test_pom_coordinates_newer_release_with_the_same_suffix(self):
        jar = self.jar("guava-33.6.0-jre.jar", [("com.google.guava", "guava", "33.6.0-jre")])
        self.central[f"{CENTRAL}/com/google/guava/guava/maven-metadata.xml"] = metadata(
            "33.6.0-jre", "33.7.0-jre", "33.8.0-android", "34.0.0-rc1-jre"
        )
        self.assertEqual(
            self.row(jar), ["lib/guava-33.6.0-jre.jar", "33.6.0-jre", "33.7.0-jre", "newer available"]
        )

    def test_current_when_nothing_newer(self):
        jar = self.jar("nom-tam-fits-1.22.0.jar", [("gov.nasa.gsfc.heasarc", "nom-tam-fits", "1.22.0")])
        self.central[f"{CENTRAL}/gov/nasa/gsfc/heasarc/nom-tam-fits/maven-metadata.xml"] = metadata(
            "1.21.0", "1.22.0"
        )
        self.assertEqual(self.row(jar)[1:], ["1.22.0", "1.22.0", "current"])

    def test_no_coordinates_is_not_verified(self):
        jar = self.jar("jnispice.jar")
        self.assertEqual(self.row(jar), ["lib/jnispice.jar", "-", "-", "not verified"])

    def test_ambiguous_poms_are_not_guessed(self):
        jar = self.jar("ehcache-3.12.0.jar", [("a", "ehcache-api", "3.12.0"), ("a", "ehcache-core", "3.12.0")])
        self.assertEqual(self.row(jar)[3], "not verified")

    def test_mapped_coordinate_counts_only_when_central_sha1_matches(self):
        jar = self.jar("joml-1.10.9.jar")
        mapping = {"lib/joml-1.10.9.jar": {"group": "org.joml", "artifact": "joml", "version": "1.10.9"}}
        sha1_url = f"{CENTRAL}/org/joml/joml/1.10.9/joml-1.10.9.jar.sha1"
        self.central[f"{CENTRAL}/org/joml/joml/maven-metadata.xml"] = metadata("1.10.9", "1.10.10")
        self.central[sha1_url] = b"0000000000000000000000000000000000000000  joml-1.10.9.jar"
        self.assertEqual(self.row(jar, mapping)[3], "not verified")
        self.central[sha1_url] = hashlib.sha1(jar.read_bytes()).hexdigest().encode()
        self.assertEqual(self.row(jar, mapping)[1:], ["1.10.9", "1.10.10", "newer available"])

    def test_network_failure_is_not_verified_and_the_jar_is_untouched(self):
        jar = self.jar("commons-io-2.22.0.jar", [("commons-io", "commons-io", "2.22.0")])
        before = jar.read_bytes()
        self.assertEqual(self.row(jar)[2:], ["-", "not verified"])
        self.assertEqual(jar.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
