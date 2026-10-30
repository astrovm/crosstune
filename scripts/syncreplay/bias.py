"""How much later than it's really sung whisper.cpp tiny says each word starts, in each 20 s heard:
python3 bias.py SONG (needs work/SONG.truth.jsonl.words.json and replay/SONG.windows.json)"""
import json, re, statistics, sys, unicodedata
song = sys.argv[1]
def norm(w): return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", w.lower()))
truth = [(norm(w["word"]), w["start"] * 1000) for w in json.load(open(f"work/{song}.truth.jsonl.words.json"))]
windows = json.load(open(f"replay/{song}.windows.json"))
per = []
for start, words in sorted(windows.items(), key=lambda x: int(x[0])):
    s = int(start)
    heard = [(norm(w), s + ms) for ms, w in words if ms >= 0 and norm(w)]
    offs = []
    for w, at in heard:
        near = [t for tw, t in truth if tw == w and abs(t - at) < 1500]
        if near: offs.append(at - min(near, key=lambda t: abs(t - at)))
    if len(offs) >= 5: per.append((s, statistics.median(offs), len(offs)))
for s, m, n in per[::5]: print(f"from {s/1000:6.1f}s late {m:+5.0f} ms over {n} words")
print(song, "median late", statistics.median(m for _, m, _ in per), "ms; spread", statistics.quantiles([m for _, m, _ in per], n=10)[0], statistics.quantiles([m for _, m, _ in per], n=10)[-1])
