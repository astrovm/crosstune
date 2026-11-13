"""How scripts/matchcheck/replay.py reads what collect.py kept: YouTube Music's results as the app reads
them, and which of them are the song.

The SongNames functions mirrored here judge what's right, so they stay as they were written, even
where the app's rules change.
"""
import re
import unicodedata

VERSION_WORDS = {
    "cover", "covers", "covered", "karaoke", "live", "unplugged", "mtv", "vivo", "directo", "session", "sessions",
    "concert", "tour", "remix", "mix", "instrumental", "inst", "acoustic", "acustico", "piano", "guitar",
    "bass", "drum", "drums", "chords", "tab", "tabs", "tutorial", "lesson", "practice", "reaction", "slowed",
    "reverb", "sped", "nightcore", "8d", "demo", "medley", "mashup", "amv", "mmd", "ver", "tribute", "ktv",
    "acapella", "acappella", "cappella", "capella", "8bit", "orgel", "musicbox",
}
# SongNames.VERSION_MARKS: each mark, and the Latin words that mean it, which the song's own name may say.
VERSION_MARKS = {
    "カラオケ": {"karaoke"}, "ライブ": {"live"}, "弾き語り": set(), "歌ってみた": {"cover"}, "演奏してみた": {"cover"},
    "弾いてみた": {"cover"}, "叩いてみた": {"cover"}, "ピアノ": {"piano"}, "ギター": {"guitar"}, "カバー": {"cover"},
    "メドレー": {"medley"}, "練習": {"practice"}, "オルゴール": {"orgel", "musicbox"}, "アカペラ": {"acapella", "cappella"},
    "アコースティック": {"acoustic"}, "翻唱": {"cover"}, "伴奏": {"instrumental", "karaoke"}, "伴唱": {"karaoke"},
    "卡拉ok": {"karaoke"}, "커버": {"cover"}, "라이브": {"live"}, "노래방": {"karaoke"},
    "串烧": {"medley"}, "串燒": {"medley"}, "메들리": {"medley"},
    "offvocal": {"instrumental", "karaoke"}, "tvsize": set(), "オフボーカル": {"instrumental", "karaoke"},
    "インスト": {"inst", "instrumental"}, "tvサイズ": set(), "テレビサイズ": set(),
    "纯音乐": {"instrumental"}, "純音樂": {"instrumental"}, "인스트": {"inst", "instrumental"}, "반주": {"instrumental", "karaoke"},
    "リミックス": {"remix"}, "混音": {"remix"}, "리믹스": {"remix"}, "演唱会": {"live", "concert"}, "演唱會": {"live", "concert"},
    "オフヴォーカル": {"instrumental", "karaoke"}, "ライヴ": {"live"}, "カヴァー": {"cover"},
}
# Marks that count only with no katakana beside them, as "アライヴ" is "Alive".
WORD_OF_ITS_OWN = {"ライヴ", "カヴァー"}
# Words that only hold a mark's letters, as "ドライブ" holds "ライブ", where a word starts.
HOLD_A_MARK = re.compile("(?<![\u30a1-\u30fa\u30fc-\u30ff\u1100-\u11ff\u3130-\u318f\uac00-\ud7af])"
                         "(?:インストール|ドライブ|ライブラリ|ディスカバー|リカバー|드라이브|라이브러리|디스커버|커버리지|반주자)")
ARTIST_SPLIT = re.compile(r"\s*(?:,|&|、|×|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\band\b)\s*", re.IGNORECASE)
BRACKETS = re.compile(r"\s*[(\[（【「『].*?[)\]）】」』]\s*")


def normalize(text):
    folded = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))
    return "".join(c for c in folded.lower() if c.isalnum())


def artists(text):
    return {normalize(part) for part in ARTIST_SPLIT.split(text) if normalize(part)}


def composed(text):
    """SongNames.composed: NFKC and lowercase, with its sound marks kept."""
    return unicodedata.normalize("NFKC", text).lower()


def with_bu(word):
    """SongNames.withBu: a katakana word with ヴ as ブ, as katakana writes a v either way: ライヴ is ライブ."""
    for vu, bu in (("ヴァ", "バ"), ("ヴィ", "ビ"), ("ヴェ", "ベ"), ("ヴォ", "ボ"), ("ヴ", "ブ")):
        word = word.replace(vu, bu)
    return word


def in_katakana(c):
    """SongNames.Mark.inKatakana: a katakana letter or ー."""
    return c is not None and ("\u30a1" <= c <= "\u30fa" or "\u30fc" <= c <= "\u30ff")


def mark_found(mark, name):
    """SongNames.Mark.foundIn: the mark's letters in a row, anything but letters and digits between them; one in Latin
    letters only as a word of its own."""
    text = HOLD_A_MARK.sub(" ", composed(name))
    letters = [c for c in composed(mark) if c.isalnum()]
    pattern = r"(?:[^\w]|_)*".join(re.escape(c) for c in letters)
    a_word = all("LATIN" in unicodedata.name(c, "") for c in letters)

    def goes_on(c):
        return c is not None and ("LATIN" in unicodedata.name(c, "") or c.isdigit() or unicodedata.category(c) == "Mn")
    for m in re.finditer(pattern, text):
        before = text[m.start() - 1] if m.start() > 0 else None
        after = text[m.end()] if m.end() < len(text) else None
        if a_word:
            if not goes_on(before) and not goes_on(after):
                return True
        elif mark not in WORD_OF_ITS_OWN or not in_katakana(before) and not in_katakana(after):
            return True
    return False


# SongNames.tagEdge, VERSION_SAID and VERSION_WRITTEN: where a tag ends, and the words that say a name is a version.
TAG_EDGE = re.compile(r"[()\[\]{}【】「」『』〈〉《》~〜|/_]|\s[-–]\s")
VERSION_SAID = {"ver": "version", "version": "version", "inst": "instrumental", "instrumental": "instrumental"}
VERSION_WRITTEN = {"バージョン": "version", "ヴァージョン": "version", "버전": "version"}
# SongNames.KATAKANA_WORDS: words a version tag says in katakana, with ヴ as ブ, and the same words in Latin letters.
KATAKANA_WORDS = {
    "シングル": "single", "アルバム": "album", "オリジナル": "original", "ラジオ": "radio", "エディット": "edit",
    "ショート": "short", "ロング": "long", "フル": "full", "スロー": "slow", "ライブ": "live", "リミックス": "remix",
    "ミックス": "mix", "リマスター": "remaster", "リマスタード": "remastered", "モノラル": "mono", "モノ": "mono",
    "ステレオ": "stereo", "アコースティック": "acoustic", "ピアノ": "piano", "ギター": "guitar", "オーケストラ": "orchestra",
    "バンド": "band", "デモ": "demo", "インスト": "instrumental", "インストゥルメンタル": "instrumental", "カラオケ": "karaoke",
    "スピードアップ": "speed up", "エクステンデッド": "extended", "イングリッシュ": "english", "ジャパニーズ": "japanese",
    "ムービー": "movie", "テレビ": "tv", "ドラマ": "drama", "アニメ": "anime", "アカペラ": "acapella", "ボーカル": "vocal",
    "オフ": "off", "カバー": "cover", "セルフ": "self", "スペシャル": "special", "ニュー": "new", "スタジオ": "studio",
    "リレコーディング": "re recording", "ダンス": "dance", "アンプラグド": "unplugged",
}


def version_like():
    """SongNames.VERSION_LIKE: the words a tag that says only what version it is has."""
    katakana = set().union(*(set(re.split(r"[^a-z0-9]+", means)) - {""} for means in KATAKANA_WORDS.values()))
    return VERSION_WORDS | set(VERSION_SAID) | katakana | {"size"}


def latin_words(text):
    """SongNames.words: the words in Latin letters and digits, widths folded and accents off."""
    folded = "".join(c for c in unicodedata.normalize("NFD", unicodedata.normalize("NFKC", text)) if not unicodedata.combining(c))
    return set(re.split(r"[^a-z0-9]+", folded.lower())) - {""}


def canon(word):
    """SongNames.canon: "ver" as "version", "inst" as "instrumental"."""
    return VERSION_SAID.get(word, word)


VERSION_LIKE = version_like()


def version_tags(name):
    """SongNames.versionTags: the parts of a name that say it's a version."""
    return [tag for tag in TAG_EDGE.split(composed(name)) if latin_words(tag) & VERSION_SAID.keys() or any(mark in tag for mark in VERSION_WRITTEN)]


def in_katakana_says(run, tagged):
    """SongNames.inKatakanaSays: the run is words in katakana, each one the song's own tags say."""
    cut = [True] + [False] * len(run)
    for end in range(1, len(run) + 1):
        cut[end] = any(cut[start] and with_bu(run[start:end]) in KATAKANA_WORDS and all(canon(w) in tagged for w in latin_words(KATAKANA_WORDS[with_bu(run[start:end])]))
                       for start in range(end))
    return cut[-1]


def own_runs(text):
    """SongNames.notOwnLetters split: each run of letters of a script but Latin."""
    runs, run = [], ""
    for c in text:
        if c.isalpha() and "LATIN" not in unicodedata.name(c, ""):
            run += c
        else:
            runs.append(run)
            run = ""
    return [r for r in runs + [run] if r]


def all_said(tag, tagged, written):
    """SongNames.allSaid: a tag that says it's a version says nothing the song's own tags don't, each katakana word exactly."""
    if not all(canon(w) in tagged for w in latin_words(tag)):
        return False
    marked = {mark: means for mark, means in VERSION_WRITTEN.items() if mark in tag}
    if any(means not in tagged and not any(normalize(mark) in w for w in written) for mark, means in marked.items()):
        return False
    for mark in marked:
        tag = tag.replace(mark, " ")
    return all(any(normalize(run) in w for w in written) or in_katakana_says(run, tagged) for run in own_runs(tag))


def own_versions(title):
    """SongNames.ownVersions: the title's tags that say only what version it is, "(Live)", not what it's from."""
    bare_one = bare_title(title)
    tail = title[len(bare_one):] if title.startswith(bare_one) else ""
    return [tag for tag in TAG_EDGE.split(tail) if latin_words(tag) and latin_words(tag) <= VERSION_LIKE]


def says_another_version_of(name, titles, artist):
    """SongNames.saysAnotherVersionOf: what all the song's names say together is its own, and a version tag is only
    when all else it says is what the song's own tags say."""
    said = {canon(w) for t in titles for w in latin_words(t)}
    tagged = set()
    for t in titles:
        tagged |= set().union(*(latin_words(tag) for tag in own_versions(t)))
        tagged |= set().union(*(latin_words(tag) for tag in version_tags(t)))
    tagged = {canon(w) for w in tagged}
    written = [normalize(t) for t in titles]
    versions = version_tags(name)
    if any(not all_said(tag, tagged, written) for tag in versions):
        return True
    own = said | latin_words(artist) | set().union(*(latin_words(tag) for tag in versions))
    if any(canon(w) not in said for w in (latin_words(name) - own) & VERSION_WORDS):
        return True
    return any(mark_found(mark, name) and not any(mark_found(mark, t) for t in titles) and not mark_found(mark, artist) and not means & tagged
               for mark, means in VERSION_MARKS.items())


def title_names(name):
    """The name, and each half of "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"."""
    halves = [half for half in re.split(r"\s+[-–—/]\s+|\s*[（(]\s*|\s*[)）]\s*", name) if half.strip()]
    return [name] + halves


def bare(name):
    return normalize(BRACKETS.sub(" ", name))


def rows(answer):
    """YouTube Music's results as the app reads them: the video, the name, the second line split up and the length."""
    found = []

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "musicResponsiveListItemRenderer":
                    found.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(answer or {})
    out = []
    for item in found:
        columns = ["".join(run.get("text", "") for run in col.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs", [])) for col in item.get("flexColumns", [])]
        details = (columns[1] if len(columns) > 1 else "").split(" • ")
        length = None
        parts = details[-1].strip().split(":")
        if 2 <= len(parts) <= 3 and all(p.isdigit() for p in parts):
            length = 0
            for p in parts:
                length = length * 60 + int(p)
            length *= 1000
        out.append({"id": item.get("playlistItemData", {}).get("videoId"), "name": columns[0] if columns else "", "details": details, "lengthMs": length})
    return out


# Results checked by eye that the names alone can't tell are right: China's simplified letters for
# Taiwan's traditional ones, or Korea's name for one the stores give only in Chinese.
RIGHT_BY_EYE = {
    ("YOONA", "덕수궁 돌담길의 봄 Deoksugung Stonewall Walkway (feat. 10cm)"),
    ("Sally Yeh & A-Lin", "爱的可能 (Live)"),
    # The song itself, and its Director's Edit, which Japan's store doesn't name for the US store's id.
    ("Yoko Takahashi", "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"),
    ("Yoko Takahashi", "残酷な天使のテーゼ (Directors Edit Version) - The Cruel Angel's Thesis(Directors Edit Version)"),
    # Named the same, with the Latin name after a dash, which the halves split up.
    ("Kenshi Yonezu", "烏 - Raven - Karasu - Raven"),
    ("Keisuke Kuwata & Yumi Matsutoya", "Kissin' Christmas (クリスマスだからじゃない) 2023 - Kissin' Christmas (Christmas Dakarajanai) 2023"),
    ("Toshiki Kadomatsu", "After 5 Crash / Tokyo Tower / 初恋 / I'll Do My Best - After 5 Crash / Tokyo Tower / Hatsukoi / I'll Do My Best"),
    # Korea's names for the songs the stores give only in Latin letters.
    ("Lee Sun Hee", "아! 옛날이여"),
    ("Lee Sun Hee", "A girl's pray (소녀의 기도)"),
}


TRAILING_TAG = re.compile(r"(?:\s*[(\[][^()\[\]]*[)\]]|\s+[-–]\s+[^-–]*)$")


def bare_title(title):
    """SongNames.bareTitle: without what it adds in brackets or after a dash, at its end."""
    name = title.strip()
    while True:
        shorter = TRAILING_TAG.sub("", name).strip()
        if shorter == name or not shorter:
            return name
        name = shorter

