#!/usr/bin/env python3
"""Builds the website in docs/ from site/ templates and site/strings/<lang>.json.

English goes at the root and every other language under /<lang>/. Run it after editing
anything in site/, then commit docs/ too:  python3 scripts/build-site.py
"""
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
DOCS = ROOT / "docs"
BASE = "https://crosstune.4st.li"
# URL folder for each strings file, in the order the language picker lists them.
LANGS = ["en", "es", "pt-br", "de", "fr", "it", "nl", "pl", "ru", "tr", "id", "hi", "ja", "ko", "zh-cn"]
HEART = '<svg class="heart" viewBox="0 0 960 960" width="14" height="14" role="img" aria-label="love"><path fill="#e34b5f" d="M480,840Q466,840 451.5,835Q437,830 426,819L357,756Q251,659 165.5,563.5Q80,468 80,354Q80,260 143,197Q206,134 300,134Q353,134 400,156.5Q447,179 480,218Q513,179 560,156.5Q607,134 660,134Q754,134 817,197Q880,260 880,354Q880,468 794.5,564Q709,660 602,757L534,819Q523,830 508.5,835Q494,840 480,840Z"/></svg>'

# Material Symbols Rounded, in the same order as the cards in each strings file.
FEATURE_ICONS = [
    "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-288q0-17 11.5-28.5T720-720h120q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640h-80v360q0 50-35 85t-85 35ZM160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Z",
    "M280-280v-400q0-17 11.5-28.5T320-720q17 0 28.5 11.5T360-680v400q0 17-11.5 28.5T320-240q-17 0-28.5-11.5T280-280Zm160 160v-720q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v720q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM120-440v-80q0-17 11.5-28.5T160-560q17 0 28.5 11.5T200-520v80q0 17-11.5 28.5T160-400q-17 0-28.5-11.5T120-440Zm480 160v-400q0-17 11.5-28.5T640-720q17 0 28.5 11.5T680-680v400q0 17-11.5 28.5T640-240q-17 0-28.5-11.5T600-280Zm160-160v-80q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v80q0 17-11.5 28.5T800-400q-17 0-28.5-11.5T760-440Z",
    "M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80ZM240-160v-400 400Z",
]
# Name and app drawable of each service on the "Works with" list.
SERVICES = [
    ("Spotify", "spotify"), ("YouTube Music", "youtubemusic"), ("YouTube", "youtube"),
    ("Apple Music", "applemusic"), ("Deezer", "deezer"), ("TIDAL", "tidal"),
    ("SoundCloud", "soundcloud"), ("Bandcamp", "bandcamp"), ("Audiomack", "audiomack"),
    ("Amazon Music", "amazonmusic"), ("Qobuz", "qobuz"),
]
# Web frontends with no logo of their own: their first letter on a tile, as in the app (Frontend.color).
FRONTENDS = [("Invidious", "#2E8FE0"), ("Piped", "#E5482F")]
# Links in the Google Play steps. The app is in closed testing, so testers join the group first.
PLAY_LINKS = {
    "group": "https://groups.google.com/g/crosstune-testers",
    "testing": "https://play.google.com/apps/testing/com.astrovm.crosstune",
    "store": "https://play.google.com/store/apps/details?id=com.astrovm.crosstune",
}


def services():
    # Reuses the app's service logos so the site and the app always match.
    items = []
    for name, drawable in SERVICES:
        xml = (ROOT / "app/src/main/res/drawable" / f"ic_service_{drawable}.xml").read_text()
        color = re.search(r'android:fillColor="([^"]+)"', xml).group(1)
        path = re.search(r'android:pathData="([^"]+)"', xml).group(1)
        fill = "currentColor" if color in ("#000000", "#FF000000") else color
        items.append(f'            <li title="{name}"><svg viewBox="0 0 24 24" role="img" aria-label="{name}"><path fill="{fill}" d="{path}"/></svg></li>')
    for name, color in FRONTENDS:
        items.append(
            f'            <li title="{name}"><svg viewBox="0 0 24 24" role="img" aria-label="{name}">'
            f'<rect width="24" height="24" rx="6" fill="{color}"/>'
            f'<text x="12" y="17" text-anchor="middle" font-family="system-ui, sans-serif" font-size="14" font-weight="700" fill="#fff">{name[0]}</text></svg></li>'
        )
    return "\n".join(items)


def home(code):
    return "/" if code == "en" else f"/{code}/"


def picker(strings, code, page):
    options = "".join(
        f'<option value="{home(c)}{page}"{" selected" if c == code else ""}>{strings[c]["name"]}</option>'
        for c in LANGS
    )
    label = html.escape(strings[code]["language_label"])
    return f' <select class="language" aria-label="{label}" onchange="pickLanguage(this.value)">{options}</select>'


def script(strings, code, page):
    # Remembers a language picked by hand. An English page sends a visitor to the language they
    # picked before or, the first time, to their browser's language when the site has it.
    homes = {strings[c]["lang"].lower(): home(c) for c in LANGS}
    return f"""<script>
  function pickLanguage(url) {{
    try {{ localStorage.setItem("language", url.split("/")[1] && url.split("/")[1] !== "privacy" ? url.split("/")[1] : "en"); }} catch (e) {{}}
    location.href = url;
  }}
  (function () {{
    if ({json.dumps(code)} !== "en") return;
    let saved = null;
    try {{ saved = localStorage.getItem("language"); }} catch (e) {{}}
    if (saved === "en") return;
    if (saved) {{ location.replace("/" + saved + "/" + {json.dumps(page)}); return; }}
    const homes = {json.dumps(homes)};
    for (const wanted of navigator.languages || [navigator.language]) {{
      const tag = wanted.toLowerCase();
      const match = homes[tag] || homes[tag.split("-")[0]] || Object.entries(homes).find(([k]) => k.split("-")[0] === tag.split("-")[0])?.[1];
      if (match === "/") return;
      if (match) {{ location.replace(match + {json.dumps(page)}); return; }}
    }}
  }})();
</script>"""


def alternates(strings, page):
    links = [f'<link rel="alternate" hreflang="{strings[c]["lang"]}" href="{BASE}{home(c)}{page}">' for c in LANGS]
    links.append(f'<link rel="alternate" hreflang="x-default" href="{BASE}/{page}">')
    return "\n".join(links)


def fill(template, values):
    for key, value in values.items():
        template = template.replace("{{" + key + "}}", value)
    assert "{{" not in template, template[template.index("{{"):][:60]
    return template


def main():
    strings = {c: json.loads((SITE / "strings" / f"{c}.json").read_text()) for c in LANGS}
    index = (SITE / "index.html").read_text()
    privacy = (SITE / "privacy.html").read_text()
    for code in LANGS:
        s = strings[code]
        text = {k: v for k, v in s.items() if isinstance(v, str)}
        common = {
            "home": home(code),
            # App screenshots in this page's language, from docs/img/<code>/.
            "shots": code,
            "made_with": s["made_with"].replace("{heart}", HEART),
        }
        assert len(s["cards"]) == len(FEATURE_ICONS), code
        cards = "\n".join(
            f'        <div class="card"><svg viewBox="0 -960 960 960" aria-hidden="true"><path d="{icon}"/></svg>'
            f'<div><h3>{h}</h3><p>{p}</p></div></div>'
            for icon, (h, p) in zip(FEATURE_ICONS, s["cards"])
        )
        play_steps = "\n".join(f"        <li><span>{step.format(**PLAY_LINKS)}</span></li>" for step in s["play_steps"])
        how_steps = "\n".join(
            f'        <div class="card step"><span class="num">{n}</span><div><h3>{h}</h3><p>{p}</p></div></div>'
            for n, (h, p) in enumerate(s["how_steps"], 1)
        )
        out = DOCS if code == "en" else DOCS / code
        out.mkdir(parents=True, exist_ok=True)
        (out / "index.html").write_text(fill(index, {
            **text, **common, "cards": cards, "how_steps": how_steps, "play_steps": play_steps, "services": services(),
            "alternates": alternates(strings, ""),
            "language_picker": picker(strings, code, ""),
            "language_script": script(strings, code, ""),
        }))
        body = "\n".join(
            f"<{tag}>{content.replace('{email}', '<a href=\"mailto:~@4st.li\">~@4st.li</a>')}</{tag}>"
            for tag, content in s["privacy_body"]
        )
        (out / "privacy").mkdir(exist_ok=True)
        (out / "privacy" / "index.html").write_text(fill(privacy, {
            **text, **common,
            "short_items": "\n".join(f"<li>{item}</li>" for item in s["short_items"]),
            "privacy_body": body,
            "alternates": alternates(strings, "privacy/"),
            "language_picker": picker(strings, code, "privacy/"),
            "language_script": script(strings, code, "privacy/"),
        }))


if __name__ == "__main__":
    main()
