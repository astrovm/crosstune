"""adbtool against made-up SDK folders and a stand-in adb that writes what it was asked."""
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import adbtool

FAKE_ADB = '#!/bin/sh\necho "adb $*"\n'


class AdbToolTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.dir = Path(folder.name)
        self.home = self.dir / "home"
        self.home.mkdir()
        environment = {key: value for key, value in os.environ.items() if key not in ("ADB", "ANDROID_HOME", "ANDROID_SDK_ROOT")}
        patcher = mock.patch.dict(os.environ, {**environment, "HOME": str(self.home)}, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def sdk(self, place: Path) -> Path:
        adb = place / "platform-tools/adb"
        adb.parent.mkdir(parents=True)
        adb.write_text(FAKE_ADB)
        adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
        return adb

    def test_adb_env_comes_first(self):
        self.sdk(self.home / "Android/Sdk")
        with mock.patch.dict(os.environ, {"ADB": "/opt/adb"}):
            self.assertEqual("/opt/adb", adbtool.path())

    def test_the_named_sdk_comes_before_android_studios(self):
        self.sdk(self.home / "Android/Sdk")
        named = self.sdk(self.dir / "named")
        with mock.patch.dict(os.environ, {"ANDROID_HOME": str(self.dir / "named")}):
            self.assertEqual(str(named), adbtool.path())
            self.assertEqual(self.dir / "named", adbtool.sdk())
        with mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(self.dir / "named")}):
            self.assertEqual(str(named), adbtool.path())

    def test_a_named_sdk_is_the_only_one_looked_in(self):
        self.sdk(self.home / "Android/Sdk")
        with mock.patch.dict(os.environ, {"ANDROID_HOME": str(self.dir / "empty")}):
            self.assertIsNone(adbtool.sdk())
            with mock.patch.object(adbtool.shutil, "which", return_value="/usr/bin/adb"):
                self.assertEqual("/usr/bin/adb", adbtool.path())

    def test_a_macs_sdk_is_found(self):
        mac = self.sdk(self.home / "Library/Android/sdk")
        self.assertEqual(str(mac), adbtool.path())

    def test_adb_comes_from_the_path_without_an_sdk(self):
        self.assertIsNone(adbtool.sdk())
        with mock.patch.object(adbtool.shutil, "which", return_value="/usr/bin/adb"):
            self.assertEqual("/usr/bin/adb", adbtool.path())
        with mock.patch.object(adbtool.shutil, "which", return_value=None), self.assertRaises(SystemExit) as stopped:
            adbtool.path()
        self.assertIn("platform-tools", str(stopped.exception))

    def test_run_names_the_device_and_passes_its_arguments(self):
        self.sdk(self.home / "Android/Sdk")
        result = adbtool.run("shell", "echo", serial="emulator-5554", capture_output=True, text=True, check=True)
        self.assertEqual("adb -s emulator-5554 shell echo\n", result.stdout)
        self.assertEqual(b"adb devices\n", adbtool.run("devices", capture_output=True, check=True).stdout)

    def test_the_one_online_samsung_is_picked(self):
        listing = ("List of devices attached\n"
                   "emulator-5554 device product:sdk model:sdk_gphone64 device:emu64 transport_id:2\n"
                   "fake-device (2)._adb-tls._tcp device product:x model:SM_S908E device:x transport_id:1\n")
        self.assertEqual("fake-device (2)._adb-tls._tcp", adbtool.samsung_serial(listing))

    def test_no_samsung_or_two_or_an_offline_one_is_refused(self):
        online = "R5CT device product:x model:SM_S908E transport_id:1\n"
        for listing in ("", online + online.replace("R5CT", "R5CU"), online.replace(" device ", " offline ")):
            with self.subTest(listing=listing), self.assertRaises(ValueError):
                adbtool.samsung_serial(listing)


if __name__ == "__main__":
    unittest.main()
