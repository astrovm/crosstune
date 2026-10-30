"""How far from where each line is really sung the replay showed it: python3 compare.py DIR SONG FROM"""
import json, statistics, sys
d, song, frm = sys.argv[1:4]
r = json.load(open(f"{d}/{song}.from{frm}.shown.json"))
truth = {}
for name in ("truth2", "truth"):
    try:
        for l in open(f"/home/astro/.cache/crosstune-sync/work/{song}.{name}.jsonl"):
            t = json.loads(l); truth.setdefault(name, {})[t["line"]] = t["start"] * 1000
    except FileNotFoundError: pass
best = truth.get("truth2") or truth.get("truth")
# The app's line index to the n-th line with words.
nth, n = {}, 0
for i, text in enumerate(r["lines"]):
    if text.strip(): nth[i] = n; n += 1
first, after = {}, -1
for e in r["shown"]:
    k = nth.get(e["line"])
    if k is not None and k not in first and e["at"] > after: first[k] = e["at"]; after = e["at"]
lags = []
for k in sorted(best):
    if k in first:
        lag = (first[k] - best[k]) / 1000; lags.append(lag)
        print("%2d sung %6.1f shown %6.1f lag %+5.1f %s" % (k, best[k] / 1000, first[k] / 1000, lag, r["lines"][[i for i in nth if nth[i] == k][0]][:34]))
    else: print("%2d sung %6.1f never shown" % (k, best[k] / 1000))
on = [x for x in lags if abs(x) <= 0.5]
print(f"{song} from {frm}: median |lag| {statistics.median(abs(x) for x in lags):.2f} s, within 0.5 s: {len(on)}/{len(best)}")
# Each time the lines were placed anew: how far each line with a known time is off then.
if len(sys.argv) > 4:
    for p in r.get("placed", []):
        offs = [(k, (p["times"][i] - best[k]) / 1000) for i, k in nth.items() if k in best]
        print("at %6.1f: " % (p["at"] / 1000) + " ".join("%d:%+.1f" % (k, o) for k, o in offs))
# Lines sung once the words were first put right: what can be right as it plays.
fixed = next((p["at"] for p in r.get("placed", [])[1:]), None)
if fixed is not None:
    live = [(first[k] - best[k]) / 1000 for k in sorted(best) if k in first and best[k] > fixed]
    print(f"after the first fix at {fixed/1000:.1f} s: median |lag| {statistics.median(abs(x) for x in live):.2f} s, within 0.5 s: {sum(abs(x) <= 0.5 for x in live)}/{len(live)}, worst {max(live, key=abs):+.1f} s")
