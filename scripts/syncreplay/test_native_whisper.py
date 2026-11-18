"""Test the production JNI bridge with deterministic decoder outcomes, without a model."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JNI = ROOT / "scripts/syncreplay/jni"


class NativeWhisperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.out = Path(cls.temp.name)
        java_home = os.environ.get("JAVA_HOME")
        if java_home is None and sys.platform == "darwin":
            java_home = subprocess.check_output(["/usr/libexec/java_home"], text=True).strip()
        if java_home is None:
            javac = shutil.which("javac")
            if javac is None:
                raise RuntimeError("The native Whisper test needs a JDK with javac")
            java_home = Path(javac).resolve().parent.parent
        jdk = Path(java_home)
        compiler = shutil.which("g++")
        if compiler is None:
            raise RuntimeError("The native Whisper test needs a C++ compiler")
        subprocess.run([str(jdk / "bin/javac"), "-d", str(cls.out), str(JNI / "com/astrovm/crosstune/NativeWhisper.java")], check=True)
        platform_include = "darwin" if sys.platform == "darwin" else "linux"
        link = ["-dynamiclib"] if sys.platform == "darwin" else ["-shared", "-Wl,--no-undefined"]
        extension = ".dylib" if sys.platform == "darwin" else ".so"
        cls.lib = cls.out / f"libwhisper_test{extension}"
        subprocess.run([
            compiler, "-std=c++17", "-fPIC", *link,
            "-I" + str(jdk / "include"), "-I" + str(jdk / "include" / platform_include),
            "-I" + str(ROOT / "third_party/whisper.cpp/include"),
            "-I" + str(ROOT / "third_party/whisper.cpp/ggml/include"),
            str(ROOT / "app/src/main/cpp/sungwords.cpp"), str(JNI / "fake_whisper.cpp"),
            "-o", str(cls.lib),
        ], check=True)
        cls.java = str(jdk / "bin/java")

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
