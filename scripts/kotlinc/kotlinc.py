#!/usr/bin/env python3
"""The Kotlin compiler's jars, at the version the app builds with, each checked against its hash in kotlinc.lock.

  python3 scripts/kotlinc/kotlinc.py fetch   puts the jars in build/kotlinc/VERSION, or in $CROSSTUNE_KOTLINC_DIR
  python3 scripts/kotlinc/kotlinc.py path    prints them as a classpath
  python3 scripts/kotlinc/kotlinc.py lock    after a Kotlin bump: what the compiler needs, from Maven Central, hashed

A jar comes from Gradle's caches when one there has the locked hash, and from Maven Central when none does. The readings
guard (scripts/readingsaudit) parses with this compiler, and syncreplay compiles with it.
"""
import argparse
import hashlib
import os
import re
import shutil
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCK = Path(__file__).with_name("kotlinc.lock")
MAVEN = "https://repo1.maven.org/maven2"
COMPILER = ("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
POM = "{http://maven.apache.org/POM/4.0.0}"
# The scopes a jar needs to run with, as Maven names them.
RUN_SCOPES = ("compile", "runtime", None)


def kotlin_version(root=ROOT):
    """The Kotlin the app builds with, from its build file."""
    found = re.search(r'kotlin\.plugin\.compose"\) version "([^"]+)"', (root / "build.gradle.kts").read_text(encoding="utf-8"))
    if not found:
        sys.exit(f"{root / 'build.gradle.kts'} names no Kotlin version on its compose plugin line")
    return found.group(1)


def read_lock(text):
    """The locked jars: ((group, artifact, version), sha256) for each line that is not a comment."""
    rows = []
    for line in text.splitlines():
        if line.strip() and not line.startswith("#"):
            coordinates, digest = line.split()
            group, artifact, version = coordinates.split(":")
            rows.append(((group, artifact, version), digest))
    return rows


def locked(root=ROOT, lock=None):
    """The lock's jars, once the lock holds the compiler the build uses."""
    lock = lock or LOCK
    rows = read_lock(lock.read_text(encoding="utf-8"))
    compiler = next((version for (group, artifact, version), _ in rows if (group, artifact) == COMPILER), None)
    wanted = kotlin_version(root)
    if compiler != wanted:
        sys.exit(f"The app builds with Kotlin {wanted}, and {lock.relative_to(root) if lock.is_relative_to(root) else lock} holds compiler {compiler}.\n"
                 f"Lock it again: python3 scripts/kotlinc/kotlinc.py lock")
    return rows


def file_name(coordinates):
    _group, artifact, version = coordinates
    return f"{artifact}-{version}.jar"


def folder(root=ROOT):
    """Where the jars go: $CROSSTUNE_KOTLINC_DIR, or build/kotlinc/VERSION in the checkout."""
    named = os.environ.get("CROSSTUNE_KOTLINC_DIR")
    return Path(named) if named else root / "build" / "kotlinc" / kotlin_version(root)


def gradle_caches(home=None):
    """Gradle's module caches on this machine: gradle-box's slots and seed, and the user's own Gradle."""
    home = Path(home or Path.home())
    box = Path(os.environ.get("GRADLE_BOX_DIR") or Path(os.environ.get("XDG_CACHE_HOME") or home / ".cache") / "crosstune-gradle-box")
    user = Path(os.environ.get("GRADLE_USER_HOME") or home / ".gradle")
    found = [*sorted(box.glob("*/caches/modules-2/files-2.1")), box / "seed" / "modules-2" / "files-2.1", user / "caches" / "modules-2" / "files-2.1"]
    return [cache for cache in found if cache.is_dir()]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def maven_url(coordinates, extension):
    group, artifact, version = coordinates
    return f"{MAVEN}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{extension}"


def download(url, target):
    """[url] into [target], through a file next to it, so a stopped download leaves no jar."""
    request = urllib.request.Request(url, headers={"User-Agent": "crosstune-kotlinc"})
    with urllib.request.urlopen(request, timeout=60) as response, tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as part:
        shutil.copyfileobj(response, part)
    Path(part.name).replace(target)


def fetch(root=ROOT, caches=None, get=download):
    """The locked jars in [folder], each with its locked hash: kept, copied from Gradle's caches, or downloaded."""
    rows = locked(root)
    into = folder(root)
    into.mkdir(parents=True, exist_ok=True)
    caches = gradle_caches() if caches is None else caches
    jars = []
    for coordinates, digest in rows:
        target = into / file_name(coordinates)
        if not (target.is_file() and sha256(target) == digest):
            group, artifact, version = coordinates
            cached = [jar for cache in caches for jar in cache.glob(f"{group}/{artifact}/{version}/*/{file_name(coordinates)}")]
            source = next((jar for jar in cached if sha256(jar) == digest), None)
            if source:
                shutil.copyfile(source, target)
            else:
                try:
                    get(maven_url(coordinates, "jar"), target)
                except OSError as failure:
                    sys.exit(f"{file_name(coordinates)} is in no Gradle cache here and Maven Central can't be reached ({failure}).\n"
                             "Build the app once with scripts/gradle-box/gradle-box.sh, or run this again online.")
                if sha256(target) != digest:
                    target.unlink()
                    sys.exit(f"{maven_url(coordinates, 'jar')} does not have the hash {LOCK.name} holds for it: {digest}")
        jars.append(target)
    return jars


def classpath(root=ROOT):
    return os.pathsep.join(str(jar) for jar in fetch(root))


def dependencies(pom_text):
    """The jars a POM's artifact runs with: ((group, artifact), version) for each, its optional and test ones left out."""
    found = []
    project = ElementTree.fromstring(pom_text)
    for dependency in project.findall(f"{POM}dependencies/{POM}dependency"):
        scope = dependency.findtext(f"{POM}scope")
        if scope not in RUN_SCOPES or dependency.findtext(f"{POM}optional") == "true":
            continue
        version = dependency.findtext(f"{POM}version") or ""
        if "$" in version or not version:
            sys.exit(f"A POM names {dependency.findtext(f'{POM}artifactId')} with version {version!r}, which this does not read")
        found.append(((dependency.findtext(f"{POM}groupId"), dependency.findtext(f"{POM}artifactId")), version))
    return found


def version_key(version):
    return [int(part) if part.isdigit() else part for part in re.split(r"[.-]", version)]


def resolve(start, poms):
    """Every jar [start] runs with, read from [poms] (coordinates to POM text). A jar named at two versions takes the
    higher one, as Gradle does."""
    chosen = {start[:2]: start[2]}
    changed = True
    while changed:
        changed = False
        for (group, artifact), version in list(chosen.items()):
            for module, wanted in dependencies(poms((group, artifact, version))):
                if module not in chosen or version_key(wanted) > version_key(chosen[module]):
                    chosen[module] = wanted
                    changed = True
    return sorted((group, artifact, version) for (group, artifact), version in chosen.items())


def read_url(url):
    request = urllib.request.Request(url, headers={"User-Agent": "crosstune-kotlinc"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def lock_text(rows, version):
    lines = [f"# The Kotlin {version} compiler and what it runs with, from its POMs. Remake with: python3 scripts/kotlinc/kotlinc.py lock",
             "# group:artifact:version sha256"]
    return "\n".join(lines + [f"{':'.join(coordinates)} {digest}" for coordinates, digest in rows]) + "\n"


def lock(root=ROOT, read=read_url):
    """Locks the compiler the build uses: each jar's sha256, checked against Maven Central's own sha1 of it."""
    version = kotlin_version(root)
    rows = []
    for coordinates in resolve((*COMPILER, version), lambda found: read(maven_url(found, "pom"))):
        jar = read(maven_url(coordinates, "jar"))
        if hashlib.sha1(jar).hexdigest() != read(maven_url(coordinates, "jar.sha1")).decode().split()[0]:
            sys.exit(f"{maven_url(coordinates, 'jar')} does not match Maven Central's sha1 for it")
        rows.append((coordinates, hashlib.sha256(jar).hexdigest()))
    LOCK.write_text(lock_text(rows, version), encoding="utf-8")
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("fetch", "path", "lock"))
    args = parser.parse_args(argv)
    if args.command == "lock":
        for coordinates, digest in lock():
            print(":".join(coordinates), digest)
    elif args.command == "fetch":
        for jar in fetch():
            print(jar)
    else:
        print(classpath())
    return 0


if __name__ == "__main__":
    sys.exit(main())
