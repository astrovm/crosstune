# Crosstune

Open a link from any service in the app you listen on, name the song playing nearby, and follow its lyrics and chords. Learn the words, and practice them with the music.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Dreams Come True by S.E.S. resolved from Apple Music, ready to open in YouTube Music" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="The City Pop playlist from Deezer, with every song, ready to play in YouTube Music" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Choosing which app opens the song, with app icons" width="200">
</p>

## ⬇️ Install

Needs Android 8.0 or newer.

- Get the APK from [Releases](https://github.com/astrovm/crosstune/releases), or add `https://github.com/astrovm/crosstune` to [Obtainium](https://github.com/ImranR98/Obtainium) for updates.
- Or on Google Play, which is in testing: join the [testers group](https://groups.google.com/g/crosstune-testers), tap **Become a tester** on the [testing page](https://play.google.com/apps/testing/com.astrovm.crosstune), then install from [Google Play](https://play.google.com/store/apps/details?id=com.astrovm.crosstune).

## 🚀 Set up

Pasting or sharing a link to Crosstune works with no setup. Crosstune opens on its main screen, and under the link field lists what Android has to allow, each with what it gets you:

1. **Allow links in Crosstune**, so music links you tap in any app open here.
2. **Let lyrics follow your music**, so the words of what's playing show in time.
3. Tap one to see its steps: Android's page for your phone, a small guide floats over it, and it checks itself off once done. With [Shizuku](https://shizuku.rikka.app) or root on Android 12 or newer, tap **Fix all** instead. It's one tap.

Links from Spotify, YouTube Music, Apple Music, Deezer and TIDAL open here from the start. Turn on YouTube in **Settings** → **Open links from**. SoundCloud, Bandcamp, Audiomack, Invidious and Piped links open here when shared or pasted.

With one music app on your phone, songs play there. With more, the first one you pick on a song is where they play from then on. Change it in **Settings**.

Once you've opened something, anything left or broken later shows as **Things to fix** at the bottom of the main screen: **Fix all** with Shizuku or root, or **Fix one by one**.

## Features

- **Tap and go.** A link from one service opens in another, at the exact song, album or artist when the app supports it: Spotify to YouTube Music, Apple Music to Deezer, TIDAL to Apple Music.
- **Choose where.** Pick your music app once. Links go straight to it, or show in Crosstune first, with the song playing in that app. Set one app per service. YouTube and YouTube Music patched by ReVanced, RVX, Morphe or Vanced count as apps of their own, next to the official ones. Or add any app or site with a search URL like `https://example.com/search?q={query}`.
- **Not in the app you picked?** Crosstune says so, then opens the song where it's from or searches anyway. Choose in **Settings** → **When a song isn't found**.
- **Playlists and albums.** See their songs and open any one, or play the lot, in order or shuffled, in YouTube Music, YouTube, TIDAL or Spotify.
- **Recent.** Search your recent songs, and swipe one away to remove it. Its play button goes where links do.
- **Play and stay.** **Play** a song in your music app while you stay in Crosstune, with its own player: cover, progress, pause, skip, shuffle, repeat, seek and the app's own like. Swipe the cover to skip, play anything from what's up next, or set a sleep timer. The line being sung shows at the bottom, and the title opens the song's links. Whatever is playing waits in a bar on the home screen. Swipe up for the player, up again for the words, and down to go back.
- **Name that song.** Crosstune listens for up to 12 seconds and shows the song playing nearby, like a pasted link. Or type a song's name to find it. If Shazam can't be reached, it opens the Shazam app or Google's song search instead, when you have them.
- **Lyrics in time.** The words follow your music app, or Crosstune listens along, song after song. Each line lights a moment before it's sung, so you've read it by then: set how early, and whether the next line brightens ahead, in **Settings** → **Lyrics**. Tap a line to jump there, make the words bigger or smaller or center them, and fix the timing by tapping the line you hear or nudging it half a second at a time. Lyrics timed for another take offer the fix themselves. Also works with YouTube and YouTube Music patched by ReVanced, RVX or Morphe.
- **Chords.** Chord sheets over the words, with the capo, lined up letter by letter.
- **Recording timing.** Lyrics and corrections stay with their recording. With microphone access already allowed, lyrics briefly listen when a video, or a release longer or shorter than the one they're timed for, plays: they find how far ahead or behind it is and remember it after three agreeing audio fingerprints. Played once as the release they're timed for, they also learn how far Shazam's place in the song is from theirs, so another release Shazam matches the same way lines up too. They hear the phone's own sound, through headphones too, and the microphone only when the phone won't share it. On phones with 3 GB of memory or more, they also hear the singing, 20 seconds at a time, and move each line to where it's sung, which **Reset** undoes: through the song the first time it plays from the start, and once whenever you pick it up partway or skip within it. That runs on your phone with [whisper.cpp](https://github.com/ggml-org/whisper.cpp), whose 32 MB model downloads once, on Wi-Fi.
- **Report a problem.** Wrong words or timing? In the lyrics, one tap sends the song, its timing and what you write straight to the developer, kept private. Say yes once and Crosstune reports songs with missing lyrics, timing or chords, and timing you fix, by itself. In **Settings**, **Report a problem** opens a GitHub issue or an email instead, for you to send.
- **Floating lyrics.** Float them over any app.
- **MilkDrop visuals.** 152 [projectM](https://github.com/projectM-visualizer/projectm) visuals behind the lyrics, moving to what your phone plays. Pick which show in **Settings** → **Visuals**.
- **Learn the words.** Kana, pinyin, romaji and any other script in Latin letters under each line, translations, and the meaning of any word.
- **Practice with the music.** Save lines and they come back as cards that play the line in your music app. Choose the text layers and sizes for each side, and make more than one kind of card from a line, each on its own schedule. Rate each one: **Again**, **Hard**, **Good** or **Easy**.
- **ChatGPT, if you have it.** Sign in to translate whole songs, explain a line or a song, find the words worth learning, and find songs by describing them.
- **On your TV.** Runs on Android TV and Google TV too: what's playing, its lyrics and visuals on the big screen, all with the remote. **Play** opens a song in YouTube with its lyrics over it.
- **Your colors.** Light or dark, your wallpaper's colors, and pure black for OLED screens.
- **Shortcuts.** One-tap paste, home screen widgets, Quick Settings tiles and a long-press shortcut on the app icon.
- **No tracking.** Drops tracking bits like `?si=` from links.
- **16 languages.** Follows your phone, or pick one in Settings.

## Supported links

| Service | Reads | Exact match |
| --- | --- | --- |
| Spotify | Songs, albums, artists, playlists, `spotify.link`, `spotify:` URIs, track IDs | Songs |
| YouTube Music | Songs, videos and playlists | Songs, albums, artists |
| YouTube | Videos, Shorts, live streams, playlists, `youtu.be` | Songs |
| Apple Music | Songs, albums, artists, playlists | Songs, albums, artists |
| Deezer | Songs, albums, artists, playlists, `link.deezer.com` | Songs, albums, artists |
| TIDAL | Songs, albums, artists, playlists | Songs, albums, artists |
| SoundCloud | Tracks, artists, sets, `on.soundcloud.com` | Songs, albums, verified artists |
| Bandcamp | Tracks, albums | Songs, albums |
| Audiomack | Songs, albums, playlists | Songs, albums, verified artists |
| Amazon Music | Can't read its links | Search |
| Qobuz | Can't read its links | Songs, albums |

Songs shared from Pixel **Now Playing**, **Shazam** links and songs shared from Google's search work too. "Search" means Crosstune opens a search for the title and artist.

## 🛠️ Troubleshooting

**Links still open in another app.**
Turn the service on in **Settings** → **Open links from**. Then in Android, go to **Settings** → **Apps** → **Crosstune** → **Open by default** → **Add link** and turn on its links.

**I got a search, not the song.**
Crosstune found no confident match. Choose what happens then in **Settings** → **When a song isn't found**.

## Privacy

- No account, analytics or ads. History stays on your device.
- A link's service is asked only for its title, artist and cover. To open the song itself, the title and artist also go to the search of the app it opens in. For Spotify, whose search needs an account, they go to [ListenBrainz](https://listenbrainz.org) instead, which knows Spotify's songs by name, and Spotify's public song pages are read. Going straight to the app with **Play right away** off, a search opens there instead, and nothing is sent.
- Lyrics, chords and translations are looked up by title and artist on [LRCLIB](https://lrclib.net), YouTube Music, NetEase Cloud Music, [Letras](https://www.letras.com), [Ultimate Guitar](https://www.ultimate-guitar.com), [Cifra Club](https://www.cifraclub.com.br), [LaCuerda](https://acordes.lacuerda.net), [U-FRET](https://www.ufret.jp), [Google Translate](https://translate.google.com), [MyMemory](https://mymemory.translated.net) and [Wiktionary](https://en.wiktionary.org). Readings are worked out on your phone. For Japanese, a bigger dictionary, UniDic, is downloaded once from [Maven Central](https://central.sonatype.com/artifact/com.atilika.kuromoji/kuromoji-unidic), on Wi-Fi.
- Signed in with ChatGPT, the AI features send OpenAI the song, its lyrics and what you ask. Your sign-in stays on your phone.
- A song's meaning also asks [MusicBrainz](https://musicbrainz.org), by MetaBrainz, for the year it came out and its album, by title and artist, so ChatGPT is told them instead of searching for them. What it answers is kept on your phone.
- Naming a song uses the microphone only while listening, and only a fingerprint goes to Shazam, never the audio. Apple is then asked how long the recording Shazam named is, so its lyrics are timed for it. The visuals use it to move to your music, and record and send nothing. Lining the words up to the singing hears it on your phone and sends nothing. Its speech model is downloaded once from [Hugging Face](https://huggingface.co/ggerganov/whisper.cpp).
- **Report a problem** in the lyrics sends the song, its timing and what you write to Crosstune's only server, on Cloudflare, when you tap **Send**. It keeps no IP address, only the developer reads reports, and each is deleted after 180 days. With **Report song problems automatically** on, problems Crosstune finds go too, once per song.
- Shizuku or root is used only to change Android's link, microphone, lyrics and floating settings for Crosstune.

Full [privacy policy](https://crosstune.4st.li/privacy/).

<details>
<summary><b>Verify your download</b></summary>

Release APKs are signed with this certificate. Its SHA-256 fingerprint is also listed on each release:

```text
64:EC:D5:52:E3:4B:B4:15:8A:04:6B:DE:1B:BD:BC:C9:04:23:9D:59:33:CF:74:C4:62:B6:FD:02:A7:B0:F6:FA
```

</details>

## Building

Clone with `git clone --recurse-submodules`, and use the Android SDK and JDK 17 or newer.

```bash
./gradlew :app:assembleDebug   # "Crosstune Dev", installs next to the released app
./gradlew :app:ci            # the checks CI runs
```

More in [CONTRIBUTING.md](CONTRIBUTING.md).

Crosstune is licensed under the [GNU General Public License v3.0 only](LICENSE) (GPL-3.0-only).
