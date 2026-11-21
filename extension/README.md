# Crosstune for the browser

This extension opens a music link in the service picked in its options, and shows the lyrics of what's playing on a music site in a side panel (Firefox's sidebar), in Chrome, Edge, Brave and Firefox. TypeScript, esbuild and no framework; it needs Node 24 or newer.

```bash
cd extension
npm ci
npm run build       # dist/chrome and dist/firefox
npm run typecheck
npm test            # unit tests on recorded answers in test/fixtures/, no network
node scripts/try.mjs 'https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT' youtubemusic   # the real logic on live services
node scripts/try-lyrics.mjs 'Africa' 'Toto' 272000   # the lyrics finder on live sites, what it found and where
npm run e2e         # headless Chromium with the built extension: a click on a Spotify link ends on YouTube Music
npm run e2e:firefox # the same in headless Firefox, through geckodriver
npm run e2e:panel   # every state of the lyrics panel in Chromium, no network: a page playing silence and recorded answers; screenshots in dist/panel-shots/
npm run package     # the stores' zips in dist/: Chrome and Edge, Firefox, and the source Firefox's reviewers build from
npm run screenshots # the stores' 1280×800 screenshots in each language, in dist/screenshots/
npm run icons       # the icons again from the app's store icon, when it changes
```

Load it unpacked: in Chrome, Edge or Brave open `chrome://extensions` (`edge://extensions`, `brave://extensions`), turn on **Developer mode**, **Load unpacked**, and pick `dist/chrome`. In Firefox open `about:debugging#/runtime/this-firefox`, **Load Temporary Add-on**, and pick `dist/firefox/manifest.json`. Firefox keeps the sites an extension may read off until you allow them, so open the extension's options and press **Allow**; they're the music sites' pages and the public endpoints the app already asks. It follows the app's logic (`LinkResolver`, `ExactMatcher`, `Destinations`) but not the parts that need the app, such as the ChatGPT pick or Shazam. The toolbar button opens the lyrics, and the panel's gear the options. `src/content.ts` reads the song from the page's Media Session and the position from its `<video>` or `<audio>`; `src/lyrics.ts` follows the app's `LyricsFinder`, without the covers, romaji and sentence case; `src/panel.ts` draws it and asks only while it's open. CI builds and tests it when `extension/` changes, and runs `npm run e2e:panel`. `npm run e2e` needs the network and a Chromium download (`npx playwright install chromium`), so it runs by hand. So does `npm run e2e:firefox`, with Firefox and geckodriver on the PATH; a snap's geckodriver can't be stopped when it's done, so point `GECKODRIVER` and `FIREFOX` at [geckodriver's release](https://github.com/mozilla/geckodriver/releases) and [Firefox's own build](https://www.mozilla.org/firefox/linux/) instead. Both browser tests show the browser as people's does, since Spotify tells headless browsers what it hides from people's.

Its strings are in `_locales/`, in the app's 16 languages; `test/locales.test.ts` fails when one is missing a message. A new version goes out when `version` in `package.json` changes on main: the **Extension release** workflow sends it to each store whose keys are set and tags it `extension-<version>`. Each store's first version is uploaded by hand; [`store/README.md`](store/README.md) has the listing text, the answers the dashboards ask for, and where each key comes from.
