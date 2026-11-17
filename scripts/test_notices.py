"""notices.py's reading of each kind of source, offline: a page as text, a slice of lines, a file in an archive."""
import contextlib
import hashlib
import importlib.util
import io
import tarfile
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest import mock

SPEC = importlib.util.spec_from_file_location("notices", Path(__file__).resolve().parent / "notices.py")
notices = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(notices)


class PageTextTest(unittest.TestCase):

    def test_blocks_are_apart_and_links_keep_their_address(self):
        page = (
            b"<html><head><title>Skipped</title><style>p{}</style></head><body>"
            b"<h1>Licence</h1><p>Read <a href='/edrdg/licence.html'>the licence</a> and"
            b" <a href='#top'>go up</a>.<br>Twice.</p><script>skipped()</script>"
            b"<p><a href='https://example.com/'>https://example.com/</a><a href='https://example.com/x'><img></a></p>"
            b"<form><p>Skipped</p></form></body></html>"
        )
        self.assertEqual(
            "Licence\n\nRead the licence <https://www.edrdg.org/edrdg/licence.html> and go up. Twice.\n\nhttps://example.com/\n",
            notices.page_text(page, "utf-8", "https://www.edrdg.org/wiki/").decode(),
        )


class DownloadsTest(unittest.TestCase):
    def test_a_transient_timeout_retries_the_same_source(self):
        answer = mock.MagicMock()
        answer.__enter__.return_value.read.return_value = b"notice"
        url = "https://example.invalid/notice"
        with (
            mock.patch.object(notices.urllib.request, "urlopen", side_effect=[urllib.error.URLError(TimeoutError()), answer]) as fetch,
            mock.patch("time.sleep"),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(b"notice", notices.download(url))
        self.assertEqual([url, url], [call.args[0].full_url for call in fetch.call_args_list])

    def test_missing_source_is_not_retried(self):
        url = "https://example.invalid/notice"
        missing = urllib.error.HTTPError(url, 404, "Not found", {}, None)
        with mock.patch.object(notices.urllib.request, "urlopen", side_effect=missing) as fetch, self.assertRaises(urllib.error.HTTPError):
            notices.download(url)
        fetch.assert_called_once()


class SourceBytesTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "LICENSE").write_bytes(b"one\ntwo\nthree\nfour\n")
        self.patch = mock.patch.object(notices, "ROOT", self.root)
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.tmp.cleanup()

    def test_a_file_in_the_checkout_whole_or_its_lines(self):
        self.assertEqual(b"one\ntwo\nthree\nfour\n", notices.source_bytes("LICENSE"))
        self.assertEqual(b"two\nthree\n", notices.source_bytes("LICENSE#L2-L3"))

    def test_a_file_inside_a_zip_or_a_tarball(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/NOTICE.md", "notice\n")
        tarball = io.BytesIO()
        with tarfile.open(fileobj=tarball, mode="w:gz") as archive:
            data = b"a\nb\nc\n"
            info = tarfile.TarInfo("jpeg-6b/README")
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        served = {"https://example.com/a.jar": jar.getvalue(), "https://example.com/b.tar.gz": tarball.getvalue()}
        with mock.patch.object(notices, "download", served.__getitem__):
            self.assertEqual(b"notice\n", notices.source_bytes("https://example.com/a.jar!/META-INF/NOTICE.md"))
            self.assertEqual(b"b\nc\n", notices.source_bytes("https://example.com/b.tar.gz!jpeg-6b/README#L2-L3"))

    def test_an_ndk_file_is_read_from_the_ndk_the_build_names(self):
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text('    ndkVersion = "1.2.3"\n')
        notice = self.root / "sdk/ndk/1.2.3/sysroot/NOTICE"
        notice.parent.mkdir(parents=True)
        notice.write_bytes(b"bionic\n")
        with mock.patch.dict("os.environ", {"ANDROID_HOME": str(self.root / "sdk")}):
            self.assertEqual(b"bionic\n", notices.source_bytes("ndk:sysroot/NOTICE"))

    def test_a_missing_ndk_says_which_and_where(self):
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text('    ndkVersion = "9.9.9"\n')
        with mock.patch.dict("os.environ", {"ANDROID_HOME": str(self.root / "sdk")}):
            with self.assertRaises(SystemExit) as stop:
                notices.source_bytes("ndk:NOTICE")
        self.assertIn("9.9.9", str(stop.exception.code))


class ManifestTest(unittest.TestCase):

    def test_check_fails_on_a_changed_text_and_fetch_writes_it_and_its_sha(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "texts").mkdir()
            (root / "LICENSE").write_bytes(b"licence\n")
            (root / "texts/mit.txt").write_bytes(b"changed\n")
            manifest = root / "manifest.txt"
            jmdict = root / "jmdict-notice-texts.txt"
            jmdict.touch()
            manifest.write_text("# name sha source\nmit.txt 0 LICENSE\n")
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, JMDICT_MANIFEST=jmdict, TEXTS=root / "texts"), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, notices.main(["notices.py", "check"]))
                self.assertEqual(0, notices.main(["notices.py", "fetch", "mit.txt"]))
                self.assertEqual(0, notices.main(["notices.py", "check"]))
            self.assertEqual(b"licence\n", (root / "texts/mit.txt").read_bytes())
            self.assertEqual("# name sha source\nmit.txt " + hashlib.sha256(b"licence\n").hexdigest() + " LICENSE\n", manifest.read_text())

    def test_a_missing_licence_text_names_the_file_and_stops(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "texts").mkdir()
            (root / "LICENSE").write_bytes(b"licence\n")
            manifest = root / "manifest.txt"
            jmdict = root / "jmdict-notice-texts.txt"
            jmdict.touch()
            manifest.write_text("mit.txt " + hashlib.sha256(b"licence\n").hexdigest() + " LICENSE\n")
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, JMDICT_MANIFEST=jmdict, TEXTS=root / "texts"), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as stop:
                    notices.main(["notices.py", "check"])
            self.assertIn("mit.txt", str(stop.exception.code))
            self.assertNotIn("\n", str(stop.exception.code))
            (root / "texts/mit.txt").write_bytes(b"licence\n")
            (root / "LICENSE").unlink()
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, JMDICT_MANIFEST=jmdict, TEXTS=root / "texts"), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as stop:
                    notices.main(["notices.py", "check"])
            self.assertIn("LICENSE", str(stop.exception.code))
            self.assertNotIn("Traceback", str(stop.exception.code))


def entry(name, data):
    digest = hashlib.sha256(data).hexdigest()
    return f"{name} {digest} https://example.invalid/{name}\n"


NEW_LICENCE = b"GENERAL DICTIONARY LICENCE STATEMENT\nLicence Conditions\nUpdating Dictionary Versions\nJMdict\n"
NEW_DOCUMENTATION = b"====== JMdict-EDICT Dictionary Project ======\nXML format\n"


class JmdictNoticesTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        folder = Path(scratch.name)
        self.texts = folder / "texts"
        self.texts.mkdir()
        self.manifest = folder / "notice-texts.txt"
        self.jmdict = folder / "jmdict-notice-texts.txt"
        self.pinned = "pinned.txt"
        self.licence = "edrdg-licence.txt"
        self.documentation = "jmdict-documentation.txt"
        self.manifest.write_text(entry(self.pinned, b"pinned"))
        self.jmdict.write_text(entry(self.licence, b"old") + entry(self.documentation, b"old"))
        for name, data in ((self.pinned, b"pinned"), (self.licence, b"old"), (self.documentation, b"old")):
            (self.texts / name).write_bytes(data)
        self.enterContext(mock.patch.multiple(
            notices, MANIFEST=self.manifest, JMDICT_MANIFEST=self.jmdict, TEXTS=self.texts,
        ))

    def test_the_dictionary_refresh_keeps_both_new_texts_and_their_digests(self):
        with mock.patch.object(notices, "source_bytes", side_effect=[NEW_LICENCE, NEW_DOCUMENTATION]) as fetch:
            self.assertEqual(0, notices.main(["notices.py", "fetch-jmdict"]))
        self.assertEqual(2, fetch.call_count)
        self.assertEqual(NEW_LICENCE, (self.texts / self.licence).read_bytes())
        self.assertEqual(NEW_DOCUMENTATION, (self.texts / self.documentation).read_bytes())
        self.assertEqual(entry(self.licence, NEW_LICENCE) + entry(self.documentation, NEW_DOCUMENTATION), self.jmdict.read_text())
        self.assertEqual(entry(self.pinned, b"pinned"), self.manifest.read_text())

    def test_a_failed_documentation_download_does_not_change_the_licence_snapshot(self):
        before = self.jmdict.read_bytes()
        with mock.patch.object(notices, "source_bytes", side_effect=[NEW_LICENCE, OSError("offline")]), self.assertRaises(OSError):
            notices.main(["notices.py", "fetch-jmdict"])
        self.assertEqual(before, self.jmdict.read_bytes())
        self.assertEqual(b"old", (self.texts / self.licence).read_bytes())

    def test_empty_or_error_responses_leave_both_texts_and_digests_intact(self):
        for responses in ((b"", NEW_DOCUMENTATION), (NEW_LICENCE, b"<html>Service unavailable</html>")):
            before = self.jmdict.read_bytes()
            with (
                self.subTest(responses=responses),
                mock.patch.object(notices, "source_bytes", side_effect=responses),
                self.assertRaisesRegex(SystemExit, "Expected EDRDG"),
            ):
                notices.main(["notices.py", "fetch-jmdict"])
            self.assertEqual(before, self.jmdict.read_bytes())
            self.assertEqual(b"old", (self.texts / self.licence).read_bytes())
            self.assertEqual(b"old", (self.texts / self.documentation).read_bytes())

    def test_release_checks_the_snapshots_without_requesting_the_live_jmdict_pages(self):
        with mock.patch.object(notices, "source_bytes", return_value=b"pinned") as fetch:
            self.assertEqual(0, notices.main(["notices.py", "check-release"]))
            (self.texts / self.licence).write_bytes(b"changed locally")
            self.assertEqual(1, notices.main(["notices.py", "check-release"]))
        self.assertEqual(
            [f"https://example.invalid/{self.pinned}"] * 2,
            [call.args[0] for call in fetch.call_args_list],
        )


if __name__ == "__main__":
    unittest.main()
