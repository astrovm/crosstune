#!/usr/bin/env python3
"""Builds the website in docs/ from site/ templates and site/strings/<lang>.json.

English goes at the root and every other language under /<lang>/. Run it after editing
anything in site/, then commit docs/ too:  python3 scripts/build-site.py
"""
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
DOCS = ROOT / "docs"
BASE = "https://crosstune.4st.li"
# URL folder for each strings file, in the order the language picker lists them.
LANGS = ["en", "es", "pt-br", "de", "fr", "it", "nl", "pl", "ru", "tr", "id", "hi", "ja", "ko", "zh-cn"]
HEART = '<svg class="heart" viewBox="0 0 960 960" width="14" height="14" role="img" aria-label="love"><path fill="#e34b5f" d="M480,840Q466,840 451.5,835Q437,830 426,819L357,756Q251,659 165.5,563.5Q80,468 80,354Q80,260 143,197Q206,134 300,134Q353,134 400,156.5Q447,179 480,218Q513,179 560,156.5Q607,134 660,134Q754,134 817,197Q880,260 880,354Q880,468 794.5,564Q709,660 602,757L534,819Q523,830 508.5,835Q494,840 480,840Z"/></svg>'


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
            "made_with": s["made_with"].replace("{heart}", HEART),
        }
        cards = "\n".join(f'        <div class="card"><h3>{h}</h3><p>{p}</p></div>' for h, p in s["cards"])
        out = DOCS if code == "en" else DOCS / code
        out.mkdir(parents=True, exist_ok=True)
        (out / "index.html").write_text(fill(index, {
            **text, **common, "cards": cards,
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
