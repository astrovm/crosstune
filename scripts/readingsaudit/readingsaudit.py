#!/usr/bin/env python3
"""Checks the readings (furigana) Crosstune shows on Japanese songs against how the songs are sung.

  python3 scripts/readingsaudit/readingsaudit.py songs     # the songs below, as SongCheck's songs.json
  python3 scripts/readingsaudit/readingsaudit.py run       # the app's lyrics, timing, chords and readings on each, through gradle-box
  python3 scripts/readingsaudit/readingsaudit.py compare   # each word's reading against UtaTen's ruby; numbers, and the wrong ones by cause
  python3 scripts/readingsaudit/readingsaudit.py compare --words   # every wrong word too, with its song: for a person to read, not to publish
  python3 scripts/readingsaudit/readingsaudit.py compare --before before   # and what changed since a run kept in that folder: cp -r audit before
  python3 scripts/readingsaudit/readingsaudit.py table     # remake the app's jmdict-readings.txt from jmdict.json, through gradle-box
  python3 scripts/readingsaudit/readingsaudit.py oldkana   # remake the app's old-kana.txt from jmdict.json
  python3 scripts/readingsaudit/readingsaudit.py fetch     # put the newest jmdict-eng in the folder as jmdict.json; --tag TAG for another
  python3 scripts/readingsaudit/readingsaudit.py age       # fail when either file's JMdict is older than MAX_AGE_DAYS; the release runs it

`table --gradle "./gradlew --build-cache"` runs Gradle as given instead of through gradle-box, as the
JMdict workflow does on a runner. EDRDG's licence asks for a way to update JMdict's data regularly from
its newest version: the JMdict workflow runs fetch, table and oldkana each month.

Songs' words are people's, so everything goes in the agent cache, never in the repo:
$READINGSAUDIT_DIR, or /mnt/data/agent-cache/crosstune-anime-readings, or ~/.cache/agent-cache/crosstune-anime-readings.
`run` looks each song's words up once (found/), and works the readings out again each time, so a
change to them is measured on the same words; UtaTen's pages are kept (http/) and asked a second apart.
The truth is UtaTen's ruby, the kana it writes over each kanji as it's sung, on the page the app
itself finds; a song UtaTen doesn't have has none, and isn't counted. `compare` wants `jmdict.json`
in the folder (jmdict-eng from github.com/scriptin/jmdict-simplified) to tell a word read another
way the dictionary knows from one sung as another word; without it, those two causes are one.
Lines with no ruby are checked against Sudachi with its full dictionary instead, as weaker evidence,
when the folder has a venv with it: uv venv venv && VIRTUAL_ENV=venv uv pip install sudachipy sudachidict_full
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def data_dir():
    if os.environ.get("READINGSAUDIT_DIR"):
        return Path(os.environ["READINGSAUDIT_DIR"])
    mr = Path("/mnt/data/agent-cache")
    return (mr if mr.is_dir() else Path.home() / ".cache/agent-cache") / "crosstune-anime-readings"


# Well-known songs, kanji-heavy, across decades: anime openings and endings, city pop, and the rest.
SONGS = {
    "anime": [
        ("残酷な天使のテーゼ", "高橋洋子"), ("魂のルフラン", "高橋洋子"), ("タッチ", "岩崎良美"), ("CHA-LA HEAD-CHA-LA", "影山ヒロノブ"),
        ("愛をとりもどせ!!", "クリスタルキング"), ("ムーンライト伝説", "DALI"), ("宇宙戦艦ヤマト", "ささきいさお"), ("銀河鉄道999", "ゴダイゴ"),
        ("炎のたからもの", "ボビー"), ("悲しみよこんにちは", "斉藤由貴"), ("微笑みの爆弾", "馬渡松子"), ("ゆずれない願い", "田村直美"),
        ("君をのせて", "井上あずみ"), ("風の谷のナウシカ", "安田成美"), ("もののけ姫", "米良美一"), ("いつも何度でも", "木村弓"),
        ("アンバランスなKissをして", "高橋ひろ"), ("Butter-Fly", "和田光司"), ("そばかす", "JUDY AND MARY"), ("1/3の純情な感情", "SIAM SHADE"),
        ("only my railgun", "fripSide"), ("紅蓮の弓矢", "Linked Horizon"), ("心臓を捧げよ!", "Linked Horizon"), ("紅蓮華", "LiSA"),
        ("炎", "LiSA"), ("残響散歌", "Aimer"), ("アイドル", "YOASOBI"), ("祝福", "YOASOBI"), ("KICK BACK", "米津玄師"),
        ("unravel", "TK from 凛として時雨"), ("シルエット", "KANA-BOON"), ("ブルーバード", "いきものがかり"), ("GO!!!", "FLOW"),
        ("遥か彼方", "ASIAN KUNG-FU GENERATION"), ("創聖のアクエリオン", "AKINO"), ("鳥の詩", "Lia"), ("secret base ～君がくれたもの～", "ZONE"),
        ("コネクト", "ClariS"), ("君の知らない物語", "supercell"), ("打上花火", "DAOKO × 米津玄師"), ("前前前世", "RADWIMPS"),
        ("SPECIALZ", "King Gnu"), ("青のすみか", "キタニタツヤ"), ("勇者", "YOASOBI"), ("晴る", "ヨルシカ"), ("オトノケ", "Creepy Nuts"),
        ("IRIS OUT", "米津玄師"), ("メリッサ", "ポルノグラフィティ"), ("めぐりあい", "井上大輔"), ("Get Wild", "TM NETWORK"),
        ("君が好きだと叫びたい", "BAAD"), ("世界が終るまでは…", "WANDS"), ("CAT'S EYE", "杏里"), ("さんぽ", "井上あずみ"),
    ],
    "citypop": [
        ("プラスティック・ラブ", "竹内まりや"), ("真夜中のドア〜stay with me", "松原みき"), ("夜空のイヤリング", "八神純子"),
        ("二人だけのセレモニー", "岡田有希子"), ("みずいろの雨", "八神純子"), ("RIDE ON TIME", "山下達郎"), ("クリスマス・イブ", "山下達郎"),
        ("SPARKLE", "山下達郎"), ("悲しみがとまらない", "杏里"), ("オリビアを聴きながら", "杏里"), ("都会", "大貫妙子"), ("4:00A.M.", "大貫妙子"),
        ("少女A", "中森明菜"), ("飾りじゃないのよ涙は", "中森明菜"), ("DESIRE -情熱-", "中森明菜"), ("September", "竹内まりや"),
        ("駅", "竹内まりや"), ("不思議なピーチパイ", "竹内まりや"), ("Mr.サマータイム", "サーカス"), ("Telephone Number", "大橋純子"),
        ("シルエット・ロマンス", "大橋純子"), ("恋人がサンタクロース", "松任谷由実"), ("中央フリーウェイ", "荒井由実"),
        ("やさしさに包まれたなら", "荒井由実"), ("ルージュの伝言", "荒井由実"), ("君は天然色", "大滝詠一"), ("恋するカレン", "大滝詠一"),
        ("Woman \"Wの悲劇\"より", "薬師丸ひろ子"), ("くちびるNetwork", "岡田有希子"), ("Midnight Pretenders", "亜蘭知子"),
    ],
    "other": [
        ("丸の内サディスティック", "椎名林檎"), ("ギブス", "椎名林檎"), ("本能", "椎名林檎"), ("Lemon", "米津玄師"), ("感電", "米津玄師"),
        ("夜に駆ける", "YOASOBI"), ("群青", "YOASOBI"), ("名もなき詩", "Mr.Children"), ("innocent world", "Mr.Children"),
        ("First Love", "宇多田ヒカル"), ("Automatic", "宇多田ヒカル"), ("花束を君に", "宇多田ヒカル"), ("川の流れのように", "美空ひばり"),
        ("愛燦燦", "美空ひばり"), ("津軽海峡・冬景色", "石川さゆり"), ("天城越え", "石川さゆり"), ("北国の春", "千昌夫"),
        ("千本桜", "黒うさP"), ("ロキ", "みきとP"), ("メルト", "ryo (supercell)"), ("砂の惑星", "ハチ"), ("マリーゴールド", "あいみょん"),
        ("裸の心", "あいみょん"), ("白日", "King Gnu"), ("Pretender", "Official髭男dism"), ("Subtitle", "Official髭男dism"),
        ("ドライフラワー", "優里"), ("うっせぇわ", "Ado"), ("唱", "Ado"), ("天体観測", "BUMP OF CHICKEN"), ("小さな恋のうた", "MONGOL800"),
        ("ハナミズキ", "一青窈"), ("涙そうそう", "夏川りみ"), ("さくら(独唱)", "森山直太朗"), ("世界に一つだけの花", "SMAP"),
        ("TSUNAMI", "サザンオールスターズ"), ("ロビンソン", "スピッツ"), ("春よ、来い", "松任谷由実"), ("雪の華", "中島美嘉"),
        ("ライラック", "Mrs. GREEN APPLE"), ("晩餐歌", "tuki."), ("アイネクライネ", "米津玄師"),
    ],
}


def songs(folder):
    out = [{"id": f"{kind}-{i + 1:02d}", "kind": kind, "title": title, "artist": artist}
           for kind, listed in SONGS.items() for i, (title, artist) in enumerate(listed)]
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "songs.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print(f"{len(out)} songs in {folder / 'songs.json'}: " + ", ".join(f"{k} {len(v)}" for k, v in SONGS.items()))


# Kanji forms and kana JMdict marks as rarely used, irregular, outdated or for search only: not how lyrics write or sing a word.
ODD_FORMS = {"iK", "oK", "sK"}
ODD_KANA = {"ik", "ok", "rk", "sk"}


def listed_candidates(words):
    """{form: kana} for each JMdict form of two kanji or more, starting with one, that it reads one way only."""
    readings = defaultdict(set)
    for entry in words:
        for kanji in entry["kanji"]:
            form = kanji["text"]
            if set(kanji["tags"]) & ODD_FORMS:
                continue
            readings[form] |= {hiragana(k["text"]) for k in entry["kana"]
                               if ("*" in k["appliesToKanji"] or form in k["appliesToKanji"]) and not set(k["tags"]) & ODD_KANA}
    return {form: next(iter(kana)) for form, kana in sorted(readings.items())
            if len(kana) == 1 and is_han(form[0]) and sum(map(is_han, form)) >= 2
            and all(is_han(c) or "ぁ" <= c <= "ゖ" or "ァ" <= c <= "ヶ" or c == "ー" for c in form)}


JMDICT_URL = "https://www.edrdg.org/dokuwiki/doku.php?id=wiki_index.php_jmdict-edict_dictionary_project"
EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html"
CC_BY_SA_URL = "https://creativecommons.org/licenses/by-sa/4.0/"


def table_header(date, version):
    """The table's header: where its words come from, their licence and how they were changed, as CC BY-SA 4.0 asks."""
    return [f"# JMdict {date}, by the Electronic Dictionary Research and Development Group, {JMDICT_URL}",
            f"# through jmdict-simplified {version}. Licensed under CC BY-SA 4.0, {CC_BY_SA_URL}",
            f"# as the Group's licence asks, {EDRDG_LICENCE_URL}",
            "# Changed: only words of two kanji or more it reads one way only, kept where Crosstune reads them otherwise.",
            "# Made by scripts/readingsaudit/readingsaudit.py table."]


def old_kana(words):
    """The words in kana a word in old kana may be today, {kana: lookup}, and the words in kana with a ひ or ふ after their first kana.

    The first are JMdict's common words with an い or う after their first kana, おもいで, but adjectives, each looked up as its usual form,
    思い出, or as its kana when it's usually written so or JMdict has more than one common word for it. The second are
    every word JMdict has that's written so today, おひる or くふう, not archaic: written there, it's no old spelling.
    """
    looked_up = defaultdict(set)
    modern = set()
    for entry in words:
        senses = entry.get("sense", [])
        kana_usually = any("uk" in sense.get("misc", []) for sense in senses)
        archaic = bool(senses) and all(set(sense.get("misc", [])) & {"arch", "obs"} for sense in senses)
        for kana in entry["kana"]:
            text = kana["text"]
            if set(kana["tags"]) & ODD_KANA or not text or not all("ぁ" <= c <= "ゖ" for c in text):
                continue
            if any(c in "ひふ" for c in text[1:]) and not archaic:
                modern.add(text)
            # An adjective's い was never ひ, 古い is ふるし: ふるひ is 降る日.
            adjective = any("adj-i" in sense.get("partOfSpeech", []) for sense in senses)
            if kana.get("common") and any(c in "いう" for c in text[1:]) and not adjective:
                forms = [k["text"] for k in entry["kanji"] if not set(k["tags"]) & ODD_FORMS
                         and ("*" in kana["appliesToKanji"] or k["text"] in kana["appliesToKanji"])]
                common = [k["text"] for k in entry["kanji"] if k.get("common") and k["text"] in forms]
                looked_up[text].add(text if kana_usually or not forms else (common or forms)[0])
    return {kana: next(iter(lookups)) if len(lookups) == 1 else kana for kana, lookups in sorted(looked_up.items())}, sorted(modern)


def old_kana_header(date, version):
    """old-kana.txt's header: where its words come from, their licence and how they were changed, as CC BY-SA 4.0 asks."""
    return [f"# JMdict {date}, by the Electronic Dictionary Research and Development Group, {JMDICT_URL}",
            f"# through jmdict-simplified {version}. Licensed under CC BY-SA 4.0, {CC_BY_SA_URL}",
            f"# as the Group's licence asks, {EDRDG_LICENCE_URL}",
            "# Changed: only the common words in kana with い or う after their first kana, each with how it's looked up,",
            "# then the words in kana with ひ or ふ after their first kana, which are no old spelling.",
            "# Made by scripts/readingsaudit/readingsaudit.py oldkana."]


def oldkana(folder):
    """Writes the app's old-kana.txt from the JMdict in the folder: see old_kana."""
    jmdict = json.loads((folder / "jmdict.json").read_text())
    no_downgrade(jmdict["dictDate"], OLD_KANA)
    today, modern = old_kana(jmdict["words"])
    header = old_kana_header(jmdict["dictDate"], jmdict["version"])
    OLD_KANA.write_text("\n".join(header + [f"{kana}\t{lookup}" for kana, lookup in today.items()] + modern) + "\n")
    print(f"{len(today)} words today and {len(modern)} modern words in {OLD_KANA}")


OLD_KANA = ROOT / "app/src/main/resources/com/astrovm/crosstune/old-kana.txt"


def table(folder, gradle=None):
    """The candidates for the app's jmdict-readings.txt, for the test that keeps those the app reads otherwise."""
    jmdict = json.loads((folder / "jmdict.json").read_text())
    no_downgrade(jmdict["dictDate"], TABLE)
    candidates = listed_candidates(jmdict["words"])
    header = table_header(jmdict["dictDate"], jmdict["version"])
    (folder / "listed.txt").write_text("\n".join(header + [f"{form}\t{kana}" for form, kana in candidates.items()]) + "\n")
    print(f"{len(candidates)} candidates in {folder / 'listed.txt'}")
    run(folder, "com.astrovm.crosstune.songcheck.JmdictReadings", gradle)


# How Gradle runs unless --gradle says otherwise: in the build box, which keeps a machine's builds in turn.
GRADLE_BOX = f"{ROOT / 'scripts/gradle-box/gradle-box.sh'} --max-workers=2"


def run(folder, test="com.astrovm.crosstune.songcheck.ReadingsAudit", gradle=None):
    # gradle-box mounts SONGCHECK_DIR, so the words stay out of the checkout.
    env = {**os.environ, "SONGCHECK_DIR": str(folder)}
    command = ["nice", "-n", "10", *shlex.split(gradle or GRADLE_BOX), ":app:songCheck",
               f"-PsongcheckDir={folder}", f"-PsongcheckTest={test}"]
    sys.exit(subprocess.call(command, env=env, cwd=ROOT))


# ---- fetch and age ----

JMDICT_RELEASES = "https://api.github.com/repos/scriptin/jmdict-simplified/releases"
# All of jmdict-eng, not jmdict-eng-common: the tables need the words that aren't common too.
JMDICT_ASSET = re.compile(r"jmdict-eng-\d[^/]*\.json\.zip")


# jmdict-simplified's tags: its version, then when it was made. Nothing else reaches a URL or the workflow.
TAG_FORM = re.compile(r"\d+\.\d+\.\d+\+\d{14}")
# Seconds to wait on GitHub for each try.
TIMEOUT = 60
TRIES = 3
# The zip is about 12 MB and its JSON about 120 MB: room to grow, but no download or unzip without end.
MAX_ZIP_BYTES = 64 * 1024 * 1024
MAX_JSON_BYTES = 512 * 1024 * 1024


def with_tries(what, call):
    """call(), tried again after 2 s and 4 s when the network or GitHub fails; a missing page fails at once."""
    for attempt in range(1, TRIES + 1):
        try:
            return call()
        except urllib.error.HTTPError as error:
            if error.code < 500 and error.code != 429:
                raise SystemExit(f"{what}: {error.code} {error.reason}")
            failed = error
        except (urllib.error.URLError, OSError) as error:
            failed = error
        if attempt == TRIES:
            raise SystemExit(f"{what}: failed {TRIES} tries, the last with {failed}")
        time.sleep(2 ** attempt)


def github_json(url, opener):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "crosstune-readingsaudit"}
    # A runner's address shares GitHub's limit for anonymous calls with every other job on it.
    if os.environ.get("GH_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['GH_TOKEN']}"

    def ask():
        with opener(urllib.request.Request(url, headers=headers), timeout=TIMEOUT) as answer:
            return json.load(answer)
    return with_tries(url, ask)


def checked_tag(tag):
    if not TAG_FORM.fullmatch(tag):
        raise SystemExit(f"{tag!r} isn't a jmdict-simplified release tag, like 3.6.2+20261109214828")
    return tag


def unzip_json(data, name, out):
    """Writes the zip's one JSON file to [out], if the zip says it is no bigger than MAX_JSON_BYTES.

    zipfile unzips no more than the size the zip gives, and fails on the CRC when the file is longer.
    """
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            members = [m for m in archive.infolist() if m.filename.endswith(".json")]
            if len(members) != 1:
                raise SystemExit(f"{name} holds {len(members)} JSON files, not one")
            if members[0].file_size > MAX_JSON_BYTES:
                raise SystemExit(f"{name} unzips to more than {MAX_JSON_BYTES} bytes")
            part = out.with_name(out.name + ".part")
            try:
                with archive.open(members[0]) as source, part.open("wb") as target:
                    shutil.copyfileobj(source, target)
            except BaseException:
                part.unlink(missing_ok=True)
                raise
            part.replace(out)
    except zipfile.BadZipFile as error:
        raise SystemExit(f"{name} doesn't unzip: {error}") from None


def fetch(folder, tag=None, opener=urllib.request.urlopen):
    """Puts jmdict-eng of the newest jmdict-simplified release, or of [tag], in the folder as jmdict.json.

    The file is taken by its release's tag, and must have the size and SHA-256 the release gives for it.
    """
    url = f"{JMDICT_RELEASES}/latest" if tag is None else f"{JMDICT_RELEASES}/tags/{urllib.parse.quote(checked_tag(tag), safe='')}"
    release = github_json(url, opener)
    tag = checked_tag(release["tag_name"])
    assets = [a for a in release["assets"] if JMDICT_ASSET.fullmatch(a["name"])]
    if len(assets) != 1:
        raise SystemExit(f"release {tag} has {len(assets)} jmdict-eng zips, not one: {[a['name'] for a in release['assets']]}")
    asset = assets[0]
    expected = asset.get("digest") or ""
    if not expected.startswith("sha256:"):
        raise SystemExit(f"release {tag} gives no SHA-256 for {asset['name']}, so it can't be checked")
    if asset["size"] > MAX_ZIP_BYTES:
        raise SystemExit(f"release {tag} says {asset['name']} is {asset['size']} bytes, bigger than {MAX_ZIP_BYTES}")
    download = asset["browser_download_url"]
    # Compared decoded, as the + in a tag may come written as it is or as %2B.
    pinned = f"https://github.com/scriptin/jmdict-simplified/releases/download/{tag}/"
    if not urllib.parse.unquote(download).startswith(pinned):
        raise SystemExit(f"{asset['name']} isn't under release {tag}: {download}")

    def get():
        request = urllib.request.Request(download, headers={"User-Agent": "crosstune-readingsaudit"})
        with opener(request, timeout=TIMEOUT) as answer:
            # One byte past the size is enough to know it's too long.
            return answer.read(asset["size"] + 1)
    data = with_tries(download, get)
    if len(data) > asset["size"]:
        raise SystemExit(f"{asset['name']} is {len(data)} bytes or more, release {tag} says {asset['size']}")
    if len(data) != asset["size"]:
        raise SystemExit(f"{asset['name']} is {len(data)} bytes, release {tag} says {asset['size']}")
    got = "sha256:" + hashlib.sha256(data).hexdigest()
    if got != expected:
        raise SystemExit(f"{asset['name']} has {got}, release {tag} says {expected}")
    folder.mkdir(parents=True, exist_ok=True)
    # Written by its own name, whatever path the zip gives it.
    unzip_json(data, asset["name"], folder / "jmdict.json")
    print(f"jmdict-simplified {tag}, {asset['name']}, {got}, in {folder / 'jmdict.json'}")
    # The workflow names the tag in its pull request; it has passed TAG_FORM, so it holds no newline.
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"tag={tag}\n")
    return tag


TABLE = ROOT / "app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt"
# The JMdict workflow remakes the tables each month. Three months is three runs missed, or their pull
# requests left open: long enough for a month that needs a reading looked at by hand, short enough that
# the update the EDRDG licence asks for is a regular one.
MAX_AGE_DAYS = 90


def header_date(path):
    """The JMdict date in a table's first line, `# JMdict 2026-11-09, by ...`."""
    found = re.match(r"# JMdict (\d{4}-\d{2}-\d{2}),", path.read_text().split("\n", 1)[0])
    if not found:
        raise SystemExit(f"{path} has no JMdict date in its first line")
    try:
        return datetime.date.fromisoformat(found.group(1))
    except ValueError:
        raise SystemExit(f"{path} has a JMdict date that is no date: {found.group(1)}") from None


def no_downgrade(dict_date, committed):
    """Stops a remake from an older JMdict than [committed] has: the licence asks for the newest version."""
    if committed.exists() and datetime.date.fromisoformat(dict_date) < header_date(committed):
        raise SystemExit(f"JMdict {dict_date} is older than the {header_date(committed)} of {committed.name}."
                         " The EDRDG licence asks for its newest version, so a remake never goes back;"
                         " pick a newer jmdict-simplified release")


def age(paths, today, max_days=MAX_AGE_DAYS):
    """1 when a table's JMdict is more than [max_days] older than [today], else 0, with each one's age."""
    stale = False
    for path in paths:
        days = (today - header_date(path)).days
        print(f"{path.name}: JMdict {header_date(path)}, {days} days old")
        if days > max_days:
            stale = True
            print(f"{path.name} is older than {max_days} days. Merge the JMdict workflow's pull request,"
                  " or run that workflow (Actions → JMdict → Run workflow)")
    return 1 if stale else 0


# ---- compare ----

def hiragana(text):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


VOWEL_ROWS = {v: set(row) for v, row in {
    "あ": "あかがさざただなはばぱまやゃらわぁ", "い": "いきぎしじちぢにひびぴみりぃ", "う": "うくぐすずつづぬふぶぷむゆゅるぅ",
    "え": "えけげせぜてでねへべぺめれぇ", "お": "おこごそぞとどのほぼぽもよょろをぉ"}.items()}


def norm(kana):
    """Kana as compared: hiragana, a long-vowel mark as the vowel it draws out, づ and ぢ as ず and じ, no spaces or marks."""
    out = []
    for c in hiragana(kana).replace("づ", "ず").replace("ぢ", "じ"):
        if c == "ー" and out:
            out.append(next((v for v, row in VOWEL_ROWS.items() if out[-1] in row), out[-1]))
        elif c.isalnum() or c in "ゝゞ":
            out.append(c)
    return "".join(out)


def is_han(c):
    return "一" <= c <= "鿿" or "㐀" <= c <= "䶿" or c in "々〆ヶ"


def spans(parts):
    """[(start, end, reading)] of a line's parts, kana and the rest read as written."""
    at, out = 0, []
    for text, reading in parts:
        out.append((at, at + len(text), reading if reading is not None else text))
        at += len(text)
    return out


def truth_units(line, truth):
    """UtaTen's kanji in our line, in order: (start, end, readings it may mean)."""
    units, at = [], 0
    for text, reading in truth:
        if reading is None or not any(map(is_han, text)):
            continue
        found = line.find(text, at)
        if found < 0:
            continue
        after = line[found + len(text):]
        r = hiragana(reading)
        # Its kana may run on into those written after the kanji, ごし for 越 in 越し.
        options = {r} | {r[:k] for k in range(1, len(r)) if hiragana(after).startswith(r[k:])}
        units.append((found, found + len(text), options))
        at = found + len(text)
    return units


def reading_over(ours, line, start, end):
    """Our reading over line[start:end], widened to where our parts begin and end: (start, end, kana)."""
    over = [s for s in ours if s[1] > start and s[0] < end]
    if not over:
        return None
    return over[0][0], over[-1][1], "".join(s[2] for s in over)


def compare_line(line, parts, units):
    """Each of UtaTen's kanji in the line: (word as written, ours, theirs, right?), over the stretch both split alike."""
    ours = spans(parts)
    out = []
    for start, end, options in units:
        got = reading_over(ours, line, start, end)
        if got is None:
            continue
        a, b, kana = got
        # Theirs over the same stretch: their reading for the kanji, the line's kana around it, and
        # any other of their kanji inside it; a kanji in it they don't read can't be compared.
        others = [u for u in units if u[0] >= a and u[1] <= b and u[0] != start]
        covered = {i for u in others for i in range(u[0], u[1])} | set(range(start, end))
        if any(is_han(line[i]) and i not in covered for i in range(a, b)):
            continue
        def theirs(option):
            text, i = "", a
            while i < b:
                if i == start:
                    text += option
                    i = end
                    continue
                other = next((u for u in others if u[0] == i), None)
                if other:
                    text += min(other[2], key=len)
                    i = other[1]
                else:
                    text += line[i]
                    i += 1
            return text
        wanted = [theirs(o) for o in options]
        right = any(norm(w) == norm(kana) for w in wanted)
        out.append((line[a:b], kana, max(wanted, key=len), right, (a, b)))
    return out


SUDACHI = """
import json, sys
from sudachipy import Dictionary
tokenizer = Dictionary(dict="full").create()
print(json.dumps([[[m.surface(), m.reading_form()] for m in tokenizer.tokenize(line)] for line in json.load(sys.stdin)], ensure_ascii=False))
"""


def sudachi(folder, lines):
    """Each line as Sudachi reads it, [[surface, reading]], or None without its venv."""
    python = folder / "venv/bin/python"
    if not python.exists() or not lines:
        return None
    answer = subprocess.run([str(python), "-I", "-c", SUDACHI], input=json.dumps(lines), capture_output=True, text=True, check=True)
    return json.loads(answer.stdout)


def load_jmdict(folder):
    path = folder / "jmdict.json"
    if not path.exists():
        return None
    words = defaultdict(set)
    for entry in json.loads(path.read_text())["words"]:
        kana = {hiragana(k["text"]) for k in entry["kana"]}
        for kanji in entry["kanji"]:
            text = kanji["text"]
            words[text] |= kana
            # The kanji of a word written with kana after them, 行く ゆく, read without those: 行 ゆ.
            stem = re.sub("[ぁ-ゖ]+$", "", text)
            tail = hiragana(text[len(stem):])
            stem_only = stem and all(map(is_han, stem))
            if tail and stem_only:
                words[stem] |= {k[:-len(tail)] for k in kana if k.endswith(tail) and len(k) > len(tail)}
    return words


def cause(word, shown, right, jmdict, line):
    """A first guess at why [shown] isn't [right], from the word and the dictionary; refined by hand."""
    kanji = "".join(c for c in word if is_han(c))
    if any(c.isdigit() for c in word) or re.search("[一二三四五六七八九十百千万]", kanji):
        return "numbers and counters"
    listed = jmdict.get(word) if jmdict is not None else None
    if listed is not None:
        if norm(right) in {norm(k) for k in listed} and norm(shown) in {norm(k) for k in listed}:
            return "another reading of the same word"
        if norm(right) in {norm(k) for k in listed}:
            return "wrong reading, dictionary knows the right one"
    if jmdict is not None and not any(norm(right) in {norm(k) for k in jmdict.get(w, ())} for w in {word, kanji}):
        return "sung as another word (ruby only)"
    return "other"


def checked(audit):
    """Each word UtaTen reads in each song's lines, by reader: whether ours is right, and what it is."""
    out = {}
    for path in sorted(audit.glob("*.json")):
        song = json.loads(path.read_text())
        for line in song.get("lines") or []:
            if not line.get("truth"):
                continue
            units = truth_units(line["text"], line["truth"])
            for reader in ("builtIn", "uniDic", "shown"):
                for word, shown, right, ok, at in compare_line(line["text"], line[reader], units):
                    out[(reader, song["id"], line["text"], at)] = (ok, word, norm(shown), norm(right))
    return out


def changed(folder, before):
    """Words right before and wrong now, and the other way, by reader, from the run kept in [before]."""
    old, new = checked(folder / before / "."), checked(folder / "audit")
    for reader in ("builtIn", "uniDic", "shown"):
        keys = [k for k in new if k[0] == reader and k in old]
        broke = [new[k] for k in keys if old[k][0] and not new[k][0]]
        mended = [new[k] for k in keys if not old[k][0] and new[k][0]]
        print(f"{reader}: {len(mended)} wrong before and right now, {len(broke)} right before and wrong now, of {len(keys)} words in both")
        for ok, word, shown, right in broke:
            print(f"  now wrong: {word} {shown}→{right}")


def compare(folder, words, before=None):
    if before:
        changed(folder, before)
    jmdict = load_jmdict(folder)
    totals = defaultdict(Counter)
    wrong = defaultdict(list)
    found = defaultdict(Counter)
    missing = defaultdict(list)
    unruled = []
    for path in sorted((folder / "audit").glob("*.json")):
        song = json.loads(path.read_text())
        kind = song["kind"]
        found[kind]["songs"] += 1
        name = f"{song['title']} / {song['artist']}"
        if song.get("lyrics") != "found":
            missing["no lyrics"].append(name)
            found[kind]["no lyrics"] += 1
            continue
        if not song.get("timed"):
            missing["no timing"].append(name)
            found[kind]["no timing"] += 1
        if song.get("chords") != "found":
            missing["no chords"].append(name)
            found[kind]["no chords"] += 1
        if song.get("script") != "JAPANESE":
            found[kind]["not Japanese"] += 1
            continue
        if not song.get("utaten"):
            missing["no UtaTen page found by the app"].append(name)
            found[kind]["no UtaTen"] += 1
        # A chorus sung again is counted once.
        seen = set()
        for line in song["lines"]:
            if line["text"] in seen:
                continue
            seen.add(line["text"])
            if not line.get("truth"):
                # A line that gives its readings in brackets, 瞬間（とき）, is read as it says, which no analyzer knows.
                if any(map(is_han, line["text"])) and not re.search("[（(][ぁ-ゖァ-ヺー]+[）)]", line["text"]):
                    unruled.append((kind, name, line))
                continue
            units = truth_units(line["text"], line["truth"])
            for reader in ("builtIn", "uniDic", "shown"):
                for word, shown, right, ok, at in compare_line(line["text"], line[reader], units):
                    totals[(kind, reader)]["words"] += 1
                    if not ok:
                        totals[(kind, reader)]["wrong"] += 1
                        wrong[reader].append({"word": word, "shown": shown, "right": right, "song": name, "line": line["text"],
                                              "cause": cause(word, shown, right, jmdict, line["text"])})
    read = sudachi(folder, [line["text"] for _, _, line in unruled]) or []
    for (kind, name, line), theirs in zip(unruled, read):
        units, at = [], 0
        for surface, reading in theirs:
            if any(map(is_han, surface)) and reading:
                units.append((at, at + len(surface), {hiragana(reading)}))
            at += len(surface)
        for word, shown, right, ok, _ in compare_line(line["text"], line["shown"], units):
            totals[(kind, "sudachi")]["words"] += 1
            if not ok:
                totals[(kind, "sudachi")]["wrong"] += 1
                wrong["sudachi"].append({"word": word, "shown": shown, "right": right, "song": name, "line": line["text"],
                                         "cause": cause(word, shown, right, jmdict, line["text"])})
    print("songs per kind:", {k: dict(v) for k, v in found.items()})
    for reader in ("builtIn", "uniDic", "shown", "sudachi"):
        words_all = sum(totals[(k, reader)]["words"] for k in SONGS)
        wrong_all = sum(totals[(k, reader)]["wrong"] for k in SONGS)
        per = ", ".join(f"{k} {totals[(k, reader)]['wrong']}/{totals[(k, reader)]['words']}" for k in SONGS)
        print(f"{reader}: {wrong_all} wrong of {words_all} words ({per})")
    for reader in ("builtIn", "uniDic", "shown", "sudachi"):
        print(f"\n== {reader}: by cause")
        by_cause = defaultdict(Counter)
        for w in wrong[reader]:
            by_cause[w["cause"]][(w["word"], norm(w["shown"]), norm(w["right"]))] += 1
        for name, counted in sorted(by_cause.items(), key=lambda kv: -sum(kv[1].values())):
            print(f"{name}: {sum(counted.values())}  " + "  ".join(f"{w} {s}→{r}" + (f"×{n}" if n > 1 else "") for (w, s, r), n in counted.most_common(8)))
    (folder / "wrong.json").write_text(json.dumps(wrong, ensure_ascii=False, indent=1))
    (folder / "missing.json").write_text(json.dumps(missing, ensure_ascii=False, indent=1))
    print(f"\n{folder / 'wrong.json'}, {folder / 'missing.json'}")
    print({k: len(v) for k, v in missing.items()})
    if words:
        for w in wrong["shown"]:
            print(f"{w['cause']} | {w['word']} {norm(w['shown'])}→{norm(w['right'])} | {w['song']} | {w['line']}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("step", choices=["songs", "run", "compare", "table", "oldkana", "fetch", "age"])
    parser.add_argument("--words", action="store_true", help="compare: list every wrong word shown, with its song and line")
    parser.add_argument("--before", help="compare: a folder in the data folder with an earlier run's audit/, to count what changed")
    parser.add_argument("--gradle", help="run, table: the Gradle command, instead of gradle-box")
    parser.add_argument("--tag", help="fetch: this jmdict-simplified release instead of the newest")
    args = parser.parse_args(argv)
    folder = data_dir()
    if args.step == "songs":
        songs(folder)
    elif args.step == "run":
        run(folder, gradle=args.gradle)
    elif args.step == "table":
        table(folder, args.gradle)
    elif args.step == "oldkana":
        oldkana(folder)
    elif args.step == "fetch":
        fetch(folder, args.tag)
    elif args.step == "age":
        sys.exit(age([TABLE, OLD_KANA], datetime.date.today()))
    else:
        compare(folder, args.words, args.before)


if __name__ == "__main__":
    main()
