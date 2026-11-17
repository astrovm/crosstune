"""Test the production JNI bridge with deterministic decoder outcomes, without a model."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JNI = ROOT / "scripts/syncreplay/jni"


class NativeWhisperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.out = Path(cls.temp.name)
        javac = Path(shutil.which("javac")).resolve()
        java_home = javac.parent.parent
        subprocess.run([str(javac), "-d", str(cls.out), str(JNI / "com/astrovm/crosstune/NativeWhisper.java")], check=True)
        cls.lib = cls.out / "libwhisper_test.so"
        subprocess.run([
            "g++", "-std=c++17", "-shared", "-fPIC", "-Wl,--no-undefined",
            "-I" + str(java_home / "include"), "-I" + str(java_home / "include/linux"),
            "-I" + str(ROOT / "third_party/whisper.cpp/include"),
            "-I" + str(ROOT / "third_party/whisper.cpp/ggml/include"),
            str(ROOT / "app/src/main/cpp/sungwords.cpp"), str(JNI / "fake_whisper.cpp"),
            "-o", str(cls.lib),
        ], check=True)
        cls.java = str(java_home / "bin/java")

    def run_outcome(self, outcome):
        subprocess.run([self.java, "-Xcheck:jni", "-cp", str(self.out),
                        "com.astrovm.crosstune.NativeWhisper", str(self.lib), str(outcome)], check=True)

    def test_failure_returns_null(self):
        self.run_outcome(-1)

    def test_positive_failure_returns_null(self):
        self.run_outcome(2)

    def test_silence_returns_empty_array(self):
        self.run_outcome(0)

    def test_words_return_bytes(self):
        self.run_outcome(1)
