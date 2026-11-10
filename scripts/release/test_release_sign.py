"""The release workflow checks the App Bundle against the upload key."""
from pathlib import Path
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/release.yml"


class ReleaseSignTest(unittest.TestCase):
    def test_aab_verify_uses_the_upload_keystore(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        start = text.index("jarsigner -verify")
        command = text[start:text.index("certs=", start)]
        self.assertIn('-keystore "$keystore"', command)
        self.assertIn("-storepass:env RELEASE_KEYSTORE_PASSWORD", command)
        self.assertNotIn('jarsigner -verify "Crosstune-$TAG.aab"', text)
