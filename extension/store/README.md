# Store listings

What the Chrome Web Store, Firefox Add-ons and Edge Add-ons ask for, ready to paste.

- `listing.json`: each language's summary and description. The release sends Firefox Add-ons its copy each time; Chrome's and Edge's are pasted into their dashboards.
- Screenshots: `npm run screenshots` draws a 1280×800 one per language in `dist/screenshots/`.
- Icon: `icons/icon-128.png`. Small promo tile (440×280) and others are optional.
- Privacy policy: https://crosstune.4st.li/privacy/
- Homepage and support: https://github.com/astrovm/crosstune
- Category: Chrome **Entertainment**, Firefox **Photos, Music & Videos**, Edge **Entertainment**.

## Chrome's privacy tab

**Single purpose:** Opens music links in the music service the user picked: a click on a Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL or SoundCloud link opens the same song, album or artist in the chosen service.

**Permission justifications:**

- `webNavigation`: sees when a tab is about to open a music link, so it can open the same item in the chosen service instead.
- `storage`: keeps the two settings, on or off and the chosen service, in the browser.
- `declarativeNetRequestWithHostAccess`: YouTube Music's and YouTube's search answers only requests that come from their own site, so the extension's searches to them say they come from it. Nothing else is changed.
- Host permissions: the music sites whose links it opens, and their public endpoints it asks for a link's title and artist and to find the same item elsewhere. ListenBrainz finds a song on Spotify, whose search needs an account.

**Remote code:** No. All code is in the package.

**Data usage:** It handles **Website content** only: the title and artist of the link opened, sent to the music services named above to find the same item. Nothing is sold, used for anything else, or sent to the developer. Tick the three disclosures.

## The first version, by hand

Each store's first version, and its listing, can only be made in its dashboard. `npm run package` writes the zips to `dist/`.

1. **Chrome Web Store** ($5 once): https://chrome.google.com/webstore/devconsole. Upload `crosstune-<version>-chrome.zip`, fill the listing from `listing.json` (English first, then **Add language** for each), the privacy tab from above, and submit.
2. **Firefox Add-ons**: https://addons.mozilla.org/developers/addon/submit/. **On this site**, upload `crosstune-<version>-firefox.zip`, then `crosstune-<version>-source.zip` when it asks for source code, with the note: Build with Node 24: `npm ci && npm run build`; the add-on is `dist/firefox`.
3. **Edge Add-ons**: https://partner.microsoft.com/dashboard/microsoftedge. Upload the Chrome zip, fill the listing the same way, and submit.

## Keys for the release workflow

After that, `.github/workflows/extension-release.yml` sends each new version, when `version` in `package.json` changes on main. A store without its keys is skipped. Set them under **Settings** → **Secrets and variables** → **Actions**.

| Store | Secrets | Where |
| --- | --- | --- |
| Chrome | `CHROME_CLIENT_ID`, `CHROME_CLIENT_SECRET`, `CHROME_REFRESH_TOKEN` | An OAuth client of type **Desktop app** in a Google Cloud project with the **Chrome Web Store API** on, and a refresh token for the scope `https://www.googleapis.com/auth/chromewebstore`: https://developer.chrome.com/docs/webstore/using-api |
| | `CHROME_PUBLISHER_ID`, `CHROME_EXTENSION_ID` | The dashboard's **Publisher** → **Settings**, and the item's ID |
| Firefox | `AMO_API_KEY`, `AMO_API_SECRET` | https://addons.mozilla.org/developers/addon/api/key/ |
| Edge | `EDGE_CLIENT_ID`, `EDGE_API_KEY`, `EDGE_PRODUCT_ID` | Partner Center → **Microsoft Edge** → **Publish API**, and the extension's **Product ID** |

The Edge API key expires; renew it in **Publish API** when the release says it was turned away.
