"""No external rights websites or media are touched; all URLs fictional."""
import unittest

from tools.photo_lineage.license_reference_guard import consistent_license_reference as ok


class LicenseReferenceGuardTests(unittest.TestCase):
    def test_known_license_identifiers_accept_matching_official_reference(self):
        valid = (
            ("CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/"),
            ("CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/"),
            ("CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/"),
            ("PUBLIC-DOMAIN", "https://creativecommons.org/publicdomain/mark/1.0/"),
            ("PUBLIC-DOMAIN", "https://commons.wikimedia.org/wiki/Template:PD-old-70"),
        )
        for label, url in valid:
            with self.subTest(label=label, url=url):
                self.assertTrue(ok(label, url))
                self.assertTrue(ok(label, url.rstrip("/")))

    def test_mismatched_license_labels_are_not_accepted(self):
        self.assertFalse(ok("CC-BY-4.0", "https://creativecommons.org/licenses/by-sa/4.0/"))
        self.assertFalse(ok("CC0-1.0", "https://creativecommons.org/licenses/by/4.0/"))
        self.assertFalse(ok("CC-BY-SA-4.0", "https://creativecommons.org/licenses/by/4.0/"))
        self.assertFalse(ok("PUBLIC-DOMAIN", "https://creativecommons.org/licenses/by/4.0/"))

    def test_spoofed_hosts_queries_ports_credentials_and_escapes_fail(self):
        base = "https://creativecommons.org/licenses/by/4.0/"
        for bad in (
            base + "?tracking=1", base + "#fake", base + "\n",
            "http://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org.evil.test/licenses/by/4.0/",
            "https://creativecommons.org@evil.test/licenses/by/4.0/",
            "https://creativecommons.org:8443/licenses/by/4.0/",
            "https://creativecommons.org/licenses/by/%34.0/",
            "https://creativecommons.org/licenses/by/4.0//",
            "https://creativecommons.org/licenses/by/4.0/../sa/4.0",
            "https://creativecommons.org/licenses/by/4.0\\bad",
            "https://evil.test/licenses/by/4.0/",
        ):
            with self.subTest(url=bad):
                self.assertFalse(ok("CC-BY-4.0", bad))

    def test_no_arbitrary_public_domain_claim_pointer(self):
        self.assertFalse(ok("PUBLIC-DOMAIN", "https://example.com/rights/PD"))
        self.assertFalse(ok("PUBLIC-DOMAIN", "https://commons.wikimedia.org/wiki/Template:PD-"))
        self.assertFalse(ok("PUBLIC-DOMAIN", "https://commons.wikimedia.org/wiki/File:foo.jpg"))
        self.assertFalse(ok("PUBLIC-DOMAIN", "https://commons.wikimedia.org/wiki/Template:PD-%252e"))
        for value in (None, True, 2, "", "https://creativecommons.org/"):
            self.assertFalse(ok("CC-BY-4.0", value))
        for key in ("CC-BY-3.0", "GPL", None, False):
            self.assertFalse(ok(key, "https://creativecommons.org/licenses/by/4.0/"))


if __name__ == "__main__":
    unittest.main()
