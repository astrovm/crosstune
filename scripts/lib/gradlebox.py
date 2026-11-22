"""Find the Gradle box and Gradle module caches on this machine."""
import os
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


def kotlin_stdlib():
    """Find the Kotlin standard library in the module caches."""
    return next(jar for cache in gradle_caches() for jar in cache.glob('org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*[0-9].jar'))
