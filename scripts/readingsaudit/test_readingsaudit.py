import datetime
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import readingsaudit  # noqa: E402


def entry(kanji, kana):
    """A JMdict entry as jmdict-simplified writes it: kanji [(text, tags)], kana [(text, tags, applies to)]."""
    return {"kanji": [{"text": text, "tags": tags} for text, tags in kanji],
            "kana": [{"text": text, "tags": tags, "appliesToKanji": applies} for text, tags, applies in kana]}


class ListedCandidates(unittest.TestCase):

    def test_keeps_words_of_two_kanji_read_one_way_only(self):
        words = [
            entry([("星空", [])], [("ほしぞら", [], ["*"])]),
            # Two readings, from one entry or from two: no way to tell which is sung.
            entry([("雨空", [])], [("あまぞら", [], ["*"]), ("あまぞら", [], ["*"]), ("うくう", [], ["*"])]),
            entry([("月夜", [])], [("つきよ", [], ["*"])]),
            entry([("月夜", [])], [("げつや", [], ["*"])]),
            # One kanji, or kana first, or a letter that isn't Japanese: not a word the app looks for.
            entry([("夢", [])], [("ゆめ", [], ["*"])]),
            entry([("お茶屋", [])], [("おちゃや", [], ["*"])]),
            entry([("Ｘ線写真", [])], [("エックスせんしゃしん", [], ["*"])]),
        ]
        self.assertEqual({"星空": "ほしぞら"}, readingsaudit.listed_candidates(words))

    def test_reads_katakana_as_hiragana_and_keeps_kana_written_after_the_kanji(self):
        words = [entry([("肉付く", [])], [("ニクヅク", [], ["*"])]), entry([("硝子窓", [])], [("ガラスまど", [], ["*"])])]
        self.assertEqual({"肉付く": "にくづく", "硝子窓": "がらすまど"}, readingsaudit.listed_candidates(words))

    def test_leaves_out_odd_forms_and_odd_kana(self):
        words = [
            # A form for search only is not how lyrics write the word.
            entry([("迚角", ["sK"])], [("とてかく", [], ["*"])]),
            # Rare kana don't count against the usual one; kana for another form don't count at all.
            entry([("上手", []), ("上手い", [])], [("じょうず", [], ["上手"]), ("じょうて", ["rk"], ["*"]), ("うまい", [], ["上手い"])]),
        ]
        self.assertEqual({"上手": "じょうず", "上手い": "うまい"}, readingsaudit.listed_candidates(words))


class Table(unittest.TestCase):

    def test_writes_the_candidates_with_where_they_come_from_and_runs_the_test_that_keeps_them(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            jmdict = {"version": "9.9.9", "dictDate": "2000-01-01", "words": [entry([("星空", [])], [("ほしぞら", [], ["*"])])]}
            (folder / "jmdict.json").write_text(json.dumps(jmdict, ensure_ascii=False))
            with mock.patch.object(readingsaudit, "run") as run, mock.patch("builtins.print"):
                readingsaudit.table(folder, "./gradlew")
            run.assert_called_once_with(folder, "com.astrovm.crosstune.songcheck.JmdictReadings", "./gradlew")
            lines = (folder / "listed.txt").read_text().splitlines()
            self.assertEqual("星空\tほしぞら", lines[-1])
            header = "\n".join(line for line in lines if line.startswith("#"))
            self.assertTrue(lines[0].startswith("# JMdict 2000-01-01"))
            self.assertIn("jmdict-simplified 9.9.9", header)
            # Where the words come from, their licence and that they were changed, as CC BY-SA 4.0 asks.
            for said in ("Electronic Dictionary Research and Development Group", readingsaudit.JMDICT_URL, "CC BY-SA 4.0",
                         readingsaudit.CC_BY_SA_URL, readingsaudit.EDRDG_LICENCE_URL, "Changed:"):
                self.assertIn(said, header)

    def test_the_committed_table_has_the_header_a_remake_writes(self):
        table = (Path(__file__).resolve().parents[2] / "app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt").read_text()
        header = [line for line in table.splitlines() if line.startswith("#")]
        date, version = header[0].split()[2].rstrip(","), header[1].split()[3].rstrip(".")
        self.assertEqual(readingsaudit.table_header(date, version), header)


def word(kanji, kana, senses):
    """A JMdict entry for old_kana: kanji [(text, tags, common)], kana [(text, tags, common, applies to)], senses [(part of speech, misc)]."""
    return {"kanji": [{"text": text, "tags": tags, "common": common} for text, tags, common in kanji],
            "kana": [{"text": text, "tags": tags, "common": common, "appliesToKanji": applies} for text, tags, common, applies in kana],
            "sense": [{"partOfSpeech": pos, "misc": misc} for pos, misc in senses]}


class OldKana(unittest.TestCase):

    def test_names_each_common_word_with_i_or_u_by_its_usual_form(self):
        words = [
            word([("思い出", [], True), ("想い出", [], False)], [("おもいで", [], True, ["*"])], [(["n"], [])]),
            # Usually written in kana, or with no kanji: looked up as it is.
            word([("有難う", [], True)], [("ありがとう", [], True, ["*"])], [(["int"], ["uk"])]),
            word([], [("ちょうど", [], True, [])], [(["adv"], [])]),
            # No common form: the first one there is, but no form for search only.
            word([("迚", ["sK"], False), ("会う", [], False)], [("あう", [], True, ["*"])], [(["v5u"], [])]),
            # Two common words written so: no telling which, so the kana.
            word([("言う", [], True)], [("いう", [], True, ["*"])], [(["v5u"], [])]),
            word([("結う", [], True)], [("いう", [], True, ["*"])], [(["v5u"], [])]),
        ]
        today, _ = readingsaudit.old_kana(words)
        self.assertEqual({"おもいで": "思い出", "ありがとう": "ありがとう", "ちょうど": "ちょうど", "あう": "会う", "いう": "いう"}, today)

    def test_leaves_out_adjectives_rare_words_odd_kana_and_other_letters(self):
        words = [
            # 古い was ふるし, never ふるひ.
            word([("古い", [], True)], [("ふるい", [], True, ["*"])], [(["adj-i"], [])]),
            word([("会う", [], True)], [("あう", [], False, ["*"])], [(["v5u"], [])]),
            word([("言う", [], True)], [("ゆう", ["ik"], True, ["*"])], [(["v5u"], [])]),
            word([], [("ウイスキー", [], True, [])], [(["n"], [])]),
            # い or う first is no old ひ or ふ.
            word([("家", [], True)], [("いえ", [], True, ["*"])], [(["n"], [])]),
        ]
        self.assertEqual(({}, []), readingsaudit.old_kana(words))

    def test_lists_the_words_written_with_hi_or_fu_today_but_archaic_ones(self):
        words = [
            word([("工夫", [], True)], [("くふう", [], True, ["*"])], [(["n"], [])]),
            word([("お昼", [], True)], [("おひる", [], False, ["*"])], [(["n"], []), (["n"], ["arch"])]),
            word([("言ふ", [], False)], [("いふ", [], False, ["*"])], [(["v4h"], ["arch"])]),
            word([], [("ひと", ["ok"], False, [])], [(["n"], [])]),
        ]
        self.assertEqual(["おひる", "くふう"], readingsaudit.old_kana(words)[1])

    def test_writes_the_table_with_where_it_comes_from(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            words = [word([("思い出", [], True)], [("おもいで", [], True, ["*"])], [(["n"], [])]),
                     word([("工夫", [], True)], [("くふう", [], True, ["*"])], [(["n"], [])])]
            (folder / "jmdict.json").write_text(json.dumps({"version": "9.9.9", "dictDate": "2000-01-01", "words": words}, ensure_ascii=False))
            out = folder / "old-kana.txt"
            with mock.patch.object(readingsaudit, "OLD_KANA", out), mock.patch("builtins.print") as said:
                readingsaudit.oldkana(folder)
            lines = out.read_text().splitlines()
            header = "\n".join(line for line in lines if line.startswith("#"))
            self.assertTrue(lines[0].startswith("# JMdict 2000-01-01"))
            # Where it's from, its license and that it's changed, as the Group's licence asks.
            for part in ("jmdict-simplified 9.9.9", "CC BY-SA 4.0", "Changed:", readingsaudit.JMDICT_URL,
                         "https://creativecommons.org/licenses/by-sa/4.0/", "https://www.edrdg.org/edrdg/licence.html"):
                self.assertIn(part, header)
            self.assertEqual(["おもいで\t思い出", "くふう\t工夫", "くふう"], [line for line in lines if not line.startswith("#")])
            self.assertIn("2 words today and 1 modern words", said.call_args.args[0])

    def test_the_committed_table_has_the_header_a_remake_writes(self):
        header = [line for line in readingsaudit.OLD_KANA.read_text().splitlines() if line.startswith("#")]
        date, version = header[0].split()[2].rstrip(","), header[1].split()[3].rstrip(".")
        self.assertEqual(readingsaudit.old_kana_header(date, version), header)


TAG = "9.9.9+20000101000000"
ZIP_NAME = f"jmdict-eng-{TAG}.json.zip"
DOWNLOAD = f"https://github.com/scriptin/jmdict-simplified/releases/download/9.9.9%2B20000101000000/{ZIP_NAME}"


def zipped(files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        for name, text in files.items():
            archive.writestr(name, text)
    return out.getvalue()


class Answer(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class FakeGitHub:
    """Answers the release API and the download the way GitHub does, from what each test gives it."""

    def __init__(self, data, assets=None, tag=TAG):
        self.data = data
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        self.release = {"tag_name": tag, "assets": assets if assets is not None else [
            {"name": f"jmdict-eng-common-{tag}.json.zip", "size": 1, "digest": "sha256:00",
             "browser_download_url": DOWNLOAD.replace("jmdict-eng-", "jmdict-eng-common-")},
            {"name": ZIP_NAME, "size": len(data), "digest": digest, "browser_download_url": DOWNLOAD},
        ]}
        self.asked = []

    def __call__(self, request):
        self.asked.append(request)
        if request.full_url.startswith(readingsaudit.JMDICT_RELEASES):
            return Answer(json.dumps(self.release).encode())
        return Answer(self.data)


class Fetch(unittest.TestCase):

    def setUp(self):
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory())) / "data"
        self.enterContext(mock.patch("builtins.print"))
        self.enterContext(mock.patch.dict(os.environ, {"GH_TOKEN": ""}))

    def test_puts_the_newest_jmdict_eng_in_the_folder_when_it_is_what_the_release_says(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": '{"dictDate": "2000-01-01"}'}))
        self.assertEqual(TAG, readingsaudit.fetch(self.folder, opener=github))
        self.assertEqual('{"dictDate": "2000-01-01"}', (self.folder / "jmdict.json").read_text())
        self.assertEqual([f"{readingsaudit.JMDICT_RELEASES}/latest", DOWNLOAD], [r.full_url for r in github.asked])
        self.assertIsNone(github.asked[0].get_header("Authorization"))

    def test_asks_for_the_release_of_a_tag_with_the_runners_token(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        with mock.patch.dict(os.environ, {"GH_TOKEN": "fake-token"}):
            readingsaudit.fetch(self.folder, TAG, opener=github)
        self.assertEqual(f"{readingsaudit.JMDICT_RELEASES}/tags/9.9.9%2B20000101000000", github.asked[0].full_url)
        self.assertEqual("Bearer fake-token", github.asked[0].get_header("Authorization"))
        # The token is GitHub's API's, never sent with the download.
        self.assertIsNone(github.asked[1].get_header("Authorization"))

    def test_keeps_nothing_that_is_not_what_the_release_says(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        asset = {"name": ZIP_NAME, "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest(),
                 "browser_download_url": DOWNLOAD}
        for assets, said in [
            ([], "has 0 jmdict-eng zips"),
            ([asset, {**asset, "name": f"jmdict-eng-{TAG}-again.json.zip"}], "has 2 jmdict-eng zips"),
            ([{**asset, "digest": None}], "gives no SHA-256"),
            ([{k: v for k, v in asset.items() if k != "digest"}], "gives no SHA-256"),
            ([{**asset, "browser_download_url": DOWNLOAD.replace("20000101000000", "19990101000000")}], "isn't under release"),
            ([{**asset, "size": len(data) + 1}], f"is {len(data)} bytes"),
            ([{**asset, "digest": "sha256:" + "0" * 64}], "has sha256:"),
        ]:
            with self.subTest(said), self.assertRaisesRegex(SystemExit, said):
                readingsaudit.fetch(self.folder, opener=FakeGitHub(data, assets))
            self.assertFalse((self.folder / "jmdict.json").exists())

    def test_wants_one_json_file_in_the_zip(self):
        for files, count in [({"a.json": "{}", "b.json": "{}"}, 2), ({"README.md": ""}, 0)]:
            with self.subTest(count), self.assertRaisesRegex(SystemExit, f"holds {count} JSON files"):
                readingsaudit.fetch(self.folder, opener=FakeGitHub(zipped(files)))

    def test_writes_jmdict_json_whatever_path_the_zip_gives(self):
        readingsaudit.fetch(self.folder, opener=FakeGitHub(zipped({"../../escape.json": "{}"})))
        self.assertEqual(["jmdict.json"], [p.name for p in self.folder.parent.rglob("*.json")])


def dated(folder, name, date):
    path = folder / name
    path.write_text("\n".join(readingsaudit.table_header(date, "9.9.9") + ["星空\tほしぞら"]) + "\n")
    return path


class Age(unittest.TestCase):

    def setUp(self):
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.said = self.enterContext(mock.patch("builtins.print"))

    def test_passes_up_to_the_age_and_fails_past_it_naming_the_old_file(self):
        fresh = dated(self.folder, "fresh.txt", "2000-03-01")
        old = dated(self.folder, "old.txt", "2000-01-01")
        self.assertEqual(0, readingsaudit.age([fresh, old], datetime.date(2000, 3, 31), max_days=90))
        self.assertEqual(1, readingsaudit.age([fresh, old], datetime.date(2000, 4, 1), max_days=90))
        said = [call.args[0] for call in self.said.call_args_list]
        self.assertIn("old.txt: JMdict 2000-01-01, 91 days old", said)
        self.assertTrue(any(line.startswith("old.txt is older than 90 days") for line in said))
        self.assertFalse(any(line.startswith("fresh.txt is older") for line in said))

    def test_a_table_with_no_date_stops_the_check(self):
        path = self.folder / "undated.txt"
        path.write_text("星空\tほしぞら\n")
        with self.assertRaisesRegex(SystemExit, "no JMdict date"):
            readingsaudit.age([path], datetime.date(2000, 1, 1))

    def test_the_committed_tables_have_a_date_the_check_reads(self):
        for path in (readingsaudit.TABLE, readingsaudit.OLD_KANA):
            self.assertGreaterEqual(readingsaudit.header_date(path), datetime.date(2026, 11, 9))


class Run(unittest.TestCase):

    def gradle_command(self, *args, **kwargs):
        with mock.patch.object(readingsaudit.subprocess, "call", return_value=3) as call, self.assertRaises(SystemExit) as stop:
            readingsaudit.run(Path("/fake/folder"), "Some.Test", *args, **kwargs)
        self.assertEqual(3, stop.exception.code)
        self.assertEqual("/fake/folder", call.call_args.kwargs["env"]["SONGCHECK_DIR"])
        return call.call_args.args[0]

    def test_runs_gradle_in_the_box_unless_told_another_way(self):
        tail = [":app:songCheck", "-PsongcheckDir=/fake/folder", "-PsongcheckTest=Some.Test"]
        box = str(readingsaudit.ROOT / "scripts/gradle-box/gradle-box.sh")
        self.assertEqual(["nice", "-n", "10", box, "--max-workers=2", *tail], self.gradle_command())
        self.assertEqual(["nice", "-n", "10", "./gradlew", "--build-cache", *tail], self.gradle_command("./gradlew --build-cache"))


class Main(unittest.TestCase):

    def test_passes_each_step_its_options(self):
        with mock.patch.object(readingsaudit, "data_dir", return_value=Path("/fake")):
            with mock.patch.object(readingsaudit, "table") as table:
                readingsaudit.main(["table", "--gradle", "./gradlew"])
            table.assert_called_once_with(Path("/fake"), "./gradlew")
            with mock.patch.object(readingsaudit, "run") as run:
                readingsaudit.main(["run"])
            run.assert_called_once_with(Path("/fake"), gradle=None)
            with mock.patch.object(readingsaudit, "fetch") as fetch:
                readingsaudit.main(["fetch", "--tag", TAG])
            fetch.assert_called_once_with(Path("/fake"), TAG)
            with mock.patch.object(readingsaudit, "age", return_value=1) as age, self.assertRaises(SystemExit) as stop:
                readingsaudit.main(["age"])
            self.assertEqual(1, stop.exception.code)
            self.assertEqual([readingsaudit.TABLE, readingsaudit.OLD_KANA], age.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
