# Development guide

## Prerequisites

| Tool | Version / requirement |
| --- | --- |
| Java | JDK 17 |
| Android SDK | Platform 35 and Build Tools 35.0.0 |
| Gradle | 9.3.1, downloaded by the included wrapper |
| Android Gradle Plugin | 8.12.0, pinned in the build |
| Python | Python 3, for the macOS/Linux packaging script |

Install the SDK components through Android Studio's SDK Manager, or use an installed `sdkmanager`:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
```

Set `ANDROID_HOME` to your SDK directory and `JAVA_HOME` to your JDK 17 directory. On macOS, `build-android.sh` uses the existing Homebrew SDK/JDK paths when these variables are unset.

## Build and install

From the repository root on macOS/Linux:

```sh
bash build-android.sh
adb install -r outputs/InstantTranslate-v0.5.2-debug.apk
```

The script runs JVM tests, lint, and the debug build, verifies the APK signature, and creates a versioned APK plus an allowlisted source ZIP in `outputs/`.

For Android Studio, open the `android/` directory. For direct Gradle builds:

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

On Windows, use `gradlew.bat` with the same tasks. Direct Gradle builds write the APK to `android/build/outputs/apk/debug/InstantTranslate-debug.apk`.

## Tests

| Checks | How to run | Coverage |
| --- | --- | --- |
| JVM tests | `./gradlew testDebugUnitTest` | Source changes, frame motion, overlay masks, and scroll settling |
| Device tests | `./gradlew connectedDebugAndroidTest` | RGBA row padding, crop dimensions/colors, and usable OCR input |
| Android lint | `./gradlew lintDebug` | API compatibility and Android build checks |

Run these commands from `android/`. Device tests require a connected device or emulator. Current evidence: 19 JVM tests and 3 device tests passed; lint has zero errors and 21 warnings. See the [verification record](VERIFICATION.md) for versions and environments.

[GitHub Actions](../.github/workflows/android.yml) runs the JVM tests, lint, APK build, and instrumentation APK compilation on pushes and pull requests. It does not run device tests. Reports are uploaded as workflow artifacts.

## Code map

| File | Responsibility |
| --- | --- |
| [MainActivity](../android/src/main/java/com/instanttranslate/app/MainActivity.java) | Permissions, capture consent, and session controls |
| [OverlayService](../android/src/main/java/com/instanttranslate/app/OverlayService.java) | Capture lifecycle, OCR, translation caching, and overlay/helper windows |
| [FrameBitmap](../android/src/main/java/com/instanttranslate/app/FrameBitmap.java) | Copies RGBA frames safely, including missing final-row padding |
| [RefreshPolicy](../android/src/main/java/com/instanttranslate/app/RefreshPolicy.java) | Waits for 300 ms of quiet before an automatic scan |
| [ScreenChangeDetector](../android/src/main/java/com/instanttranslate/app/ScreenChangeDetector.java) | Compares source/frame samples while excluding the app's overlays |
| [TranslationAccessibilityService](../android/src/main/java/com/instanttranslate/app/TranslationAccessibilityService.java) | Optional content events and fully opaque overlay hosting |
| [ComposeActivity](../android/src/main/java/com/instanttranslate/app/ComposeActivity.java) | English-to-Chinese messages, private drafts, and source-matched copying |

The service briefly hides its own windows to capture original pixels. ML Kit recognizes Chinese lines, cached results appear first, and new translations render as they finish. Unchanged label views are reused.

## Models and network use

Chinese OCR uses the bundled `com.google.mlkit:text-recognition-chinese:16.0.1` model. Translation uses `com.google.mlkit:translate:17.0.3`; its language models download on first use. Both translation directions run on-device after download. Common seller questions use built-in shopping phrases. A cloud translation provider is not configured.

## Versions and signing

Update `APP_VERSION_CODE` and `APP_VERSION_NAME` in [gradle.properties](../android/gradle.properties) for an app release. Documentation-only updates do not change the app version.

Fresh clones use Android's generated debug key. If a private local key exists in `work/android-build`, the build reuses it to keep local installs updatable. Signing files, caches, APKs, fixtures, and build folders are ignored by Git. Keep any future release signing key outside the published repository; debug signing is for development and demo installs.

## Contributions

Keep changes focused and explain their user-visible behavior. Run the JVM tests and lint before submitting a pull request; run device tests for frame-conversion changes. For capture/lifecycle fixes, record the Android version and reproduction steps. Do not commit screenshots containing personal account data or private signing/build files.

The [code review](../CODE_REVIEW.md) lists unresolved release findings. Historical behavior and checks are in the [changelog](CHANGELOG.md) and [verification record](VERIFICATION.md).
