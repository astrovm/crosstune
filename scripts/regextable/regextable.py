#!/usr/bin/env python3
"""What Android's Regex reads each character as, measured on an emulator, and the Kotlin sets the readers take from it.

    python3 scripts/regextable/regextable.py probe [--serial SERIAL]   # run RegexTableProbe on the emulator, keep its table
    python3 scripts/regextable/regextable.py generate [TABLE]         # write PhoneRegexTable.kt and PhoneRegexSets.kt from a kept table
    python3 scripts/regextable/regextable.py diff A B                 # list where two tables differ
    python3 scripts/regextable/regextable.py patterns [--serial SERIAL] [--base REF]   # each app pattern on both engines

On Android, kotlin.text.Regex runs on ICU, which reads `\\s`, `\\w`, `\\d`, `\\b`, `.`, `$` and case otherwise than the
JVM the tests run on. `probe` compiles RegexTableProbe.kt in the Gradle box, dexes it with d8, runs it on the
emulator with app_process, and keeps what it prints as app/src/test/regextable/android-API.tsv, its first line naming
the ICU and Android versions. It runs the probe on this machine's JVM too, and lists where that differs. Then it
writes PhoneRegexTable.kt from the table, with the sets the app reads, and PhoneRegexSets.kt in the tests, with those
only the tests' pattern writer reads. Make it again on a new Android version, and keep the newest as the rule.

Take the emulator first: python3 scripts/devicelock.py take crosstune-phone --who "regex table" --minutes 15.
It finds the running crosstune-phone emulator by its AVD name, or uses --serial. It never runs on a phone: it
refuses a serial that isn't an emulator.

`patterns` runs each of the app's patterns over the fuzz titles with PatternsProbe, on the emulator and on this
machine's JVM. It keeps the emulator's digests as app/src/test/regextable/patterns-android-API.tsv, which
PatternsOnThePhoneTest holds the JVM's written-out patterns to, and lists each pattern the JVM reads otherwise than
the phone, marked "touched" where its text isn't one of REF's (origin/main by default).

It needs adb, d8 in $ANDROID_HOME/build-tools, and the Gradle box. It can't start the emulator: use
scripts/emulator/emulator.py start.
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
TABLES = ROOT / "app/src/test/regextable"
KOTLIN = ROOT / "app/src/main/java/com/astrovm/crosstune/PhoneRegexTable.kt"
# The sets only the tests' pattern writer reads, kept out of the app.
TEST_KOTLIN = ROOT / "app/src/test/java/com/astrovm/crosstune/PhoneRegexSets.kt"
# The sets the app reads itself: the rest are TEST_KOTLIN's.
APP_SETS = {"PHONE_SPACE", "PHONE_DIGIT", "PHONE_LINE_BREAK", "PHONE_LATIN_IGNORING_CASE", "PHONE_WORD_EDGE", "PHONE_NOT_WORD_EDGE"}
CLASSES = ROOT / "app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
MAIN_CLASS = "com.astrovm.crosstune.RegexTableProbe"
PATTERNS_CLASS = "com.astrovm.crosstune.PatternsProbe"
APP_CLASSES = ROOT / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
# The test classes PatternsProbe runs with on the emulator.
PATTERNS_TESTS = ("PatternsProbe", "TitleFuzz", "TitleProbes", "RegexTableProbe")
DEVICE_JAR = "/data/local/tmp/regex-probe.jar"
DEVICE_TABLE = "/data/local/tmp/regex-table.tsv"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
AVD = "crosstune-phone"

# The sets the readers take, by name in PhoneRegexTable.kt, from the table's rows: a class row, or what makes one.
SETS = {
    "PHONE_SPACE": ("`\\s`", "\\s"),
    "PHONE_WORD": ("`\\w`", "\\w"),
    "PHONE_DIGIT": ("`\\d`", "\\d"),
    "PHONE_LETTER": ("`\\p{L}`", "\\p{L}"),
    "PHONE_MARK": ("`\\p{M}`", "\\p{M}"),
    "PHONE_NUMBER": ("`\\p{N}`", "\\p{N}"),
    "PHONE_SPACE_SEPARATOR": ("`\\p{Zs}`", "\\p{Zs}"),
    "PHONE_NONSPACING_MARK": ("`\\p{Mn}`", "\\p{Mn}"),
    "PHONE_ENCLOSING_MARK": ("`\\p{Me}`", "\\p{Me}"),
    "PHONE_FORMAT": ("`\\p{Cf}`", "\\p{Cf}"),
    "PHONE_HAN": ("`\\p{sc=Han}`", "\\p{sc=Han}"),
    "PHONE_HANGUL": ("`\\p{sc=Hangul}`", "\\p{sc=Hangul}"),
    "PHONE_HIRAGANA": ("`\\p{sc=Hiragana}`", "\\p{sc=Hiragana}"),
    "PHONE_KATAKANA": ("`\\p{sc=Katakana}`", "\\p{sc=Katakana}"),
    "PHONE_LATIN": ("`\\p{sc=Latin}`", "\\p{sc=Latin}"),
    "PHONE_LINE_BREAK": ("What `.` doesn't read, and `$` reads as a line's end", "!."),
    "PHONE_LATIN_IGNORING_CASE": ("`[a-z]` ignoring case", "(?i)[a-z]"),
    "PHONE_WORD_EDGE": ("A word letter that `\\b` reads, where a word starts", "\\b@0"),
    "PHONE_NOT_WORD_EDGE": ("A letter that `\\b` reads, where a word ends", "\\b@a"),
}


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout


def load(path):
    """The table at [path], as {(kind, name): [values]}, and its engine."""
    rows, engine = {}, "?"
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        kind, name, *rest = line.split("\t")
        if kind == "# engine":
            engine = name
        else:
            rows[(kind, name)] = rest
    return rows, engine


def codes(ranges):
    found = set()
    for part in filter(None, ranges.split(",")):
        start, _, end = part.partition("-")
        found.update(range(int(start, 16), int(end or start, 16) + 1))
    return found


def ranges(found):
    runs, found = [], sorted(found)
    at = 0
    while at < len(found):
        end = at
        while end + 1 < len(found) and found[end + 1] == found[end] + 1:
            end += 1
        runs.append("%x" % found[at] if at == end else "%x-%x" % (found[at], found[end]))
        at = end + 1
    return ",".join(runs)


def shown(found, most=12):
    runs = ranges(found).split(",") if found else []
    return " ".join(runs[:most]).upper() + (f" ...({len(runs)} runs)" if len(runs) > most else "")


def diff(first, second):
    """Prints where tables [first] and [second] differ: each class, fold and line end. Gives how many differ."""
    a, engine_a = load(first)
    b, engine_b = load(second)
    print(f"A: {engine_a}\nB: {engine_b}")
    count = 0
    for key in sorted(set(a) | set(b)):
        if a.get(key) == b.get(key):
            continue
        count += 1
        kind, name = key
        if kind in ("class", "fold"):
            in_a, in_b = codes(a.get(key, [""])[0]), codes(b.get(key, [""])[0])
            print(f"{kind} {name}: A only {len(in_a - in_b)}: {shown(in_a - in_b)} | B only {len(in_b - in_a)}: {shown(in_b - in_a)}")
        elif kind == "ends":
            text = " ".join(f"U+{int(c, 16):04X}" for c in name.split(" "))
            print(f"ends {text}: A {' '.join(a.get(key, ['none']))} | B {' '.join(b.get(key, ['none']))}")
        else:
            print(f"{kind} {name}: {'A only' if key not in b else 'B only' if key not in a else 'differs'}")
    print(f"{count} rows differ")
    return count


def generate(table):
    rows, engine = load(table)
    every = codes("0-10ffff")
    made = "// Made by scripts/regextable/regextable.py from app/src/test/regextable/" + Path(table).name + ": make it again, don't edit it."
    lines = [
        made,
        "package com.astrovm.crosstune",
        "",
        "/** The engine the table was measured on. */",
        f'internal const val PHONE_ENGINE = "{engine}"',
    ]
    test_lines = [made, "package com.astrovm.crosstune"]
    for name, (what, row) in SETS.items():
        # A row the table doesn't have yet is none, so the app builds for the probe that measures it.
        held = rows.get(("class", row.lstrip("!")))
        if held is None:
            print(f"{name}: no {row} in {table}, so none until it's probed")
        found = set() if held is None else every - codes(held[0]) if row.startswith("!") else codes(held[0])
        (lines if name in APP_SETS else test_lines).extend(
            ["", f"/** {what} on a phone, as code point runs in hex. */", f'internal const val {name} = "{ranges(found)}"']
        )
    simple = rows[("sfold", "-")][0].replace(">", ":")
    full = ",".join(f"{code}:{values[0]}" for (kind, code), values in rows.items() if kind == "multi")
    lines += [
        "",
        "/** The letters a pattern ignoring case reads as another one, as \"code:fold\" in hex: ICU's simple case folds. */",
        f'internal const val PHONE_SIMPLE_FOLDS = "{simple}"',
        "",
        "/** The letters a pattern ignoring case reads as more than one, as \"code:folds\" in hex: \"df:73 73\" is \"ß\" as \"ss\". */",
        f'internal const val PHONE_FULL_FOLDS = "{full}"',
        "",
    ]
    KOTLIN.write_text("\n".join(lines), encoding="utf-8")
    TEST_KOTLIN.write_text("\n".join(test_lines + [""]), encoding="utf-8")
    print(f"{KOTLIN.relative_to(ROOT)} and {TEST_KOTLIN.relative_to(ROOT)}: from {engine}")


def emulator(serial):
    if serial:
        if not serial.startswith("emulator-"):
            sys.exit(f"{serial} isn't an emulator: the table is made on one, never on a phone")
        return serial
    for line in run("adb", "devices").splitlines()[1:]:
        found = line.split("\t")[0]
        if found.startswith("emulator-") and AVD in run("adb", "-s", found, "emu", "avd", "name"):
            return found
    sys.exit(f"No {AVD} emulator is running: start it with scripts/emulator/emulator.py start {AVD}")


def probe(serial):
    serial = emulator(serial)
    env = dict(os.environ, GRADLE_BOX_MEMORY=os.environ.get("GRADLE_BOX_MEMORY", "7"))
    subprocess.run(["nice", "-n", "10", "scripts/gradle-box/gradle-box.sh", "--max-workers=2", ":app:compileDebugUnitTestKotlin"], cwd=ROOT, env=env, check=True)
    stdlib = next(Path.home().glob(".cache/crosstune-gradle-box/seed/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*[0-9].jar"))
    tools = sorted((SDK / "build-tools").iterdir())[-1]
    platform = sorted((SDK / "platforms").iterdir())[-1] / "android.jar"
    probe_classes = sorted(str(path) for path in (CLASSES / "com/astrovm/crosstune").glob("RegexTableProbe*.class"))
    work = Path(tempfile.mkdtemp(prefix="regextable-", dir=os.environ.get("REGEXTABLE_TMP")))
    try:
        run(str(tools / "d8"), "--release", "--min-api", "26", "--lib", str(platform), "--output", str(work / "probe.jar"), *probe_classes, str(stdlib))
        run("adb", "-s", serial, "push", str(work / "probe.jar"), DEVICE_JAR)
        run("adb", "-s", serial, "shell", f"app_process -cp {DEVICE_JAR} /system/bin {MAIN_CLASS} {DEVICE_TABLE}")
        run("adb", "-s", serial, "pull", DEVICE_TABLE, str(work / "android.tsv"))
        run("adb", "-s", serial, "shell", f"rm -f {DEVICE_JAR} {DEVICE_TABLE}")
        engine = load(work / "android.tsv")[1]
        table = TABLES / f"android-{engine.rsplit('API ', 1)[1]}.tsv"
        TABLES.mkdir(parents=True, exist_ok=True)
        shutil.copy(work / "android.tsv", table)
        print(f"{table.relative_to(ROOT)}: {engine}")
        run("java", "-cp", f"{CLASSES}{os.pathsep}{stdlib}", MAIN_CLASS, str(work / "jvm.tsv"))
        diff(work / "jvm.tsv", table)
        generate(table)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def compiled():
    env = dict(os.environ, GRADLE_BOX_MEMORY=os.environ.get("GRADLE_BOX_MEMORY", "7"))
    subprocess.run(["nice", "-n", "10", "scripts/gradle-box/gradle-box.sh", "--max-workers=2", ":app:compileDebugUnitTestKotlin"], cwd=ROOT, env=env, check=True)
    stdlib = next(Path.home().glob(".cache/crosstune-gradle-box/seed/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*[0-9].jar"))
    tools = sorted((SDK / "build-tools").iterdir())[-1]
    platform = sorted((SDK / "platforms").iterdir())[-1] / "android.jar"
    return stdlib, tools, platform


def key(pattern, options):
    """PatternsProbe.key: a pattern and its options, by name."""
    return hashlib.sha1((",".join(sorted(filter(None, options.split(",")))) + "\n" + pattern).encode("utf-8")).hexdigest()[:16]


def unescaped(text):
    return re.sub(r"\\(.)", lambda found: {"t": "\t", "n": "\n", "r": "\r"}.get(found.group(1), found.group(1)), text)


def listed(path):
    """{key: where} for each pattern a `PatternsProbe list` wrote."""
    found = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        where, options, pattern = line.split("\t")
        found[key(unescaped(pattern), options)] = where
    return found


def patterns(serial, base):
    serial = emulator(serial)
    stdlib, tools, platform = compiled()
    work = Path(tempfile.mkdtemp(prefix="regextable-", dir=os.environ.get("REGEXTABLE_TMP")))
    jvm = ["java", "-cp", os.pathsep.join([str(CLASSES), str(APP_CLASSES), str(stdlib)]), PATTERNS_CLASS]
    try:
        run(*jvm, "list", str(ROOT / "app/src/main/java"), str(work / "head.tsv"))
        (work / "base").mkdir()
        archive = subprocess.run(["git", "archive", base, "app/src/main/java"], cwd=ROOT, check=True, capture_output=True).stdout
        subprocess.run(["tar", "-x", "-C", str(work / "base")], input=archive, check=True)
        run(*jvm, "list", str(work / "base/app/src/main/java"), str(work / "base.tsv"))
        classes = work / "classes"
        shutil.copytree(APP_CLASSES, classes)
        for name in PATTERNS_TESTS:
            for found in (CLASSES / "com/astrovm/crosstune").glob(f"{name}*.class"):
                shutil.copy(found, classes / "com/astrovm/crosstune" / found.name)
        shutil.make_archive(str(work / "classes"), "zip", classes)
        ids = next(ROOT.glob("app/build/intermediates/*r_class_jar/debug/*/R.jar"))
        run(str(tools / "d8"), "--release", "--min-api", "26", "--lib", str(platform), "--output", str(work / "dex.jar"), str(work / "classes.zip"), str(ids), str(stdlib))
        device = "/data/local/tmp/patterns-"
        run("adb", "-s", serial, "push", str(work / "dex.jar"), device + "probe.jar")
        run("adb", "-s", serial, "push", str(work / "head.tsv"), device + "list.tsv")
        try:
            run("adb", "-s", serial, "shell", f"app_process -cp {device}probe.jar /system/bin {PATTERNS_CLASS} digest {device}list.tsv {device}out.tsv")
            run("adb", "-s", serial, "pull", device + "out.tsv", str(work / "android.tsv"))
        finally:
            run("adb", "-s", serial, "shell", f"rm -f {device}probe.jar {device}list.tsv {device}out.tsv")
        run(*jvm, "digest", str(work / "head.tsv"), str(work / "jvm.tsv"))
        engine = (work / "android.tsv").read_text(encoding="utf-8").splitlines()[0].split("\t")[1]
        kept = TABLES / f"patterns-android-{engine.rsplit('API ', 1)[1]}.tsv"
        shutil.copy(work / "android.tsv", kept)
        print(f"{kept.relative_to(ROOT)}: {engine}")
        head, old = listed(work / "head.tsv"), listed(work / "base.tsv")
        phone = dict(line.split("\t") for line in (work / "android.tsv").read_text(encoding="utf-8").splitlines()[1:])
        there = dict(line.split("\t") for line in (work / "jvm.tsv").read_text(encoding="utf-8").splitlines()[1:])
        differ = sorted(head[k] for k in head if phone[k] != there[k])
        for where in differ:
            touched = next(k for k in head if head[k] == where) not in old
            print(f"{where}: the JVM reads it otherwise{' (touched)' if touched else ''}")
        touched = [k for k in head if k not in old]
        print(f"{len(head)} patterns, {len(touched)} touched since {base}; the JVM reads {len(differ)} otherwise than the phone, "
              f"{sum(1 for k in touched if phone[k] != there[k])} of them touched")
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("probe").add_argument("--serial")
    commands.add_parser("generate").add_argument("table", nargs="?", default=str(TABLES / "android-37.tsv"))
    both = commands.add_parser("patterns")
    both.add_argument("--serial")
    both.add_argument("--base", default="origin/main")
    compare = commands.add_parser("diff")
    compare.add_argument("first")
    compare.add_argument("second")
    args = parser.parse_args()
    if args.command == "probe":
        probe(args.serial)
    elif args.command == "patterns":
        patterns(args.serial, args.base)
    elif args.command == "generate":
        generate(args.table)
    else:
        diff(args.first, args.second)


if __name__ == "__main__":
    main()
