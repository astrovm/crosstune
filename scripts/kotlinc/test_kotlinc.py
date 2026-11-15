import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import kotlinc  # noqa: E402  the module next to this test

POM = '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>{}</dependencies></project>'


def dependency(group, artifact, version, scope=None, optional=False):
    return ("<dependency>" f"<groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version>"
            + (f"<scope>{scope}</scope>" if scope else "") + ("<optional>true</optional>" if optional else "") + "</dependency>")


class Checkout:
    """A fake checkout: a build file at [version] and a lock with one jar per name in [jars]."""

    def __init__(self, folder, version, jars, compiler=None):
        self.root = Path(folder)
        (self.root / "build.gradle.kts").write_text(f'plugins {{ id("org.jetbrains.kotlin.plugin.compose") version "{version}" }}\n')
        self.jars = {name: name.encode() * 3 for name in jars}
        rows = [(("org.jetbrains.kotlin", "kotlin-compiler-embeddable", compiler or version), hashlib.sha256(b"compiler").hexdigest())]
        self.jars["kotlin-compiler-embeddable"] = b"compiler"
        rows += [(("org.example", name, "1.0"), hashlib.sha256(content).hexdigest()) for name, content in self.jars.items()
                 if name != "kotlin-compiler-embeddable"]
        self.lock = self.root / "kotlinc.lock"
        self.lock.write_text(kotlinc.lock_text(rows, version))
        self.rows = rows

    def content(self, url):
        return next(content for name, content in self.jars.items() if f"/{name}/" in url)


class LockTest(unittest.TestCase):
    def test_the_lock_is_read_without_its_comments(self):
        rows = kotlinc.read_lock("# a comment\n\ngroup.a:art:1.0 abc\n")
        self.assertEqual([(("group.a", "art", "1.0"), "abc")], rows)

    def test_the_committed_lock_holds_the_compiler_the_app_builds_with(self):
        rows = kotlinc.locked()
        self.assertIn((*kotlinc.COMPILER, kotlinc.kotlin_version()), [coordinates for coordinates, _ in rows])
        self.assertTrue(all(len(digest) == 64 for _, digest in rows))

    def test_a_lock_from_another_kotlin_says_how_to_lock_again(self):
        with tempfile.TemporaryDirectory() as folder:
            checkout = Checkout(folder, "2.5.0", [], compiler="2.4.21")
            with self.assertRaises(SystemExit) as stopped:
                kotlinc.locked(checkout.root, checkout.lock)
        self.assertIn("Kotlin 2.5.0", str(stopped.exception))
        self.assertIn("kotlinc.py lock", str(stopped.exception))

    def test_a_build_file_without_a_kotlin_version_is_named(self):
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "build.gradle.kts").write_text("plugins {}\n")
            with self.assertRaises(SystemExit) as stopped:
                kotlinc.kotlin_version(Path(folder))
        self.assertIn("build.gradle.kts", str(stopped.exception))


class ResolveTest(unittest.TestCase):
    def test_every_jar_it_runs_with_at_the_highest_version_named(self):
        poms = {
            ("g", "top", "1"): POM.format(dependency("g", "left", "1") + dependency("g", "right", "1")
                                          + dependency("g", "tests", "1", scope="test") + dependency("g", "extra", "1", optional=True)),
            ("g", "left", "1"): POM.format(dependency("g", "shared", "1.9")),
            ("g", "right", "1"): POM.format(dependency("g", "shared", "1.10", scope="runtime")),
            ("g", "shared", "1.9"): POM.format(""),
            ("g", "shared", "1.10"): POM.format(""),
        }
        self.assertEqual([("g", "left", "1"), ("g", "right", "1"), ("g", "shared", "1.10"), ("g", "top", "1")],
                         kotlinc.resolve(("g", "top", "1"), poms.__getitem__))

    def test_a_version_from_a_property_is_not_guessed(self):
        with self.assertRaises(SystemExit) as stopped:
            kotlinc.dependencies(POM.format(dependency("g", "named", "${version}")))
        self.assertIn("named", str(stopped.exception))

    def test_locking_checks_maven_centrals_sha1(self):
        with tempfile.TemporaryDirectory() as folder:
            Checkout(folder, "9.9", [])
            files = {"kotlin-compiler-embeddable/9.9/kotlin-compiler-embeddable-9.9.pom": POM.format("").encode(),
                     "kotlin-compiler-embeddable/9.9/kotlin-compiler-embeddable-9.9.jar": b"compiler"}

            def read(url, sha1=hashlib.sha1(b"compiler").hexdigest()):
                if url.endswith(".sha1"):
                    return f"{sha1}  file\n".encode()
                return next(content for name, content in files.items() if url.endswith(name))

            lock = Path(folder) / "kotlinc.lock"
            with mock.patch.object(kotlinc, "LOCK", lock):
                rows = kotlinc.lock(Path(folder), read)
                self.assertEqual([((*kotlinc.COMPILER, "9.9"), hashlib.sha256(b"compiler").hexdigest())], rows)
                self.assertEqual(rows, kotlinc.read_lock(lock.read_text()))
                with self.assertRaises(SystemExit) as stopped:
                    kotlinc.lock(Path(folder), lambda url: read(url, sha1="0" * 40))
        self.assertIn("sha1", str(stopped.exception))


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.checkout = Checkout(self.folder.name, "9.9", ["kept", "cached"])
        self.into = Path(self.folder.name) / "jars"
        environment = mock.patch.dict(os.environ, {"CROSSTUNE_KOTLINC_DIR": str(self.into)})
        environment.start()
        self.addCleanup(environment.stop)
        lock = mock.patch.object(kotlinc, "LOCK", self.checkout.lock)
        lock.start()
        self.addCleanup(lock.stop)

    def fetch(self, caches=(), get=None):
        def unreachable(url, target):
            raise OSError("no network")
        return kotlinc.fetch(self.checkout.root, list(caches), get or unreachable)

    def cache(self, name, content):
        jar = Path(self.folder.name) / "cache" / "org.example" / name / "1.0" / "f00" / f"{name}-1.0.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(content)
        return Path(self.folder.name) / "cache"

    def test_jars_come_from_the_folder_then_gradle_then_maven(self):
        self.into.mkdir()
        (self.into / "kept-1.0.jar").write_bytes(self.checkout.jars["kept"])
        cache = self.cache("cached", self.checkout.jars["cached"])
        downloaded = []

        def get(url, target):
            downloaded.append(url)
            target.write_bytes(self.checkout.content(url))

        jars = self.fetch([cache], get)
        self.assertEqual(["kotlin-compiler-embeddable-9.9.jar", "kept-1.0.jar", "cached-1.0.jar"], [jar.name for jar in jars])
        self.assertEqual([kotlinc.maven_url((*kotlinc.COMPILER, "9.9"), "jar")], downloaded)
        self.assertTrue(all(kotlinc.sha256(jar) == digest for jar, (_, digest) in zip(jars, self.checkout.rows)))
        self.assertEqual(jars, self.fetch())

    def test_a_cached_jar_with_another_hash_is_not_used(self):
        cache = self.cache("cached", b"changed")
        with self.assertRaises(SystemExit) as stopped:
            self.fetch([cache])
        self.assertIn("Maven Central can't be reached", str(stopped.exception))
        self.assertIn("gradle-box.sh", str(stopped.exception))

    def test_a_download_with_another_hash_is_thrown_away(self):
        with self.assertRaises(SystemExit) as stopped:
            self.fetch(get=lambda url, target: target.write_bytes(b"changed"))
        self.assertIn("does not have the hash", str(stopped.exception))
        self.assertEqual([], list(self.into.iterdir()))

    def test_a_changed_jar_in_the_folder_is_fetched_again(self):
        self.into.mkdir()
        (self.into / "kept-1.0.jar").write_bytes(b"changed")
        jars = self.fetch(get=lambda url, target: target.write_bytes(self.checkout.content(url)))
        self.assertEqual(self.checkout.jars["kept"], jars[1].read_bytes())

    def test_the_classpath_joins_the_jars(self):
        with mock.patch.object(kotlinc, "fetch", return_value=[Path("/a.jar"), Path("/b.jar")]):
            self.assertEqual(f"/a.jar{os.pathsep}/b.jar", kotlinc.classpath())


class PlacesTest(unittest.TestCase):
    def test_gradle_caches_are_the_box_slots_its_seed_and_the_users_own(self):
        with tempfile.TemporaryDirectory() as home:
            box = Path(home) / "box"
            slots = [box / "1" / "caches" / "modules-2" / "files-2.1", box / "seed" / "modules-2" / "files-2.1",
                     Path(home) / "gradle" / "caches" / "modules-2" / "files-2.1"]
            for slot in slots:
                slot.mkdir(parents=True)
            with mock.patch.dict(os.environ, {"GRADLE_BOX_DIR": str(box), "GRADLE_USER_HOME": str(Path(home) / "gradle")}):
                self.assertEqual(slots, kotlinc.gradle_caches(home))
            with mock.patch.dict(os.environ, {}, clear=True):
                self.assertEqual([], kotlinc.gradle_caches(Path(home) / "empty"))

    def test_the_folder_is_in_the_checkout_unless_one_is_named(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(kotlinc.ROOT / "build" / "kotlinc" / kotlinc.kotlin_version(), kotlinc.folder())
        with mock.patch.dict(os.environ, {"CROSSTUNE_KOTLINC_DIR": "/somewhere"}):
            self.assertEqual(Path("/somewhere"), kotlinc.folder())

    def test_a_download_leaves_no_part_behind(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "source.jar"
            source.write_bytes(b"jar")
            target = Path(folder) / "target.jar"
            kotlinc.download(source.as_uri(), target)
            self.assertEqual(b"jar", target.read_bytes())
            self.assertEqual({"source.jar", "target.jar"}, {path.name for path in Path(folder).iterdir()})


class MainTest(unittest.TestCase):
    def test_each_command(self):
        jars = [Path("/a.jar")]
        with mock.patch.object(kotlinc, "fetch", return_value=jars), mock.patch("builtins.print") as printed:
            self.assertEqual(0, kotlinc.main(["fetch"]))
            self.assertEqual(0, kotlinc.main(["path"]))
        self.assertEqual([mock.call(Path("/a.jar")), mock.call("/a.jar")], printed.call_args_list)
        with mock.patch.object(kotlinc, "lock", return_value=[(("g", "a", "1"), "abc")]), mock.patch("builtins.print") as printed:
            self.assertEqual(0, kotlinc.main(["lock"]))
        printed.assert_called_once_with("g:a:1", "abc")


if __name__ == "__main__":
    unittest.main()
