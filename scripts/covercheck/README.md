# Cover encoding measurement

The instrumentation runner calls the app's real bitmap-cover reader on the main thread with one made-up 1024x1024 cover. It needs no account or network. It measures the first read, thirty immediate reads, and one hundred reads after the file exists. The runner saves one fake cover in the dev app's normal artwork cache. Remove that file after the run.

Build the dev APK separately from the test APK, in the Gradle box:

```
scripts/gradle-box/gradle-box.sh :app:assembleDebug
scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest
```

After installing both APKs on the locked test device, use the SDK adb to run the project test runner:

```
adb shell am instrument -w com.astrovm.crosstune.dev.test/com.astrovm.crosstune.CoverEncodingCheck
```

The output reports nanoseconds spent in the main-thread reader. The returned cover filename is the fake file to remove. Keep the screen asleep outside UI checks. Drive all UI with the command from T3 `device_open`.
