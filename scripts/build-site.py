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
LANGS = ["en", "es", "pt-br", "de", "fr", "it", "nl", "pl", "ru", "tr", "id", "hi", "ja", "ko", "zh-cn", "zh-tw"]
HEART = '<svg class="heart" viewBox="0 0 960 960" width="14" height="14" role="img" aria-label="love"><path fill="#e34b5f" d="M480,840Q466,840 451.5,835Q437,830 426,819L357,756Q251,659 165.5,563.5Q80,468 80,354Q80,260 143,197Q206,134 300,134Q353,134 400,156.5Q447,179 480,218Q513,179 560,156.5Q607,134 660,134Q754,134 817,197Q880,260 880,354Q880,468 794.5,564Q709,660 602,757L534,819Q523,830 508.5,835Q494,840 480,840Z"/></svg>'

# Material Symbols Rounded, in the same order as the cards in each strings file, each with its viewBox:
# the app's own drawables are drawn from the top left, the others from the bottom left. ChatGPT's is
# OpenAI's logo, the app's own (Simple Icons, CC0).
FEATURE_ICONS = [
    ("0 -960 960 960", "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-288q0-17 11.5-28.5T720-720h120q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640h-80v360q0 50-35 85t-85 35ZM160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Z"),
    ("0 -960 960 960", "M280-280v-400q0-17 11.5-28.5T320-720q17 0 28.5 11.5T360-680v400q0 17-11.5 28.5T320-240q-17 0-28.5-11.5T280-280Zm160 160v-720q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v720q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM120-440v-80q0-17 11.5-28.5T160-560q17 0 28.5 11.5T200-520v80q0 17-11.5 28.5T160-400q-17 0-28.5-11.5T120-440Zm480 160v-400q0-17 11.5-28.5T640-720q17 0 28.5 11.5T680-680v400q0 17-11.5 28.5T640-240q-17 0-28.5-11.5T600-280Zm160-160v-80q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v80q0 17-11.5 28.5T800-400q-17 0-28.5-11.5T760-440Z"),
    ("0 0 960 960", "M160,640L160,160Q160,160 160,160Q160,160 160,160L160,160Q160,160 160,160Q160,160 160,160L160,240Q160,265 160,295.5Q160,326 160,360Q160,394 160,424.5Q160,455 160,480L160,640Q160,640 160,640Q160,640 160,640L160,640ZM600,720L240,720L148,812Q129,831 104.5,820.5Q80,810 80,783L80,160Q80,127 103.5,103.5Q127,80 160,80L600,80Q633,80 656.5,103.5Q680,127 680,160L680,200Q680,217 668.5,228.5Q657,240 640,240Q623,240 611.5,228.5Q600,217 600,200L600,160Q600,160 600,160Q600,160 600,160L160,160Q160,160 160,160Q160,160 160,160L160,640L600,640Q600,640 600,640Q600,640 600,640L600,520Q600,503 611.5,491.5Q623,480 640,480Q657,480 668.5,491.5Q680,503 680,520L680,640Q680,673 656.5,696.5Q633,720 600,720ZM280,560L360,560Q377,560 388.5,548.5Q400,537 400,520Q400,503 388.5,491.5Q377,480 360,480L280,480Q263,480 251.5,491.5Q240,503 240,520Q240,537 251.5,548.5Q263,560 280,560ZM760,480Q710,480 675,445Q640,410 640,360Q640,310 675,275Q710,240 760,240Q771,240 781,242Q791,244 800,247L800,80Q800,63 811.5,51.5Q823,40 840,40L920,40Q937,40 948.5,51.5Q960,63 960,80Q960,97 948.5,108.5Q937,120 920,120L880,120L880,360Q880,410 845,445Q810,480 760,480ZM280,440L480,440Q497,440 508.5,428.5Q520,417 520,400Q520,383 508.5,371.5Q497,360 480,360L280,360Q263,360 251.5,371.5Q240,383 240,400Q240,417 251.5,428.5Q263,440 280,440ZM280,320L480,320Q497,320 508.5,308.5Q520,297 520,280Q520,263 508.5,251.5Q497,240 480,240L280,240Q263,240 251.5,251.5Q240,263 240,280Q240,297 251.5,308.5Q263,320 280,320Z"),
    ("0 0 24 24", "M19,8.3Q18.875,8.3 18.738,8.225Q18.6,8.15 18.55,8L17.75,6.25L16,5.45Q15.85,5.4 15.775,5.262Q15.7,5.125 15.7,5Q15.7,4.875 15.775,4.737Q15.85,4.6 16,4.55L17.75,3.75L18.55,2Q18.6,1.85 18.738,1.775Q18.875,1.7 19,1.7Q19.125,1.7 19.263,1.775Q19.4,1.85 19.45,2L20.25,3.75L22,4.55Q22.15,4.6 22.225,4.737Q22.3,4.875 22.3,5Q22.3,5.125 22.225,5.262Q22.15,5.4 22,5.45L20.25,6.25L19.45,8Q19.4,8.15 19.263,8.225Q19.125,8.3 19,8.3ZM19,22.3Q18.875,22.3 18.738,22.225Q18.6,22.15 18.55,22L17.75,20.25L16,19.45Q15.85,19.4 15.775,19.262Q15.7,19.125 15.7,19Q15.7,18.875 15.775,18.738Q15.85,18.6 16,18.55L17.75,17.75L18.55,16Q18.6,15.85 18.738,15.775Q18.875,15.7 19,15.7Q19.125,15.7 19.263,15.775Q19.4,15.85 19.45,16L20.25,17.75L22,18.55Q22.15,18.6 22.225,18.738Q22.3,18.875 22.3,19Q22.3,19.125 22.225,19.262Q22.15,19.4 22,19.45L20.25,20.25L19.45,22Q19.4,22.15 19.263,22.225Q19.125,22.3 19,22.3ZM9,18.575Q8.725,18.575 8.475,18.425Q8.225,18.275 8.1,18L6.5,14.5L3,12.9Q2.725,12.775 2.575,12.525Q2.425,12.275 2.425,12Q2.425,11.725 2.575,11.475Q2.725,11.225 3,11.1L6.5,9.5L8.1,6Q8.225,5.725 8.475,5.575Q8.725,5.425 9,5.425Q9.275,5.425 9.525,5.575Q9.775,5.725 9.9,6L11.5,9.5L15,11.1Q15.275,11.225 15.425,11.475Q15.575,11.725 15.575,12Q15.575,12.275 15.425,12.525Q15.275,12.775 15,12.9L11.5,14.5L9.9,18Q9.775,18.275 9.525,18.425Q9.275,18.575 9,18.575ZM9,15.15 L10,13 12.15,12 10,11 9,8.85 8,11 5.85,12 8,13ZM9,12Z"),
    ("0 -960 960 960", "m603-202-34 97q-4 11-14 18t-22 7q-20 0-32.5-16.5T496-133l152-402q5-11 15-18t22-7h30q12 0 22 7t15 18l152 403q8 19-4 35.5T868-80q-13 0-22.5-7T831-106l-34-96H603ZM362-401 188-228q-11 11-27.5 11.5T132-228q-11-11-11-28t11-28l174-174q-35-35-63.5-80T190-640h84q20 39 40 68t48 58q33-33 68.5-92.5T484-720H80q-17 0-28.5-11.5T40-760q0-17 11.5-28.5T80-800h240v-40q0-17 11.5-28.5T360-880q17 0 28.5 11.5T400-840v40h240q17 0 28.5 11.5T680-760q0 17-11.5 28.5T640-720h-76q-21 72-63 148t-83 116l96 98-30 82-122-125Zm266 129h144l-72-204-72 204Z"),
    ("0 0 24 24", "M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z"),
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
      // Hong Kong and Macau write Traditional Chinese, like Taiwan.
      const tag = wanted.toLowerCase().replace(/^zh-(hant|hk|mo)\\b.*/, "zh-tw").replace(/^zh-hans\\b.*/, "zh-cn");
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
            f'        <div class="card"><svg viewBox="{box}" aria-hidden="true"><path d="{icon}"/></svg>'
            f'<div><h3>{h}</h3><p>{p}</p></div></div>'
            for (box, icon), (h, p) in zip(FEATURE_ICONS, s["cards"])
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
