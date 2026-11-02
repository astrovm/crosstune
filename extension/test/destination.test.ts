import assert from "node:assert/strict";
import { test } from "node:test";
import { decide, type Decision } from "../src/destination.ts";
import type { Service } from "../src/services.ts";
import { fixture, internet, spotifyEmbed, type Internet } from "./support.ts";

const TRACK = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT";
const ALBUM = "https://open.spotify.com/album/2noRn2Aes5aoNVsU6iWThc";
const ARTIST = "https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt";
/** Where each is read: Spotify's own page names it only to link previews and bots. */
const TRACK_EMBED = "https://open.spotify.com/embed/track/4cOdK2wGLETKBW3PvgPWqT";
const ALBUM_EMBED = "https://open.spotify.com/embed/album/2noRn2Aes5aoNVsU6iWThc";
const ARTIST_EMBED = "https://open.spotify.com/embed/artist/0gxyHStUsqpMadRV0Di1Qt";

function run(url: string, preferred: Service, net: Internet): Promise<Decision> {
  return decide(url, preferred, { fetch: net.fetch, country: "US" });
}

/** What YouTube Music says of someone's own upload, which isn't a song or an official video. */
const ugcVideo = JSON.stringify({ videoType: { musicVideoType: "MUSIC_VIDEO_TYPE_UGC" } });

const spotifyTrackPage: [string, string] = [TRACK_EMBED, fixture("spotify-embed-track.html")];

test("a song found exactly opens on the same song", async () => {
  const net = internet([spotifyTrackPage, ["https://itunes.apple.com/search", fixture("itunes-search-song.json")]]);
  assert.deepEqual(await run(TRACK, "applemusic", net), {
    kind: "go",
    how: "exact",
    url: "https://music.apple.com/us/album/never-gonna-give-you-up/1773292758?i=1773293184&uo=4",
  });
});

test("an Apple Music album link with a song in it finds the song, from the Apple lookup", async () => {
  const net = internet([
    ["https://itunes.apple.com/lookup", fixture("itunes-lookup-song.json")],
    ["https://music.youtube.com/youtubei/v1/search", fixture("ytm-search-songs.json")],
  ]);
  const decision = await run("https://music.apple.com/us/album/never-gonna-give-you-up/1?i=1773293184", "youtubemusic", net);
  assert.equal(decision.kind, "go");
  assert.ok(decision.kind === "go" && decision.how === "exact" && decision.url.startsWith("https://music.youtube.com/watch?v="));
});

test("an album is found on the services that can look one up", async () => {
  const net = internet([
    [ALBUM_EMBED, fixture("spotify-embed-album.html")],
    ["https://music.youtube.com/youtubei/v1/search", fixture("ytm-search-albums.json")],
  ]);
  const decision = await run(ALBUM, "youtubemusic", net);
  assert.ok(decision.kind === "go" && decision.how === "exact" && decision.url.startsWith("https://music.youtube.com/browse/"));
});

test("an artist is found by name", async () => {
  const net = internet([
    [ARTIST_EMBED, fixture("spotify-embed-artist.html")],
    ["https://music.youtube.com/youtubei/v1/search", fixture("ytm-search-artists.json")],
  ]);
  const decision = await run(ARTIST, "youtubemusic", net);
  assert.equal(decision.kind, "go");
});

test("a song only the search has: Spotify has no match, so the service's own search opens", async () => {
  const net = internet([
    [TRACK_EMBED, spotifyEmbed("Quiet Unreleased Thing", "Nobody Known", 200_000)],
    ["https://itunes.apple.com/search", JSON.stringify({ resultCount: 0, results: [] })],
  ]);
  assert.deepEqual(await run(TRACK, "applemusic", net), {
    kind: "go",
    how: "search",
    url: "https://music.apple.com/search?term=Quiet%20Unreleased%20Thing%20Nobody%20Known",
  });
});

test("an edition tag the other service doesn't use is left out of the search", async () => {
  const net = internet([[TRACK_EMBED, spotifyEmbed("Song Name - Remastered 2011", "Some Artist", 200_000)]]);
  assert.deepEqual(await run(TRACK, "amazonmusic", net), {
    kind: "go",
    how: "search",
    url: "https://music.amazon.com/search/Song%20Name%20Some%20Artist",
  });
});

test("a link already in the preferred service is left alone, without asking anyone", async () => {
  const net = internet([]);
  assert.deepEqual(await run(TRACK, "spotify", net), { kind: "leave" });
  assert.deepEqual(net.asked, []);
});

test("a page that isn't a music link is left alone", async () => {
  const net = internet([]);
  assert.deepEqual(await run("https://open.spotify.com/", "deezer", net), { kind: "leave" });
  assert.deepEqual(await run("https://www.youtube.com/@someone", "deezer", net), { kind: "leave" });
  assert.deepEqual(await run("not a url", "deezer", net), { kind: "leave" });
  assert.deepEqual(net.asked, []);
});

test("when the service can't be read, the original link opens", async () => {
  assert.deepEqual(await run(TRACK, "applemusic", internet([[TRACK_EMBED, "unreachable"]])), { kind: "leave" });
  assert.deepEqual(await run(TRACK, "applemusic", internet([[TRACK_EMBED, { status: 500 }]])), { kind: "leave" });
  assert.deepEqual(await run(TRACK, "applemusic", internet([[TRACK_EMBED, "<html>nothing here</html>"]])), { kind: "leave" });
});

test("when the lookup can't answer, the original link opens rather than a guess", async () => {
  const offline = internet([spotifyTrackPage, ["https://itunes.apple.com/search", "unreachable"]]);
  assert.deepEqual(await run(TRACK, "applemusic", offline), { kind: "leave" });
  const refused = internet([spotifyTrackPage, ["https://itunes.apple.com/search", { status: 503 }]]);
  assert.deepEqual(await run(TRACK, "applemusic", refused), { kind: "leave" });
  const busy = internet([spotifyTrackPage, ["https://api.deezer.com/search", JSON.stringify({ error: { type: "Exception" } })]]);
  assert.deepEqual(await run(TRACK, "deezer", busy), { kind: "leave" });
});

test("a song matched on Deezer by name, artist and length, not by the remix above it", async () => {
  const net = internet([spotifyTrackPage, ["https://api.deezer.com/search/track", fixture("deezer-search-track.json")]]);
  assert.deepEqual(await run(TRACK, "deezer", net), { kind: "go", how: "exact", url: "https://www.deezer.com/track/14408104" });
});

test("a song on TIDAL is picked by name, not by its remixes", async () => {
  const net = internet([spotifyTrackPage, ["https://api.tidal.com/v1/search/tracks", fixture("tidal-search-tracks.json")]]);
  assert.deepEqual(await run(TRACK, "tidal", net), { kind: "go", how: "exact", url: "https://tidal.com/browse/track/491206012" });
});

test("Spotify is found through ListenBrainz and the song's own page", async () => {
  const id = "4PTG3Z6ehGkBFwjybzWkR8";
  const net = internet([
    ["https://api.deezer.com/track/3135556", fixture("deezer-track.json")],
    ["https://labs.api.listenbrainz.org/", JSON.stringify([{ spotify_track_ids: [id] }])],
    [`https://open.spotify.com/embed/track/${id}`, spotifyEmbed("Harder, Better, Faster, Stronger", "Daft Punk", 224_000)],
  ]);
  assert.deepEqual(await run("https://www.deezer.com/en/track/3135556", "spotify", net), {
    kind: "go",
    how: "exact",
    url: `https://open.spotify.com/track/${id}`,
  });
});

test("Spotify without a known match opens its search", async () => {
  const net = internet([
    ["https://api.deezer.com/track/3135556", fixture("deezer-track.json")],
    ["https://labs.api.listenbrainz.org/", JSON.stringify([{ spotify_track_ids: [] }])],
  ]);
  assert.deepEqual(await run("https://www.deezer.com/en/track/3135556", "spotify", net), {
    kind: "go",
    how: "search",
    url: "https://open.spotify.com/search/Harder%2C%20Better%2C%20Faster%2C%20Stronger%20Daft%20Punk",
  });
});

test("YouTube Music and YouTube open each other's videos and playlists as themselves", async () => {
  const music = internet([]);
  assert.deepEqual(await run("https://music.youtube.com/watch?v=dQw4w9WgXcQ", "youtube", music), {
    kind: "go",
    how: "direct",
    url: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  });
  assert.deepEqual(await run("https://music.youtube.com/playlist?list=OLAK5uy_abcdefghijkl", "youtube", music), {
    kind: "go",
    how: "direct",
    url: "https://www.youtube.com/playlist?list=OLAK5uy_abcdefghijkl",
  });
  assert.deepEqual(music.asked, []);
  const video = internet([["https://music.youtube.com/youtubei/v1/next", fixture("ytm-next-video.json")]]);
  assert.deepEqual(await run("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtubemusic", video), {
    kind: "go",
    how: "direct",
    url: "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
  });
});

test("a YouTube video that isn't music is left on YouTube", async () => {
  const tutorial = internet([
    ["https://music.youtube.com/youtubei/v1/next", ugcVideo],
    ["https://www.youtube.com/youtubei/v1/player", JSON.stringify({ microformat: { playerMicroformatRenderer: { category: "Education" } } })],
  ]);
  assert.deepEqual(await run("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "spotify", tutorial), { kind: "leave" });
  const unknown = internet([["https://music.youtube.com/youtubei/v1/next", "unreachable"]]);
  assert.deepEqual(await run("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "spotify", unknown), { kind: "leave" });
});

test("a YouTube video that a creator filed under Music counts as music", async () => {
  const net = internet([
    ["https://music.youtube.com/youtubei/v1/next", ugcVideo],
    ["https://www.youtube.com/youtubei/v1/player", JSON.stringify({ microformat: { playerMicroformatRenderer: { category: "Music" } } })],
    ["https://www.youtube.com/oembed", fixture("youtube-oembed.json")],
    ["https://itunes.apple.com/search", fixture("itunes-search-song.json")],
  ]);
  const decision = await run("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "applemusic", net);
  assert.ok(decision.kind === "go" && decision.how === "exact");
});

test("YouTube's embed refusal falls back to what YouTube Music says the video is", async () => {
  const net = internet([
    ["https://music.youtube.com/youtubei/v1/next", fixture("ytm-next-video.json")],
    ["https://www.youtube.com/oembed", { status: 401 }],
    ["https://itunes.apple.com/search", fixture("itunes-search-song.json")],
  ]);
  const decision = await run("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "applemusic", net);
  assert.ok(decision.kind === "go" && decision.how === "exact");
});

test("YouTube Music albums are named from its browse page", async () => {
  const net = internet([
    ["https://music.youtube.com/youtubei/v1/browse", fixture("ytm-browse-album.json")],
    ["https://api.deezer.com/search/album", JSON.stringify({ data: [{ title: "Discovery", link: "https://www.deezer.com/album/302127", artist: { name: "Daft Punk" } }] })],
  ]);
  assert.deepEqual(await run("https://music.youtube.com/browse/MPREb_7ltM34kr0mH", "deezer", net), {
    kind: "go",
    how: "exact",
    url: "https://www.deezer.com/album/302127",
  });
});

test("playlists of other services are left alone", async () => {
  const net = internet([]);
  assert.deepEqual(await run("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", "deezer", net), { kind: "leave" });
  assert.deepEqual(await run("https://soundcloud.com/flume/sets/some-set", "deezer", net), { kind: "leave" });
  assert.deepEqual(net.asked, []);
});

test("a SoundCloud song that isn't found elsewhere stays on SoundCloud", async () => {
  const net = internet([
    ["https://soundcloud.com/oembed", fixture("soundcloud-oembed.json")],
    ["https://itunes.apple.com/search", JSON.stringify({ results: [] })],
  ]);
  assert.deepEqual(await run("https://soundcloud.com/flume/never-be-like-you", "applemusic", net), { kind: "leave" });
});

test("SoundCloud as the destination reads the key from its site and picks the artist's own upload", async () => {
  const key = "a".repeat(32);
  const net = internet([
    [TRACK_EMBED, spotifyEmbed("Never Be Like You feat. Kai", "Flume", 233_000)],
    ["https://soundcloud.com/", '<html><script crossorigin src="https://a-v2.sndcdn.com/assets/app.js"></script></html>'],
    ["https://a-v2.sndcdn.com/assets/app.js", `x={client_id:"${key}"}`],
    [/^https:\/\/api-v2\.soundcloud\.com\/search\/tracks.*client_id=a{32}/, fixture("soundcloud-search-tracks.json")],
  ]);
  assert.deepEqual(await run(TRACK, "soundcloud", net), { kind: "go", how: "exact", url: "https://soundcloud.com/flume/never-be-like-you-feat-kai" });
});

test("SoundCloud's key is read again when SoundCloud turns the old one away", async () => {
  const first = "a".repeat(32);
  const second = "b".repeat(32);
  let page = 0;
  const net = internet([
    [TRACK_EMBED, spotifyEmbed("Never Be Like You feat. Kai", "Flume", 233_000)],
    ["https://soundcloud.com/", () => `<script crossorigin src="https://a-v2.sndcdn.com/assets/app${page++}.js"></script>`],
    ["https://a-v2.sndcdn.com/assets/app0.js", `client_id:"${first}"`],
    ["https://a-v2.sndcdn.com/assets/app1.js", `client_id:"${second}"`],
    [new RegExp(`client_id=${first}`), { status: 401 }],
    [new RegExp(`client_id=${second}`), fixture("soundcloud-search-tracks.json")],
  ]);
  const decision = await run(TRACK, "soundcloud", net);
  assert.equal(decision.kind, "go");
});

test("a short link is followed to the item it leads to", async () => {
  const net = internet([
    ["https://spotify.link/abc", { url: `${TRACK}?si=x` }],
    [TRACK_EMBED, fixture("spotify-embed-track.html")],
    ["https://itunes.apple.com/search", fixture("itunes-search-song.json")],
  ]);
  const decision = await run("https://spotify.link/abc", "applemusic", net);
  assert.ok(decision.kind === "go" && decision.how === "exact");
});

test("a short link whose page redirects with script is read from the page", async () => {
  const net = internet([
    ["https://spotify.link/abc", { url: "https://spotify.link/abc", text: `<script>location="${TRACK}"</script>` }],
    [TRACK_EMBED, fixture("spotify-embed-track.html")],
    ["https://itunes.apple.com/search", fixture("itunes-search-song.json")],
  ]);
  assert.equal((await run("https://spotify.link/abc", "applemusic", net)).kind, "go");
});

test("a short link that leads nowhere known is left alone", async () => {
  const net = internet([["https://spotify.link/abc", { url: "https://example.com/", text: "" }]]);
  assert.deepEqual(await run("https://spotify.link/abc", "applemusic", net), { kind: "leave" });
  assert.deepEqual(await run("https://spotify.link/abc", "applemusic", internet([["https://spotify.link/abc", "unreachable"]])), { kind: "leave" });
});

test("an album opens on the search of a service that can't look albums up", async () => {
  const lookup = JSON.stringify({ results: [{ wrapperType: "collection", collectionName: "Discovery", artistName: "Daft Punk" }] });
  const net = internet([["https://itunes.apple.com/lookup", lookup]]);
  assert.deepEqual(await run("https://music.apple.com/us/album/discovery/697194953", "spotify", net), {
    kind: "go",
    how: "search",
    url: "https://open.spotify.com/search/Discovery%20Daft%20Punk",
  });
});

test("a TIDAL link is named from TIDAL's own page", async () => {
  const net = internet([
    ["https://tidal.com/track/77646164", fixture("tidal-track.html")],
    ["https://itunes.apple.com/search", JSON.stringify({ results: [] })],
  ]);
  assert.deepEqual(await run("https://tidal.com/browse/track/77646164", "applemusic", net), {
    kind: "go",
    how: "search",
    url: "https://music.apple.com/search?term=Pleasant%20Relief%20Afterhour%20Chillout",
  });
});

test("an Apple Music link is looked up in the storefront it came from", async () => {
  const net = internet([["https://itunes.apple.com/lookup", fixture("itunes-lookup-song.json")], ["https://api.deezer.com/search/track", JSON.stringify({ data: [] })]]);
  await run("https://music.apple.com/ar/album/never-gonna-give-you-up/1?i=1773293184", "deezer", net);
  assert.match(net.asked[0] ?? "", /lookup\?id=1773293184&country=ar$/);
});
