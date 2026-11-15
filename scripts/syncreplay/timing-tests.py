#!/usr/bin/env python3
"""Run the real timing JUnit tests without an Android build, in the bounded replay container.

Uses the Kotlin compiler and JUnit jars already in the Gradle box cache.
SYNCREPLAY_DIR has the same meaning as in syncreplay.py. All outputs stay ignored.
"""
import argparse
from pathlib import Path
import shutil
import subprocess

import syncreplay as replay

TEST_FOLDER = replay.ROOT / 'app/src/test/java/com/astrovm/crosstune'
TEST_SOURCES = ('SungTimingTest.kt', 'SyntheticSinging.kt', 'SyntheticSingingTest.kt')
TEST_CLASSES = ('SungTimingTest', 'SyntheticSingingTest')


def junit_jars():
    """Copy only the existing test libraries. Never mount a host Gradle home in a test container."""
    folder = replay.own() / 'unit-libs'
    folder.mkdir(exist_ok=True)
    caches = (Path.home() / '.cache/crosstune-gradle-box', Path.home() / '.gradle')
    jars = []
    for name in ('junit-4.13.2.jar', 'hamcrest-core-1.3.jar'):
        target = folder / name
        if not target.exists():
            source = next((jar for cache in caches if cache.exists() for jar in cache.rglob(name)), None)
            if source is None:
                raise SystemExit(f'No {name} in Gradle caches. Run scripts/gradle-box/gradle-box.sh :app:testDebugUnitTest first.')
            shutil.copy(source, target)
        jars.append(target)
    return jars


def run(timing=None):
    if shutil.which('podman') is None:
        raise SystemExit('Podman is required to run the timing tests in a container.')
    sources = [replay.ROOT / name for name in replay.UNTIMED_TIMING] + [TEST_FOLDER / name for name in TEST_SOURCES]
    if timing:
        sources = replay.timing_copied(timing, sources)
    compiler = replay.kotlin_jars()
    libraries = [next(jar for jar in compiler if jar.name.startswith('kotlin-stdlib-')), *junit_jars()]
    classpath = ':'.join(map(str, libraries))
    key = replay.kotlin_cache_key([*sources, *libraries])
    container = replay.replay_container((replay.ROOT, replay.ROOT, 'ro'), (replay.OUT, replay.OUT, 'ro'), (replay.own(), replay.own(), 'rw'), (compiler[0].parent, compiler[0].parent, 'ro'))

    def build(classes):
        subprocess.run(container + ['java', '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                                   '-no-stdlib', '-no-reflect', '-cp', classpath, '-d', str(classes), *map(str, sources)], check=True)

    classes = replay.compiled_cache(replay.own() / 'unit-classes-v2', key, build)
    subprocess.run(container + ['java', '-cp', f'{classes}:{classpath}', 'org.junit.runner.JUnitCore',
                               *(f'com.astrovm.crosstune.{name}' for name in TEST_CLASSES)], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timing', help='another SungTiming.kt to check with the unchanged tests')
    run(parser.parse_args().timing)
