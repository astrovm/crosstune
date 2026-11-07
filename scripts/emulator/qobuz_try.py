#!/usr/bin/env python3
"""One try of a song link handed to Crosstune with Qobuz chosen: what Qobuz's media session plays, what Android started and blocked, by the log's clock.
usage: qobuz_try.py SERIAL LINK WANT_TITLE [--pre closed|other|none] [--pre-number N] [--path straight|open|play] [--seconds 35] [--log FILE] [--then LINK --then-want TITLE --then-after S]
With --then, a newer song is asked for S seconds after the first, and the older one back after it counts as reopened.
"""
import argparse, json, re, subprocess, sys, time
p = argparse.ArgumentParser()
p.add_argument("serial"); p.add_argument("link"); p.add_argument("want")
p.add_argument("--pre", default="closed"); p.add_argument("--pre-number", default="338184944")
p.add_argument("--seconds", type=float, default=35); p.add_argument("--log", default=None)
p.add_argument("--path", default="straight", choices=["straight","open","play"])
p.add_argument("--then"); p.add_argument("--then-want"); p.add_argument("--then-after", type=float, default=3)
a = p.parse_args()
if not a.serial.strip():
    sys.exit("no serial: refusing to run adb without an explicit device")
_seen = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=20).stdout
if not re.search(r"^" + re.escape(a.serial) + r"\s+device\b", _seen, re.M):
    sys.exit(f"{a.serial} isn't attached: refusing to run")
APP = "com.astrovm.crosstune.dev"; QOBUZ = "com.qobuz.music"

PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="play_right_away" value="{right_away}" />
    <boolean name="links_in_crosstune" value="{in_crosstune}" />
    <boolean name="setup_complete" value="true" />
    <boolean name="sources_preselected" value="true" />
    <boolean name="visuals_sized" value="true" />
    <string name="default_target">QOBUZ</string>
</map>
"""

def adb(*c, timeout=20):
    return subprocess.run(["adb", "-s", a.serial, *c], capture_output=True, text=True, timeout=timeout).stdout

def session():
    out = adb("shell", "dumpsys media_session")
    i = out.find("package=" + QOBUZ)
    if i < 0: return None
    j = out.find("package=", i + 10)
    b = out[i:j if j > 0 else len(out)]
    st = re.search(r"state=PlaybackState \{state=(\w+)\(\d+\)", b); act = re.search(r"\bactive=(\w+)", b)
    desc = re.search(r"description=(.*)", b); acts = re.search(r"actions=(\d+)", b)
    return dict(state=st.group(1) if st else None, active=act.group(1) if act else None, song=desc.group(1).strip() if desc else None, actions=acts.group(1) if acts else None)

def playing_wait(want=None, secs=25):
    t = time.time()
    while time.time() - t < secs:
        s = session()
        if s and s["state"] == "PLAYING" and (want is None or want.lower() in (s["song"] or "").lower()): return s
        time.sleep(.5)
    return None

# Quiet, and nothing in front.
prefs = PREFS.format(right_away="true" if a.path == "straight" else "false", in_crosstune="false" if a.path == "straight" else "true")
adb("shell", "am", "force-stop", APP)
subprocess.run(["adb", "-s", a.serial, "shell", "run-as", APP, "sh", "-c", "'mkdir -p shared_prefs; cat > shared_prefs/crosstune_preferences.xml'"], input=prefs, text=True, capture_output=True, timeout=20)
adb("shell", "cmd", "audio", "set-volume", "3", "0")
adb("shell", "am", "force-stop", APP)
if a.pre == "other":
    adb("shell", "am", "force-stop", QOBUZ); time.sleep(.5)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"https://open.qobuz.com/track/{a.pre_number}", "-p", QOBUZ)
    played = playing_wait(None, 15)
    if not played or a.want.lower() in (played["song"] or "").lower():
        adb("shell", "am", "force-stop", QOBUZ); print(json.dumps({"pre_failed": True, "server_500": 99})); sys.exit(2)
    time.sleep(1); adb("shell", "am", "force-stop", QOBUZ); adb("shell", "input", "keyevent", "KEYCODE_HOME")
if a.pre == "closed":
    adb("shell", "am", "force-stop", QOBUZ); adb("shell", "input", "keyevent", "KEYCODE_HOME")
elif a.pre == "none":
    adb("shell", "am", "force-stop", QOBUZ); adb("shell", "input", "keyevent", "KEYCODE_HOME")
time.sleep(2)

if a.path != "straight":
    adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", a.link, "-n", f"{APP}/com.astrovm.crosstune.MainActivity")
    time.sleep(12)
adb("logcat", "-c")
log = subprocess.Popen(["adb", "-s", a.serial, "logcat", "-v", "epoch", "ActivityTaskManager:I", "LoadTask:E", "*:S"], stdout=subprocess.PIPE, text=True)
import threading
lines = []; threading.Thread(target=lambda: [lines.append(l.rstrip()) for l in log.stdout], daemon=True).start()
t0 = float(adb("shell", "date", "+%s.%N").strip())
if a.path == "straight":
    adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", a.link, "-n", f"{APP}/com.astrovm.crosstune.MainActivity")
else:
    adb("shell", "input", "tap", "797" if a.path == "open" else "283", "1660")
trace, last = [], None
right = None
then_sent, then_right, reopened = False, None, False
while True:
    now = float(adb("shell", "date", "+%s.%N").strip()) - t0
    if now > a.seconds: break
    if sum("session/start, code=500" in l for l in lines) >= 2: break
    if right is not None and now > right + 3 and not a.then: break
    if a.then and not then_sent and now >= a.then_after:
        adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", a.then, "-n", f"{APP}/com.astrovm.crosstune.MainActivity")
        then_sent = True
    s = session()
    key = (s or {}).get("state"), (s or {}).get("song")
    if key != last:
        trace.append((round(now, 1), *key)); last = key
    if right is None and key[0] == "PLAYING" and a.want.lower() in (key[1] or "").lower():
        right = round(now, 1)
    if then_sent and then_right is None and key[0] == "PLAYING" and a.then_want.lower() in (key[1] or "").lower():
        then_right = round(now, 1)
    if then_right is not None and key[0] in ("PLAYING", "BUFFERING") and a.want.lower() in (key[1] or "").lower():
        reopened = True
    time.sleep(.3)
adb("shell", "am", "force-stop", QOBUZ) if sum("session/start, code=500" in l for l in lines) >= 2 else None
time.sleep(.5); log.terminate()
server_500 = sum("session/start, code=500" in l for l in lines)
events = []
for l in lines:
    m = re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+ActivityTaskManager:\s*(.*)", l)
    if not m: continue
    t, msg = float(m.group(1)) - t0, m.group(2)
    if "START u0" in msg and ("qobuz" in msg.lower() or APP in msg):
        d = re.search(r"dat=(\S+)", msg); c = re.search(r"cmp=(\S+)", msg)
        events.append((round(t, 1), "START", d.group(1) if d else "", c.group(1) if c else ""))
    if "Background activity launch blocked" in msg or "Abort background activity" in msg or "bal" in msg.lower() and "blocked" in msg.lower():
        events.append((round(t, 1), "BLOCKED", msg[:1600]))
if a.log:
    open(a.log, "a").write("\n".join(lines) + "\n")
final = session()
newer = dict(then_right_at=then_right, reopened=reopened) if a.then else {}
print(json.dumps(dict(**newer, server_500=server_500, right_at=right, blocked=sum(1 for e in events if e[1] == "BLOCKED"), trace=trace, events=events, final=final, shows=adb("shell", "dumpsys activity activities").split("topResumedActivity=")[-1].split("\n")[0][:80])))
