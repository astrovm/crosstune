import argparse
import gzip
import shutil
import tempfile
import unittest
from pathlib import Path

import baseline


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.work)

    def checkout(self, probes=None):
        checkout = self.work / "checkout"
        (checkout / baseline.TESTS).mkdir(parents=True)
        (checkout / baseline.MAIN).mkdir(parents=True)
        if probes is not None:
            (checkout / baseline.TESTS / "TitleProbes.kt").write_text(probes, encoding="utf-8")
        return checkout

    def test_mains_checkout_gets_the_fuzz_titles_and_mains_probes(self):
        checkout = self.checkout()
        baseline.copy_in(checkout)
        tests, main = checkout / baseline.TESTS, checkout / baseline.MAIN
        for name in ("TitleFuzz.kt", "IcuPattern.kt", "PhoneRegexSets.kt"):
            self.assertEqual((baseline.ROOT / baseline.TESTS / name).read_bytes(), (tests / name).read_bytes(), name)
        for name in ("PhoneRegex.kt", "PhoneRegexTable.kt"):
            self.assertEqual((baseline.ROOT / baseline.MAIN / name).read_bytes(), (main / name).read_bytes(), name)
        for name in ("WriteTitlesBaseline.kt", "LongTitlesProbe.kt"):
            self.assertEqual((baseline.HERE / name).read_bytes(), (tests / name).read_bytes(), name)
        self.assertEqual((baseline.HERE / "MainTitleProbes.kt").read_bytes(), (tests / "TitleProbes.kt").read_bytes())

    def test_a_checkout_with_probes_of_its_own_keeps_them(self):
        checkout = self.checkout(probes="// its own")
        baseline.copy_in(checkout)
        self.assertEqual("// its own", (checkout / baseline.TESTS / "TitleProbes.kt").read_text(encoding="utf-8"))

    def test_each_timing_asks_the_probe_for_its_own(self):
        def asked(**given):
            return baseline.timing(argparse.Namespace(**{"long": False, "long_all": False, "long_instrumental": False, **given}))
        self.assertIsNone(asked())
        self.assertEqual("", asked(long=True))
        self.assertEqual("all", asked(long_all=True))
        self.assertEqual("instrumental", asked(long_instrumental=True))

    def test_the_readings_are_kept_with_the_commit_and_engine_and_the_same_bytes_each_time(self):
        made = self.work / "made.tsv.gz"
        with gzip.open(made, "wt", encoding="utf-8") as out:
            out.write("ICU 78, API 37\nt1\tr1\nt2\tr2\n")
        first, second = self.work / "a" / "main.tsv.gz", self.work / "b" / "main.tsv.gz"
        self.assertEqual(("ICU 78, API 37", 2), baseline.write_readings(made, first, "abc123"))
        baseline.write_readings(made, second, "abc123")
        with gzip.open(first, "rt", encoding="utf-8") as read:
            self.assertEqual("# abc123 ICU 78, API 37\nt1\tr1\nt2\tr2\n", read.read())
        self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
