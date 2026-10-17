"""Exercise the native library check with libraries that do and don't depend on where they were built."""
import contextlib
import io
from pathlib import Path
import random
import tempfile
import unittest

import check_native_paths


class NativePathsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        build = self.root / "app/build.gradle.kts"
        build.parent.mkdir()
        build.write_text('    ndkVersion = "30.0.16248370"\n', encoding="utf-8")
        self.libraries = []
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
            library = self.root / f"app/build/intermediates/cxx/RelWithDebInfo/1e3p2771/obj/{abi}/libmilkdrop.so"
            library.parent.mkdir(parents=True)
            library.write_bytes(b"\x7fELF ./app/src/main/cpp /ndk/toolchains/llvm/prebuilt\x00")
            self.libraries.append(library)

    def test_mapped_paths_pass(self):
        self.assertEqual(check_native_paths.check_native_paths(self.root), sorted(self.libraries))

    def test_checkout_path_fails(self):
        self.libraries[1].write_bytes(b"\x00" + str(self.root).encode() + b"/app/src/main/cpp\x00")
        with self.assertRaisesRegex(ValueError, "armeabi-v7a.*the checkout's path"):
            check_native_paths.check_native_paths(self.root)

    def test_ndk_path_fails_wherever_the_sdk_is(self):
        for sdk in ("/usr/local/lib/android/sdk", "/opt/android-sdk", "C:/Android/Sdk"):
            with self.subTest(sdk=sdk):
                self.libraries[0].write_bytes(f"{sdk}/ndk/30.0.16248370/sysroot/usr/include".encode())
                with self.assertRaisesRegex(ValueError, "arm64-v8a.*the NDK's path"):
                    check_native_paths.check_native_paths(self.root)

    def test_other_ndk_versions_and_mapped_ndk_pass(self):
        self.libraries[0].write_bytes(b"/ndk/toolchains ndk/29.0.1/ ndk/30.0.16248370x")
        check_native_paths.check_native_paths(self.root)

    def test_every_problem_is_reported(self):
        for library in self.libraries:
            library.write_bytes(str(self.root).encode())
        with self.assertRaises(ValueError) as error:
            check_native_paths.check_native_paths(self.root)
        self.assertEqual(str(error.exception).count("the checkout's path"), 3)

    def test_leak_anywhere_in_random_bytes_is_found(self):
        rng = random.Random(7)
        leak = b"ndk/30.0.16248370/"
        for _ in range(100):
            before = rng.randbytes(rng.randint(0, 2000))
            after = rng.randbytes(rng.randint(0, 2000))
            self.libraries[0].write_bytes(before + leak + after)
            with self.assertRaisesRegex(ValueError, "the NDK's path"):
                check_native_paths.check_native_paths(self.root)

    def test_no_library_fails(self):
        for library in self.libraries:
            library.unlink()
        # Debug builds don't count: only the release one ships.
        debug = self.root / "app/build/intermediates/cxx/Debug/1e3p2771/obj/x86_64/libmilkdrop.so"
        debug.parent.mkdir(parents=True)
        debug.write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "No release native library"):
            check_native_paths.check_native_paths(self.root)

    def test_missing_or_ambiguous_ndk_version_fails(self):
        build = self.root / "app/build.gradle.kts"
        for source in ("", '// ndkVersion = "30.0.16248370"\n', 'ndkVersion = "1"\nndkVersion = "2"\n'):
            with self.subTest(source=source):
                build.write_text(source, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "ndkVersion"):
                    check_native_paths.check_native_paths(self.root)

    def test_cli_reports_github_error_and_success(self):
        self.libraries[0].write_bytes(str(self.root).encode())
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check_native_paths.main(self.root), 1)
        self.assertIn("::error::", errors.getvalue())
        self.libraries[0].write_bytes(b"")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(check_native_paths.main(self.root), 0)
        self.assertEqual(output.getvalue().count("Same wherever it's built"), 3)

    def test_cli_without_build_file_fails(self):
        (self.root / "app/build.gradle.kts").unlink()
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check_native_paths.main(self.root), 1)
        self.assertIn("::error::", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
