"""notices.py's reading of each kind of source, offline: a page as text, a slice of lines, a file in an archive."""
import contextlib
import hashlib
import html
import http.client
import importlib.util
import io
import socketserver
import ssl
import tarfile
import tempfile
import threading
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest import mock

SPEC = importlib.util.spec_from_file_location("notices", Path(__file__).resolve().parent / "notices.py")
notices = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(notices)


def http_error(test, code, message=""):
    """An HTTP error from the EDRDG site, with an empty body that's closed after the test, as a real one would be."""
    error = urllib.error.HTTPError("https://www.edrdg.org/", code, message, {}, io.BytesIO())
    test.addCleanup(error.close)
    return error


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
        with io.BytesIO(b"missing") as body:
            missing = urllib.error.HTTPError(url, 404, "Not found", {}, body)
            with mock.patch.object(notices.urllib.request, "urlopen", side_effect=missing) as fetch, self.assertRaises(urllib.error.HTTPError):
                notices.download(url)
            self.assertTrue(body.closed)
        fetch.assert_called_once()

    def test_disconnect_and_incomplete_body_retry_without_using_partial_bytes(self):
        first = mock.MagicMock()
        first.__enter__.return_value.read.side_effect = http.client.IncompleteRead(b"part", 3)
        next_answer = mock.MagicMock()
        next_answer.__enter__.return_value.read.return_value = b"notice"
        url = "https://example.invalid/notice"
        with (
            mock.patch.object(notices.urllib.request, "urlopen", side_effect=[
                http.client.RemoteDisconnected(), first, next_answer,
            ]) as fetch,
            mock.patch.object(notices.time, "sleep") as wait,
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(b"notice", notices.download(url))
        self.assertEqual([url] * 3, [call.args[0].full_url for call in fetch.call_args_list])
        self.assertEqual([mock.call(1), mock.call(2)], wait.call_args_list)

    def test_incomplete_body_stops_after_three_attempts(self):
        answer = mock.MagicMock()
        answer.__enter__.return_value.read.side_effect = http.client.IncompleteRead(b"part", 3)
        with (
            mock.patch.object(notices.urllib.request, "urlopen", return_value=answer) as fetch,
            mock.patch.object(notices.time, "sleep"),
            contextlib.redirect_stderr(io.StringIO()),
            self.assertRaises(http.client.IncompleteRead),
        ):
            notices.download("https://example.invalid/notice")
        self.assertEqual(3, fetch.call_count)


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
        served = {
            "https://example.com/a.jar": jar.getvalue(),
            "https://example.com/a.aar": jar.getvalue(),
            "https://example.com/b.tar.gz": tarball.getvalue(),
        }
        with mock.patch.object(notices, "download", served.__getitem__):
            self.assertEqual(b"notice\n", notices.source_bytes("https://example.com/a.jar!/META-INF/NOTICE.md"))
            self.assertEqual(b"notice\n", notices.source_bytes("https://example.com/a.aar!/META-INF/NOTICE.md"))
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

    def test_play_texts_are_checked_and_fetched_in_the_play_folder(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            main = root / "main"
            play_texts = root / "play"
            main.mkdir()
            play_texts.mkdir()
            manifest = root / "manifest.txt"
            jmdict = root / "jmdict.txt"
            play = root / "play.txt"
            manifest.write_text("")
            jmdict.write_text("")
            (root / "LICENSE").write_bytes(b"play licence\n")
            play.write_text("cast.txt 0 LICENSE\n")
            (play_texts / "cast.txt").write_bytes(b"stale\n")
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, JMDICT_MANIFEST=jmdict,
                                     PLAY_MANIFEST=play, TEXTS=main, PLAY_TEXTS=play_texts), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, notices.main(["notices.py", "check-release"]))
                self.assertEqual(0, notices.main(["notices.py", "fetch", "cast.txt"]))
                self.assertEqual(0, notices.main(["notices.py", "check-release"]))
            self.assertEqual(b"play licence\n", (play_texts / "cast.txt").read_bytes())
            self.assertFalse((main / "cast.txt").exists())
            self.assertIn(hashlib.sha256(b"play licence\n").hexdigest(), play.read_text())

    def test_check_fails_on_a_changed_text_and_fetch_writes_it_and_its_sha(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "texts").mkdir()
            (root / "LICENSE").write_bytes(b"licence\n")
            (root / "texts/mit.txt").write_bytes(b"changed\n")
            manifest = root / "manifest.txt"
            play = root / "notice-texts-play.txt"
            play.touch()
            jmdict = root / "jmdict-notice-texts.txt"
            jmdict.touch()
            manifest.write_text("# name sha source\nmit.txt 0 LICENSE\n")
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, PLAY_MANIFEST=play, JMDICT_MANIFEST=jmdict, TEXTS=root / "texts"), contextlib.redirect_stdout(io.StringIO()):
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
            play = root / "notice-texts-play.txt"
            play.touch()
            jmdict = root / "jmdict-notice-texts.txt"
            jmdict.touch()
            manifest.write_text("mit.txt " + hashlib.sha256(b"licence\n").hexdigest() + " LICENSE\n")
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, PLAY_MANIFEST=play, JMDICT_MANIFEST=jmdict, TEXTS=root / "texts"), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as stop:
                    notices.main(["notices.py", "check"])
            self.assertIn("mit.txt", str(stop.exception.code))
            self.assertNotIn("\n", str(stop.exception.code))
            (root / "texts/mit.txt").write_bytes(b"licence\n")
            (root / "LICENSE").unlink()
            with mock.patch.multiple(notices, ROOT=root, MANIFEST=manifest, PLAY_MANIFEST=play, JMDICT_MANIFEST=jmdict, TEXTS=root / "texts"), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as stop:
                    notices.main(["notices.py", "check"])
            self.assertIn("LICENSE", str(stop.exception.code))
            self.assertNotIn("Traceback", str(stop.exception.code))


def entry(name, data):
    digest = hashlib.sha256(data).hexdigest()
    return f"{name} {digest} https://example.invalid/{name}\n"


NEW_LICENCE = (notices.TEXTS / "edrdg-licence.txt").read_bytes()
NEW_DOCUMENTATION = (notices.TEXTS / "jmdict-documentation.txt").read_bytes()


class JmdictNoticesTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        folder = Path(scratch.name)
        self.texts = folder / "texts"
        self.texts.mkdir()
        self.manifest = folder / "notice-texts.txt"
        self.jmdict = folder / "jmdict-notice-texts.txt"
        self.play = folder / "notice-texts-play.txt"
        self.play.touch()
        self.pinned = "pinned.txt"
        self.licence = "edrdg-licence.txt"
        self.documentation = "jmdict-documentation.txt"
        self.manifest.write_text(entry(self.pinned, b"pinned"))
        self.jmdict.write_text(entry(self.licence, b"old") + entry(self.documentation, b"old"))
        for name, data in ((self.pinned, b"pinned"), (self.licence, b"old"), (self.documentation, b"old")):
            (self.texts / name).write_bytes(data)
        self.enterContext(mock.patch.multiple(
            notices, MANIFEST=self.manifest, JMDICT_MANIFEST=self.jmdict, PLAY_MANIFEST=self.play, TEXTS=self.texts,
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

    def test_an_unreachable_site_keeps_the_snapshot_only_when_asked(self):
        before = self.jmdict.read_bytes()
        offline = urllib.error.URLError(TimeoutError("timed out"))
        busy = http_error(self, 503, "Service Unavailable")
        for error in (offline, TimeoutError("timed out"), ConnectionResetError("reset"), busy):
            with self.subTest(error=error):
                with mock.patch.object(notices, "source_bytes", side_effect=[NEW_LICENCE, error]), \
                        contextlib.redirect_stdout(io.StringIO()) as said:
                    self.assertEqual(0, notices.main(["notices.py", "fetch-jmdict", "--keep-if-unreachable"]))
                self.assertIn("::warning::", said.getvalue())
                self.assertEqual(before, self.jmdict.read_bytes())
                self.assertEqual(b"old", (self.texts / self.licence).read_bytes())
                with mock.patch.object(notices, "source_bytes", side_effect=[error]), self.assertRaises(OSError):
                    notices.main(["notices.py", "fetch-jmdict"])

    def test_a_site_that_answers_wrong_still_fails_when_unreachable_is_allowed(self):
        refused = http_error(self, 404, "Not Found")
        bad_certificate = urllib.error.URLError(ssl.SSLCertVerificationError("certificate has expired"))
        for error in (refused, bad_certificate):
            with self.subTest(error=error), mock.patch.object(notices, "source_bytes", side_effect=[error]), \
                    self.assertRaises(urllib.error.URLError):
                notices.main(["notices.py", "fetch-jmdict", "--keep-if-unreachable"])
        with mock.patch.object(notices, "source_bytes", side_effect=[b""]), self.assertRaisesRegex(SystemExit, "Expected EDRDG"):
            notices.main(["notices.py", "fetch-jmdict", "--keep-if-unreachable"])

    def test_the_answers_download_retries_count_as_down_and_the_rest_as_answers(self):
        self.assertEqual([True, True, True, True, False, False, False],
                         [notices.unreachable(http_error(self, code)) for code in (408, 429, 500, 599, 499, 404, 403)])

    def test_an_unknown_option_or_one_for_another_command_stops_before_fetching(self):
        for argv in (["fetch-jmdict", "--keep-if-unreachble"], ["fetch", "--keep-if-unreachable"]):
            with self.subTest(argv=argv), mock.patch.object(notices, "source_bytes") as fetch, \
                    self.assertRaisesRegex(SystemExit, "takes no"):
                notices.main(["notices.py", *argv])
            fetch.assert_not_called()

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

    def test_cut_off_pages_with_the_initial_headings_leave_the_snapshot_intact(self):
        before = self.jmdict.read_bytes()
        cases = [
            (self.licence, b"GENERAL DICTIONARY LICENCE STATEMENT\nLicence Conditions\nUpdating Dictionary Versions\nJMdict\n", NEW_DOCUMENTATION),
            (self.licence, NEW_LICENCE.split(b"5. Warranty")[0], NEW_DOCUMENTATION),
            (self.licence, NEW_LICENCE.rsplit(b"(", 1)[0], NEW_DOCUMENTATION),
            (self.documentation, NEW_LICENCE, b"====== JMdict-EDICT Dictionary Project ======\nXML format\n"),
            (self.documentation, NEW_LICENCE, NEW_DOCUMENTATION.split(b"===== PUBLICATIONS =====")[0]),
            (self.documentation, NEW_LICENCE, NEW_DOCUMENTATION.split(b"ejdic_report1.pdf|(pdf)]]", 1)[0]),
        ]
        for name, licence, documentation in cases:
            with self.subTest(name=name, sizes=(len(licence), len(documentation))):
                with (
                    mock.patch.object(notices, "source_bytes", side_effect=[licence, documentation]),
                    self.assertRaisesRegex(SystemExit, f"Expected EDRDG content in {name}"),
                ):
                    notices.main(["notices.py", "fetch-jmdict"])
                self.assertEqual(before, self.jmdict.read_bytes())
                self.assertEqual(b"old", (self.texts / self.licence).read_bytes())
                self.assertEqual(b"old", (self.texts / self.documentation).read_bytes())

    def test_missing_app_attribution_and_distribution_is_rejected(self):
        for prefix in (b"", b"An added introduction.\n\n"):
            data = prefix + NEW_LICENCE
            start = data.index(b"in the case of a software package,")
            end = data.index(b"Note that in all cases,", start)
            shortened = data[:start] + data[end:]
            self.assertGreater(len(shortened), 7000)
            with self.assertRaisesRegex(SystemExit, "Expected EDRDG"):
                notices.check_jmdict_page(self.licence, shortened)

    def test_padding_cannot_hide_removed_liability(self):
        (self.texts / self.licence).write_bytes(NEW_LICENCE)
        start = NEW_LICENCE.index(b"5. Warranty and Liability")
        end = NEW_LICENCE.index(b"6. Copyright", start)
        damaged = b"Added introduction. " * 300 + NEW_LICENCE[:start] + b"5. Warranty and Liability\n\n" + NEW_LICENCE[end:]
        self.assertGreater(len(damaged), len(NEW_LICENCE))
        before = self.jmdict.read_bytes()
        with (mock.patch.object(notices, "source_bytes", side_effect=[damaged, NEW_DOCUMENTATION]),
              self.assertRaisesRegex(SystemExit, "shorter")):
            notices.main(["notices.py", "fetch-jmdict"])
        self.assertEqual(before, self.jmdict.read_bytes())
        self.assertEqual(NEW_LICENCE, (self.texts / self.licence).read_bytes())
        self.assertEqual(b"old", (self.texts / self.documentation).read_bytes())

    def test_padding_cannot_hide_shortening_any_numbered_section(self):
        (self.texts / self.licence).write_bytes(NEW_LICENCE)
        sections = notices.licence_sections(NEW_LICENCE)
        self.assertEqual(set(b"123456789"), {number[0] for number in sections})
        for number, section in sections.items():
            with self.subTest(section=number):
                heading = section.split(b"\n", 1)[0] + b"\n"
                damaged = b"Extra introduction. " * 500 + NEW_LICENCE.replace(section, heading, 1)
                with (mock.patch.object(notices, "source_bytes", return_value=damaged),
                      self.assertRaises(SystemExit)):
                    notices.main(["notices.py", "fetch-jmdict"])
                self.assertEqual(NEW_LICENCE, (self.texts / self.licence).read_bytes())

    def test_invalid_utf8_stops_after_three_attempts_without_writes(self):
        self.jmdict.write_text(entry(self.licence, b"old").replace("https:", "html:https:") + entry(self.documentation, b"old"))
        before = self.jmdict.read_bytes()
        answer = mock.MagicMock()
        answer.__enter__.return_value.read.return_value = b"<p>\xc3"
        with (mock.patch.object(notices.urllib.request, "urlopen", return_value=answer) as fetch,
              mock.patch.object(notices.time, "sleep"), contextlib.redirect_stderr(io.StringIO()),
              self.assertRaisesRegex(SystemExit, "Could not decode EDRDG")):
            notices.main(["notices.py", "fetch-jmdict"])
        self.assertEqual(3, fetch.call_count)
        self.assertEqual(before, self.jmdict.read_bytes())
        self.assertEqual(b"old", (self.texts / self.licence).read_bytes())
        self.assertEqual(b"old", (self.texts / self.documentation).read_bytes())

    def test_licence_section_expansion_can_be_refreshed(self):
        (self.texts / self.licence).write_bytes(NEW_LICENCE)
        expanded = NEW_LICENCE.replace(b"1. Introduction\n", b"1. Introduction\n\nAn added paragraph.\n", 1)
        with mock.patch.object(notices, "source_bytes", side_effect=[expanded, NEW_DOCUMENTATION]):
            self.assertEqual(0, notices.main(["notices.py", "fetch-jmdict"]))
        self.assertEqual(expanded, (self.texts / self.licence).read_bytes())

    def test_other_html_decode_errors_keep_their_existing_behavior(self):
        answer = mock.MagicMock()
        answer.__enter__.return_value.read.return_value = b"<p>\xc3"
        with mock.patch.object(notices.urllib.request, "urlopen", return_value=answer) as fetch:
            with self.assertRaises(UnicodeDecodeError):
                notices.source_bytes("html:https://example.invalid/notice")
        fetch.assert_called_once()

    def test_shorter_complete_pages_do_not_replace_the_snapshot(self):
        for name, original in ((self.licence, NEW_LICENCE), (self.documentation, NEW_DOCUMENTATION)):
            with self.subTest(name=name):
                for page, data in ((self.licence, NEW_LICENCE), (self.documentation, NEW_DOCUMENTATION)):
                    (self.texts / page).write_bytes(data)
                (self.texts / name).write_bytes(original + b"Extra reviewed text.\n")
                before = self.jmdict.read_bytes()
                with mock.patch.object(notices, "source_bytes", side_effect=[NEW_LICENCE, NEW_DOCUMENTATION]):
                    with self.assertRaisesRegex(SystemExit, "shorter"):
                        notices.main(["notices.py", "fetch-jmdict"])
                self.assertEqual(before, self.jmdict.read_bytes())
                self.assertEqual(original + b"Extra reviewed text.\n", (self.texts / name).read_bytes())
                other = self.documentation if name == self.licence else self.licence
                self.assertEqual(NEW_DOCUMENTATION if other == self.documentation else NEW_LICENCE,
                                 (self.texts / other).read_bytes())

    def test_close_delimited_partial_page_retries_before_writing(self):
        def response(data, page=True):
            answer = mock.MagicMock()
            answer.__enter__.return_value.read.return_value = ("".join("<p>" + html.escape(block) + "</p>" for block in data.decode().strip().split("\n\n"))).encode() if page else data
            return answer
        self.jmdict.write_text(
            entry(self.licence, b"old").replace("https:", "html:https:") +
            entry(self.documentation, b"old")
        )
        with (mock.patch.object(notices.urllib.request, "urlopen", side_effect=[
                response(NEW_LICENCE[:100]), response(NEW_LICENCE), response(NEW_DOCUMENTATION, page=False),
            ]) as fetch, mock.patch.object(notices.time, "sleep"), contextlib.redirect_stderr(io.StringIO())):
            self.assertEqual(0, notices.main(["notices.py", "fetch-jmdict"]))
        self.assertEqual(3, fetch.call_count)
        self.assertEqual(NEW_LICENCE, (self.texts / self.licence).read_bytes())
        self.assertEqual(NEW_DOCUMENTATION, (self.texts / self.documentation).read_bytes())

    def test_http_close_delimited_eof_retries_and_keeps_exact_bytes(self):
        bodies = [NEW_DOCUMENTATION[:100], NEW_DOCUMENTATION]

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.recv(4096)
                self.request.sendall(b"HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n" + bodies.pop(0))

        with socketserver.TCPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                url = f"https://example.invalid/{self.documentation}"
                local = f"http://127.0.0.1:{server.server_address[1]}/"
                original = notices.urllib.request.urlopen
                def open_local(request, **kwargs):
                    return original(local, **kwargs)
                def validate(data):
                    try:
                        notices.check_jmdict_page(self.documentation, data)
                    except SystemExit as error:
                        raise notices.InvalidPage(str(error)) from error
                with (mock.patch.object(notices.urllib.request, "urlopen", side_effect=open_local),
                      mock.patch.object(notices.time, "sleep"), contextlib.redirect_stderr(io.StringIO())):
                    self.assertEqual(NEW_DOCUMENTATION, notices.download(url, validate))
                self.assertEqual([], bodies)
            finally:
                server.shutdown()
                thread.join()

    def test_http_html_mid_utf8_eof_retries_before_writing(self):
        valid = ("".join("<p>" + html.escape(block) + "</p>" for block in NEW_LICENCE.decode().strip().split("\n\n"))).encode()
        bodies = [b"<p>\xc3", valid]

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.recv(4096)
                self.request.sendall(b"HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n" + bodies.pop(0))

        self.jmdict.write_text(entry(self.licence, b"old").replace("https:", "html:https:") + entry(self.documentation, b"old"))
        with socketserver.TCPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                local = f"http://127.0.0.1:{server.server_address[1]}/"
                original = notices.urllib.request.urlopen
                def open_local(request, **kwargs):
                    if request.full_url.endswith(self.licence):
                        return original(local, **kwargs)
                    answer = mock.MagicMock()
                    answer.__enter__.return_value.read.return_value = NEW_DOCUMENTATION
                    return answer
                with (mock.patch.object(notices.urllib.request, "urlopen", side_effect=open_local),
                      mock.patch.object(notices.time, "sleep"), contextlib.redirect_stderr(io.StringIO())):
                    self.assertEqual(0, notices.main(["notices.py", "fetch-jmdict"]))
                self.assertEqual([], bodies)
                self.assertEqual(NEW_LICENCE, (self.texts / self.licence).read_bytes())
                self.assertEqual(NEW_DOCUMENTATION, (self.texts / self.documentation).read_bytes())
            finally:
                server.shutdown()
                thread.join()

    def test_rejected_responses_stop_after_three_attempts_without_writes(self):
        self.jmdict.write_text(entry(self.licence, b"old").replace("https:", "html:https:") +
                               entry(self.documentation, b"old"))
        before = self.jmdict.read_bytes()
        answer = mock.MagicMock()
        answer.__enter__.return_value.read.return_value = b"partial"
        with (mock.patch.object(notices.urllib.request, "urlopen", return_value=answer) as fetch,
              mock.patch.object(notices.time, "sleep"), contextlib.redirect_stderr(io.StringIO()),
              self.assertRaisesRegex(SystemExit, "Expected EDRDG")):
            notices.main(["notices.py", "fetch-jmdict"])
        self.assertEqual(3, fetch.call_count)
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
