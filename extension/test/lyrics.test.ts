import assert from "node:assert/strict";
import { test } from "node:test";
import { findLyrics, parseLrc, type Lyrics, type Song } from "../src/lyrics.ts";
import { fixture, internet, type Answer, type Internet } from "./support.ts";

const LRCLIB = "https://lrclib.net/api/search";
const YTM_NEXT = "https://music.youtube.com/youtubei/v1/next";
const YTM_BROWSE = "https://music.youtube.com/youtubei/v1/browse";
const YTM_SEARCH = "https://music.youtube.com/youtubei/v1/search";
const NETEASE_SEARCH = "https://music.163.com/api/search/get";
const NETEASE_LYRIC = "https://music.163.com/api/song/lyric";
const LETRAS_SEARCH = "https://solr.sscdn.co/letras/m1/";
const LETRAS_PAGE = "https://www.letras.com/mercedes-sosa/37544/";

const AFRICA: Song = { title: "Africa", artist: "Toto", lengthMs: 295_000 };
const FIRST_LINE = "Paper boats along the river";

/** Every source, each answering that it has nothing, unless a route before it says otherwise. */
const nothing: [string, Answer][] = [
  [LRCLIB, "[]"],
  [YTM_SEARCH, "{}"],
  [NETEASE_SEARCH, '{"result":{"songs":[]},"code":200}'],
  [LETRAS_SEARCH, 'LetrasSug({"response":{"docs":[]}})'],
  ["https://genius.com/api/search/multi", '{"response":{"sections":[]}}'],
];

const find = (song: Song, net: Internet): Promise<Lyrics> => findLyrics(song, net.fetch, { pause: async () => {} });

function found(lyrics: Lyrics): Extract<Lyrics, { kind: "found" }> {
  assert.equal(lyrics.kind, "found");
  return lyrics as Extract<Lyrics, { kind: "found" }>;
}

test("LRCLIB's words timed for this recording are the answer, and no one else is asked", async () => {
  const net = internet([[LRCLIB, fixture("lrclib-search.json")]]);
  const lyrics = found(await find(AFRICA, net));
  assert.equal(lyrics.source, "lrclib");
  assert.deepEqual(lyrics.lines[0], { timeMs: 10_500, text: FIRST_LINE });
  assert.equal(lyrics.lengthMs, 295_000);
  assert.equal(net.asked.length, 1);
  assert.match(net.asked[0] ?? "", /track_name=Africa&artist_name=Toto/);
});

test("of two timed copies, the one as long as the song playing wins", async () => {
  const net = internet([[LRCLIB, fixture("lrclib-search.json")]]);
  assert.equal(found(await find({ ...AFRICA, lengthMs: 340_000 }, net)).lines[0]?.timeMs, 40_500);
});

test("another artist's song of the same name, or another version, is not this one", async () => {
  const net = internet([[LRCLIB, fixture("lrclib-search.json")], ...nothing]);
  const lyrics = await find({ title: "Africa", artist: "Weezer", lengthMs: 280_000 }, net);
  assert.deepEqual(lyrics, { kind: "none" });
});

test("LRCLIB's name header on plain words is left out", async () => {
  const row = { trackName: "Africa", artistName: "Toto", plainLyrics: `Artist: Toto\nTitle: Africa\n${FIRST_LINE}` };
  const net = internet([[LRCLIB, JSON.stringify([row])], ...nothing.slice(1)]);
  assert.equal(found(await find(AFRICA, net)).words, FIRST_LINE);
});

test("a line that's a web address is an ad, not the song's", async () => {
  const row = { trackName: "Africa", artistName: "Toto", plainLyrics: `${FIRST_LINE}\nGet more at www.example.com` };
  const net = internet([[LRCLIB, JSON.stringify([row])], ...nothing.slice(1)]);
  assert.equal(found(await find(AFRICA, net)).words, FIRST_LINE);
});

test("a contributor's mark for the music is a pause, not words", async () => {
  const row = { trackName: "Africa", artistName: "Toto", duration: 295, syncedLyrics: `[00:00.32] ♪♪𝓜𝓾𝓼𝓲𝓬♪♪\n[00:10.00] ${FIRST_LINE}\n[00:15.00] ♫\n[00:20.00] Music` };
  const net = internet([[LRCLIB, JSON.stringify([row])]]);
  const lyrics = found(await find(AFRICA, net));
  assert.deepEqual(lyrics.lines.map((line) => line.text), ["", FIRST_LINE, "", "Music"]);
  assert.equal(lyrics.words, `${FIRST_LINE}\nMusic`);
});

test("YouTube Music's words are asked next, for the video playing, and its timing is kept", async () => {
  const net = internet([
    [LRCLIB, "[]"],
    [YTM_NEXT, fixture("ytm-next-lyrics.json")],
    [YTM_BROWSE, (_url, init) => {
      assert.match(init?.body ?? "", /ANDROID_MUSIC/);
      assert.match(init?.body ?? "", /MPLYt_OmZ6OuUbt2B/);
      return fixture("ytm-browse-lyrics-timed.json");
    }],
  ]);
  const lyrics = found(await find({ ...AFRICA, lengthMs: 272_000, videoId: "FTQbiNvZqaY" }, net));
  assert.equal(lyrics.source, "youtube_music");
  assert.equal(lyrics.lines[0]?.text, FIRST_LINE);
  assert.equal(lyrics.lines[0]?.timeMs, 10_500);
  // Where nothing is sung it says so with a note, which is a pause.
  assert.equal(lyrics.lines.at(-1)?.text, "");
  assert.equal(lyrics.lengthMs, 272_000);
  assert.ok(!lyrics.words.includes("♪"));
});

test("with no video to go by, YouTube Music's search finds it", async () => {
  const row = {
    musicResponsiveListItemRenderer: {
      playlistItemData: { videoId: "FTQbiNvZqaY" },
      flexColumns: [
        { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: "Africa" }] } } },
        { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: "Toto" }, { text: " • " }, { text: "Toto IV" }, { text: " • " }, { text: "4:32" }] } } },
      ],
    },
  };
  const net = internet([
    [LRCLIB, "[]"],
    [YTM_SEARCH, JSON.stringify({ contents: [row] })],
    [YTM_NEXT, fixture("ytm-next-lyrics.json")],
    [YTM_BROWSE, fixture("ytm-browse-lyrics-timed.json")],
  ]);
  assert.equal(found(await find({ title: "Africa", artist: "Toto" }, net)).source, "youtube_music");
});

test("NetEase's words come without its credits, timed", async () => {
  const net = internet([
    [LRCLIB, "[]"],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, fixture("netease-search.json")],
    [NETEASE_LYRIC, fixture("netease-lyric.json")],
  ]);
  const lyrics = found(await find(AFRICA, net));
  assert.equal(lyrics.source, "netease");
  assert.deepEqual(lyrics.lines[0], { timeMs: 10_500, text: FIRST_LINE });
  assert.ok(!lyrics.words.includes("作词"));
  assert.ok(net.asked.some((url) => url.includes("id=2016009")));
});

test("NetEase's own note for an instrumental says it has no words", async () => {
  const net = internet([
    [LRCLIB, "[]"],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, fixture("netease-search.json")],
    [NETEASE_LYRIC, JSON.stringify({ lrc: { lyric: "[00:01.00]纯音乐，请欣赏" }, code: 200 })],
    [LETRAS_SEARCH, 'LetrasSug({"response":{"docs":[]}})'],
    ["https://genius.com/api/search/multi", '{"response":{"sections":[]}}'],
  ]);
  assert.deepEqual(await find(AFRICA, net), { kind: "instrumental" });
});

function netEaseLyric(lyric: string): Internet {
  return internet([
    [LRCLIB, "[]"],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, fixture("netease-search.json")],
    [NETEASE_LYRIC, JSON.stringify({ lrc: { lyric }, code: 200 })],
    [LETRAS_SEARCH, 'LetrasSug({"response":{"docs":[]}})'],
    ["https://genius.com/api/search/multi", '{"response":{"sections":[]}}'],
  ]);
}

test("a NetEase line that only marks an instrumental is no words", async () => {
  const net = netEaseLyric("作词 : Ada Writer\n作曲 : Ada Writer\n[Instrumental]");
  assert.deepEqual(await find(AFRICA, net), { kind: "instrumental" });
  assert.deepEqual(await find(AFRICA, netEaseLyric("（instrumental）")), { kind: "instrumental" });
});

test("a NetEase instrumental mark between real lines is only a break", async () => {
  const lyric = "[00:10.00] First line of the song\n[00:20.00] [Instrumental]\n[00:30.00] Love this instrumental part\n[00:40.00] Second line of the song";
  const lyrics = found(await find(AFRICA, netEaseLyric(lyric)));
  assert.equal(lyrics.words, "First line of the song\nLove this instrumental part\nSecond line of the song");
  assert.equal(lyrics.lines.some((line) => line.text.includes("[Instrumental]")), false);
});

test("a credit NetEase opens with is left out, whatever its label", async () => {
  const only = await find(AFRICA, netEaseLyric("[00:01.00] 编程 : Player One"));
  assert.deepEqual(only, { kind: "none" });
  const lyrics = found(await find(AFRICA, netEaseLyric("[00:01.00] 人声 : Singer One\n[00:10.00] First line of the song")));
  assert.equal(lyrics.words, "First line of the song");
  // Who sings a line is a line, and one credit at the end can be sung.
  const kept = found(await find(AFRICA, netEaseLyric("[00:10.00] Chorus : Stay with me\n[00:20.00] First line of the song\n[00:40.00] Mastering : Someone")));
  assert.equal(kept.words, "Chorus : Stay with me\nFirst line of the song\nMastering : Someone");
});

test("credits NetEase closes with are left out once two of them end the song", async () => {
  const lyric = "[00:10.00] First line of the song\n[00:20.00] Male : Stay with me\n[00:40.00] Guitar : Player One\n[00:40.50] Recorded at Some Studio\n[00:41.00] Bass : Player Two";
  const closed = found(await find(AFRICA, netEaseLyric(lyric)));
  assert.equal(closed.words, "First line of the song\nMale : Stay with me");
});

test("a closing credit needs no space before its colon, and a time is still sung", async () => {
  const dropped = found(await find(AFRICA, netEaseLyric("[00:10.00] First line of the song\n[00:40.00] 贝斯：Player One\n[00:41.00] 鼓:Player Two")));
  assert.equal(dropped.words, "First line of the song");
  const one = found(await find(AFRICA, netEaseLyric("[00:10.00] First line of the song\n[00:40.00] 贝斯：Player One")));
  assert.equal(one.words, "First line of the song\n贝斯：Player One");
  const times = found(await find(AFRICA, netEaseLyric("[00:10.00] First line of the song\n[00:40.00] 7:30 in the night\n[00:41.00] When it's 11:30 in the night")));
  assert.equal(times.words, "First line of the song\n7:30 in the night\nWhen it's 11:30 in the night");
  // Punctuation isn't who played, and neither is a colon with no label.
  const marks = found(await find(AFRICA, netEaseLyric("[00:10.00] First line of the song\n[00:40.00] ???: still here\n[00:41.00] !!!: still here")));
  assert.equal(marks.words, "First line of the song\n???: still here\n!!!: still here");
  const blank = found(await find(AFRICA, netEaseLyric("[00:10.00] First line of the song\n[00:40.00]  : Someone\n[00:41.00]   : Else")));
  assert.equal(blank.words, "First line of the song\n: Someone\n: Else");
});

test("Letras's words come from the page its search points to, untimed and without its ads", async () => {
  const net = internet([
    [LRCLIB, "[]"],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, '{"result":{"songs":[]},"code":200}'],
    [LETRAS_SEARCH, fixture("letras-search.txt")],
    [LETRAS_PAGE, fixture("letras-song.html")],
  ]);
  const lyrics = found(await find({ title: "Gracias a la vida", artist: "Mercedes Sosa" }, net));
  assert.equal(lyrics.source, "letras");
  assert.deepEqual(lyrics.lines, []);
  assert.equal(lyrics.words, "Paper boats along the river\nWe sang until the morning's came\n\nEvery window holds a lantern\nAnd the night forgets its name");
});

test("Letras's page cut off in the middle of a tag leaves none of it in the words", async () => {
  const net = internet([
    [LRCLIB, "[]"],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, '{"result":{"songs":[]},"code":200}'],
    [LETRAS_SEARCH, fixture("letras-search.txt")],
    [LETRAS_PAGE, '<div class="lyric-original"><p>Paper boats<br/>Along the river<script src="x"</div>'],
  ]);
  const lyrics = found(await find({ title: "Gracias a la vida", artist: "Mercedes Sosa" }, net));
  assert.equal(lyrics.words, "Paper boats\nAlong the river");
});

test("untimed words give way to timed ones elsewhere, and are kept when there are none", async () => {
  const row = { trackName: "Africa", artistName: "Toto", plainLyrics: "Untimed words" };
  const timed = internet([
    [LRCLIB, JSON.stringify([row])],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, fixture("netease-search.json")],
    [NETEASE_LYRIC, fixture("netease-lyric.json")],
  ]);
  assert.equal(found(await find(AFRICA, timed)).source, "netease");
  // Letras's are untimed too, so they can't better them, and it isn't asked.
  const none = internet([[LRCLIB, JSON.stringify([row])], [YTM_SEARCH, "{}"], [NETEASE_SEARCH, '{"result":{"songs":[]},"code":200}']]);
  assert.equal(found(await find(AFRICA, none)).words, "Untimed words");
});

test("words timed for another take stay, when no one has them for this one", async () => {
  const net = internet([[LRCLIB, fixture("lrclib-search.json")], ...nothing.slice(1)]);
  const lyrics = found(await find({ ...AFRICA, lengthMs: 200_000 }, net));
  assert.equal(lyrics.source, "lrclib");
  assert.ok(lyrics.lines.length > 0);
});

test("a title with a tag is looked for again without it", async () => {
  const row = { trackName: "Africa", artistName: "Toto", plainLyrics: "The words" };
  const net = internet([
    [(/track_name=Africa&/), JSON.stringify([row])],
    [LRCLIB, "[]"],
    [YTM_SEARCH, "{}"],
    [NETEASE_SEARCH, '{"result":{"songs":[]},"code":200}'],
    [LETRAS_SEARCH, 'LetrasSug({"response":{"docs":[]}})'],
    ["https://genius.com/api/search/multi", '{"response":{"sections":[]}}'],
  ]);
  assert.equal(found(await find({ title: "Africa (Radio Session)", artist: "Toto" }, net)).words, "The words");
});

test("LRCLIB saying it's an instrumental is believed when no one has words", async () => {
  const net = internet([[LRCLIB, JSON.stringify([{ trackName: "Africa", artistName: "Toto", instrumental: true, plainLyrics: null, syncedLyrics: null }])], ...nothing.slice(1)]);
  assert.deepEqual(await find(AFRICA, net), { kind: "instrumental" });
});

test("LRCLIB busy is asked again, as it asks, and then answers", async () => {
  let tries = 0;
  const net = internet([[LRCLIB, () => (++tries < 3 ? { status: 429, text: "{}" } : fixture("lrclib-search.json"))]]);
  assert.equal(found(await find(AFRICA, net)).source, "lrclib");
  assert.equal(tries, 3);
});

test("no one reachable is not the same as no lyrics", async () => {
  const net = internet([[/./, "unreachable"]]);
  assert.deepEqual(await find(AFRICA, net), { kind: "unavailable" });
});

test("LRCLIB, which has most songs, busy all along while the others have none: it isn't known", async () => {
  const net = internet([[LRCLIB, { status: 503, text: "busy" }], ...nothing.slice(1)]);
  assert.deepEqual(await find(AFRICA, net), { kind: "unavailable" });
});

test("LRCLIB answers, and one other is down: the song has no lyrics", async () => {
  const net = internet([[LRCLIB, "[]"], [YTM_SEARCH, "unreachable"], ...nothing.slice(2)]);
  assert.deepEqual(await find(AFRICA, net), { kind: "none" });
});

test("timed lines: several stamps, an offset, and word stamps", () => {
  assert.deepEqual(parseLrc("[offset:+500]\n[00:01.50][00:03.00]Twice <00:03.20>over\n[01:02.5]Later\n[ti:Not a line]\n[00:02.000]\n[01:30.00]Words [in brackets] stay"), [
    { timeMs: 1_000, text: "Twice over" },
    { timeMs: 1_500, text: "" },
    { timeMs: 2_500, text: "Twice over" },
    { timeMs: 62_000, text: "Later" },
    { timeMs: 89_500, text: "Words [in brackets] stay" },
  ]);
});

test("Genius supplies untimed words when the other sites have none", async () => {
  const song = { title: "Paper Boats", artist: "River Band" };
  const search = { response: { sections: [{ hits: [{ type: "song", result: { title: song.title, artist_names: song.artist, path: "/River-band-paper-boats-lyrics" } }] }] } };
  const net = internet([
    ["https://genius.com/api/search/multi", JSON.stringify(search)],
    ...nothing,
    ["https://genius.com/River-band-paper-boats-lyrics", '<div data-lyrics-container="true">Paper Boats Lyrics<br>[Verse 1]<br>Paper boats<br><a>along</a> the river</div>'],
  ]);
  assert.deepEqual(await find(song, net), { kind: "found", words: "Paper boats\nalong the river", lines: [], source: "genius" });
});
