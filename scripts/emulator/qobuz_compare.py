#!/usr/bin/env python3
"""Two dev builds tried in turn with qobuz_try.py, on one device and one song, and what the difference comes to.
Pairs go base-head, head-base, so neither build always follows the same one, and every other swap needs no install.
A try that hits Qobuz's server errors, or can't set itself up, is kept in the raw log and doesn't count.
Stops at --pairs if head is no more than --margin worse, or else goes on to --max-pairs. Picks up from --out when run again.
Says too how much later head starts and first launches Qobuz than base, pair by pair, with a 95% interval, as an equal rate can hide every song starting late.
usage: qobuz_compare.py SERIAL BASE_APK HEAD_APK LINK WANT_TITLE --out FILE [--pairs 40] [--max-pairs 80] [--margin 0.05] [--then LINK --then-want TITLE]
"""
import argparse, hashlib, json, math, statistics, subprocess, sys
from pathlib import Path

APP = "com.astrovm.crosstune.dev"
QOBUZ = "com.qobuz.music"
LISTENER = f"{APP}/com.astrovm.crosstune.NowPlayingListener"
TRY = Path(__file__).with_name("qobuz_try.py")
Z = 1.959964
MAX_UNCOUNTED = 30


def wilson(hits, n):
    """The 95% Wilson interval of hits out of n."""
    if n == 0:
        return 0.0, 1.0
    p = hits / n
    middle = (p + Z * Z / (2 * n)) / (1 + Z * Z / n)
    half = Z * math.sqrt(p * (1 - p) / n + Z * Z / (4 * n * n)) / (1 + Z * Z / n)
    return max(0.0, middle - half), min(1.0, middle + half)


def newcombe(hits_a, n_a, hits_b, n_b):
    """a's rate minus b's, with its 95% Newcombe-Wilson interval (Newcombe 1998, method 10)."""
    p_a, p_b = hits_a / n_a, hits_b / n_b
    low_a, high_a = wilson(hits_a, n_a)
    low_b, high_b = wilson(hits_b, n_b)
    diff = p_a - p_b
    return diff, diff - math.hypot(p_a - low_a, high_b - p_b), diff + math.hypot(high_a - p_a, p_b - low_b)


def arm(index):
    """Which build the index-th counted try goes to: base, head, head, base, base, head..."""
    return "base" if (index % 4) in (0, 3) else "head"


def counted(result):
    return not result.get("error") and not result.get("pre_failed") and result.get("server_500", 0) < 2


def reached(result):
    """Whether the song asked for last played, and stayed: the older song back after a newer one is a failure."""
    if "then_right_at" in result:
        final = (result.get("final") or {}).get("song") or ""
        return result["then_right_at"] is not None and not result.get("reopened") and result.get("then_want", "").lower() in final.lower()
    return result.get("right_at") is not None


def first_launch(result):
    """When Qobuz was first asked to open a song, by the log's clock."""
    return next((event[0] for event in result.get("events", []) if event[1] == "START" and "qobuz" in event[2]), None)


def paired(tries, seconds):
    """head minus base of seconds(try), pair by pair: the median, the mean, and the mean's 95% interval, with Student's t by its Cornish-Fisher expansion."""
    pairs = {}
    for t in tries:
        if "index" in t:
            pairs.setdefault(t["index"] // 2, {})[t["arm"]] = seconds(t)
    gaps = [p["head"] - p["base"] for p in pairs.values() if p.get("head") is not None and p.get("base") is not None]
    if len(gaps) < 2:
        return None
    mean = statistics.fmean(gaps)
    half = (Z + (Z ** 3 + Z) / (4 * (len(gaps) - 1))) * statistics.stdev(gaps) / math.sqrt(len(gaps))
    return dict(pairs=len(gaps), median=round(statistics.median(gaps), 3), mean=round(mean, 3), low=round(mean - half, 3), high=round(mean + half, 3))


def summary(tries):
    kept = [t for t in tries if counted(t)]
    arms = {name: [t for t in kept if t["arm"] == name] for name in ("base", "head")}
    report = {"uncounted": len(tries) - len(kept)}
    for name, its in arms.items():
        times = [t["right_at"] for t in its if t.get("right_at") is not None]
        launches = [at for at in map(first_launch, its) if at is not None]
        report[name] = dict(tries=len(its), reached=sum(reached(t) for t in its), blocked=sum(t.get("blocked", 0) for t in its), median_s=statistics.median(times) if times else None,
                            first_launch_median_s=statistics.median(launches) if launches else None, first_launch_range_s=[min(launches), max(launches)] if launches else None)
    report["start_gap_s"] = paired(kept, lambda t: t.get("right_at"))
    report["first_launch_gap_s"] = paired(kept, first_launch)
    first = arms["head"][:20]
    report["head_first_20"] = dict(tries=len(first), reached=sum(reached(t) for t in first), blocked=sum(t.get("blocked", 0) for t in first))
    if arms["base"] and arms["head"]:
        diff, low, high = newcombe(report["head"]["reached"], len(arms["head"]), report["base"]["reached"], len(arms["base"]))
        report["head_minus_base"] = dict(diff=round(diff, 4), low=round(low, 4), high=round(high, 4))
    return report


def enough(report, pairs, max_pairs, margin):
    done = min(report["base"]["tries"], report["head"]["tries"])
    if done >= max_pairs:
        return True
    return done >= pairs and report["head_minus_base"]["low"] >= -margin


def main():
    p = argparse.ArgumentParser()
    p.add_argument("serial"); p.add_argument("base"); p.add_argument("head"); p.add_argument("link"); p.add_argument("want")
    p.add_argument("--out", required=True, type=Path); p.add_argument("--pairs", type=int, default=40); p.add_argument("--max-pairs", type=int, default=80)
    p.add_argument("--margin", type=float, default=0.05); p.add_argument("--then"); p.add_argument("--then-want"); p.add_argument("--then-after", default="3"); p.add_argument("--seconds", default="35")
    a = p.parse_args()
    apks = {"base": a.base, "head": a.head}

    def adb(*c, timeout=300):
        return subprocess.run(["adb", "-s", a.serial, *c], capture_output=True, text=True, timeout=timeout)

    def shell(*c):
        return adb("shell", *c).stdout.strip()

    def md5(path):
        return hashlib.md5(Path(path).read_bytes()).hexdigest()

    def install(name):
        apk = apks[name]
        out = adb("install", "-r", "-t", apk)
        if "UPDATE_INCOMPATIBLE" in out.stdout + out.stderr:
            adb("uninstall", APP)
            out = adb("install", "-t", apk)
        if "Success" not in out.stdout:
            sys.exit(f"install of {apk} failed: {out.stdout} {out.stderr}")
        shell("cmd", "notification", "allow_listener", LISTENER)
        for permission in ("android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO"):
            shell("pm", "grant", APP, permission)
        path = shell("pm", "path", APP).splitlines()[0].removeprefix("package:")
        if shell("md5sum", path).split()[0] != md5(apk):
            sys.exit(f"the device doesn't hold {apk}")

    tries = [json.loads(line) for line in a.out.read_text().splitlines()] if a.out.exists() else []
    volume = shell("cmd", "media_session", "volume", "--stream", "3", "--get").rsplit("volume is ", 1)[-1].split()[0]
    installed, uncounted = None, 0
    try:
        while True:
            report = summary(tries)
            index = sum(counted(t) for t in tries)
            if index % 2 == 0 and index and enough(report, a.pairs, a.max_pairs, a.margin):
                break
            name = arm(index)
            if installed != name:
                install(name)
                installed = name
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard")
            command = [sys.executable, str(TRY), a.serial, a.link, a.want, "--pre", "other", "--path", "straight", "--seconds", a.seconds]
            if a.then:
                command += ["--then", a.then, "--then-want", a.then_want, "--then-after", a.then_after]
            try:
                run = subprocess.run(command, capture_output=True, text=True, timeout=120)
                result = json.loads(run.stdout.strip().splitlines()[-1])
            except (subprocess.TimeoutExpired, IndexError, json.JSONDecodeError) as problem:
                result = dict(error=str(problem)[:400])
            result.update(arm=name, index=index)
            if a.then:
                result["then_want"] = a.then_want
            tries.append(result)
            with a.out.open("a") as out:
                out.write(json.dumps(result) + "\n")
            uncounted = 0 if counted(result) else uncounted + 1
            print(name, index, "reached" if reached(result) else "missed", result.get("right_at"), "blocked", result.get("blocked"), flush=True)
            if uncounted >= MAX_UNCOUNTED:
                sys.exit(f"{uncounted} tries in a row didn't count: stopping")
    finally:
        shell("am", "force-stop", QOBUZ)
        shell("cmd", "media_session", "volume", "--stream", "3", "--set", volume)
    print(json.dumps(summary(tries), indent=1))


if __name__ == "__main__":
    main()
