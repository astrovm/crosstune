"""Find the Gradle box and Gradle module caches on this machine."""
import os
import re
import sys
from pathlib import Path


def box_dir(home=None):
    home = Path(home or Path.home())
    return Path(os.environ.get('GRADLE_BOX_DIR') or Path(os.environ.get('XDG_CACHE_HOME') or home / '.cache') / 'crosstune-gradle-box')


def gradle_caches(home=None):
    """Existing module caches: box slots, seed, then the user's Gradle home."""
    home = Path(home or Path.home())
    box = box_dir(home)
    user = Path(os.environ.get('GRADLE_USER_HOME') or home / '.gradle')
    found = [*sorted(box.glob('*/caches/modules-2/files-2.1')), box / 'seed/modules-2/files-2.1', user / 'caches/modules-2/files-2.1']
    return [cache for cache in found if cache.is_dir()]


def kotlin_version(root):
    """The Kotlin the app builds with, from its build file."""
    build = Path(root) / 'build.gradle.kts'
    found = re.search(r'kotlin\.plugin\.compose"\) version "([^"]+)"', build.read_text(encoding='utf-8'))
    if not found:
        sys.exit(f'{build} names no Kotlin version on its compose plugin line')
    return found.group(1)


def kotlin_stdlib(root):
    """The Kotlin standard library at the version the app in root builds with, from the module caches.

    A slot can keep an older Kotlin from an earlier build, so the version is matched, not just the first jar found."""
    version = kotlin_version(root)
    name = f'kotlin-stdlib-{version}.jar'
    caches = gradle_caches()
    found = next((jar for cache in caches for jar in cache.glob(f'org.jetbrains.kotlin/kotlin-stdlib/{version}/*/{name}')), None)
    if found is None:
        sys.exit(f'No {name} in {", ".join(map(str, caches)) or "any Gradle cache"}: build the app once with scripts/gradle-box/gradle-box.sh')
    return found
