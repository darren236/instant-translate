# Instant Translate — Android screen overlay

Instant Translate translates visible Chinese text over other Android apps. It does not contain a browser. Android screen capture feeds on-device ML Kit Chinese OCR and Chinese-to-English translation; English text appears at the detected text location. The overlay passes taps through to the app underneath.

## Install and use

Build the APK with `bash build-android.sh` (see the prerequisites below).

1. Install `outputs/InstantTranslate-v0.5.2-debug.apk` on Android 8.0 or newer (`adb install -r outputs/InstantTranslate-v0.5.2-debug.apk`).
2. Open Instant Translate and tap **Start translating**.
3. For solid text replacement, expand **Setup & permissions**, tap **Improve text overlays** and enable Instant Translate in Android Accessibility settings. Return to the app. If you leave this off, Android will ask for **Display over other apps** and use a partially transparent fallback overlay.
4. Allow notifications if you want a Stop control in the notification drawer. Approve the Android screen-capture prompt. Android 14 and newer require fresh consent for each capture session.
5. Open Taobao or another app as usual. After scrolling stops, English appears over recognized Chinese. The translation stays in place while the screen is unchanged. Links, scrolling, and form controls still receive taps.
6. Drag the small white duck to move it; it snaps to the nearest edge and remembers its side and height. Tap it for **Pause / Resume translation**, **Write in Chinese**, **Refresh translation**, and **Close helper**. Close helper ends the translation session and removes the duck, menu, and translated overlays. The menu shows the last scan result, such as the number of translated lines or a capture error. **Write in Chinese** is also available on the home screen. Quick starts translate common shopping questions in one tap. Your draft and matching result are saved locally between visits. The fixed action stays above the keyboard, and **Copy & return** copies the Chinese and backgrounds the app so you can paste into the shopping app. Editing the message disables stale copying until it is translated again.

The first session may download ML Kit translation models. The Chinese OCR model is bundled in the APK. After the model downloads, OCR and both translation directions run on the device. Captured frames are processed in memory and are not saved or sent to a translation server. The app still needs a network connection for websites and for the initial language-model downloads. Common seller questions in the composer use natural shopping phrases; other messages use ML Kit. A context-aware cloud translator is not configured because it would need a provider and API credentials.

The optional Accessibility service hosts a fully opaque overlay that still passes touches through. It does not read Android's accessibility view hierarchy or perform gestures. Screen capture still requires separate Android consent. The fallback overlay is partially transparent because Android limits the opacity of ordinary overlays that pass touches to other apps.

## Build from source

Run `bash build-android.sh` on macOS/Linux. Alternatively, open `android/` in Android Studio or run `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` (`gradlew.bat` on Windows). The build requires JDK 17, Android SDK platform 35, build tools 35.0.0, Gradle 9.3.1, and Android Gradle Plugin 8.12.0. The script uses Homebrew defaults for `ANDROID_HOME` and `JAVA_HOME` if those variables are unset. Fresh clones use Android’s automatically generated debug key. If a private key already exists under `work/android-build`, the build reuses it to preserve update compatibility with local installs. It signs a debug APK and writes a versioned APK and source ZIP under `outputs/`. The script runs unit tests and lint before packaging; GitHub Actions runs the build, lint, and JVM tests on pushes and pull requests, and compiles the Android instrumentation APK. With an emulator or device connected, run `cd android && ./gradlew connectedDebugAndroidTest` for the bitmap regression tests. The wrapper downloads the pinned Gradle version; a separate Gradle installation is not needed. Update `APP_VERSION_CODE` and `APP_VERSION_NAME` in `android/gradle.properties` for the next release. The debug key is not suitable for release signing.

The Android app uses `com.google.mlkit:text-recognition-chinese:16.0.1` and `com.google.mlkit:translate:17.0.3`. It does not call a per-character translation service or need an API key. The tradeoffs are the initial model download, a larger APK, battery use from repeated screen OCR, and machine-translation errors.

## How it works

`MainActivity` obtains Android's screen-sharing consent and starts `OverlayService`. The service samples frame changes, uses `RefreshPolicy` to wait for scrolling/motion to settle, and hides its own windows briefly before taking a source frame. `FrameBitmap` copies RGBA pixels safely, ML Kit detects Chinese text, and cached or completed translations are drawn at the detected text positions. Unchanged label views are reused.

`TranslationAccessibilityService` supplies optional content-change signals and a solid overlay host without reading the view hierarchy. `ComposeActivity` separately manages English-to-Chinese drafts, quick shopping phrases, and copying a source-matched result back to the shopping app. Language models are downloaded once, then both directions run on-device.

## Known limits

This is a portfolio prototype. See the [current code review](CODE_REVIEW.md) for unresolved release findings, including Google attribution placement for the live overlay.

- Translations begin at the OCR block's detected screen coordinates, use a nearby sampled background color, and shrink to fit the detected text area. Long results can still be shortened, and OCR bounds may be imperfect. Google attribution is displayed in the composer and app information screen. The floating duck and its menu have no provider badge or label.
- The overlay scans after scrolling, a new window, an accessibility content update, or a visible source change. It waits for 0.3 seconds of quiet; sustained scrolling no longer forces a scan every 0.9 seconds. A tap alone does not force a rescan. Translations stay visible while text is unchanged, and completed lines reuse existing views instead of rebuilding the whole overlay. A static page can be captured from its first available frame; capture callbacks no longer discard that frame while waiting for the overlay to hide. Visual detection also runs during OCR and inference, and upper-screen changes can trigger a scan. Our own overlay rectangles are excluded from visual comparison to avoid scans caused by translated labels. With Accessibility disabled, changes entirely hidden beneath existing translated patches may require **Refresh translation**; screen capture cannot see through those patches. Continuously animated pixels or content events can prevent the quiet window and delay automatic scans; use **Refresh translation** in that case.
- Small, stylized, or moving text may still be missed or mistranslated by OCR. The overlay translates detected Chinese lines, including mixed-language lines, without a fixed 16-block limit. Results appear as they complete; isolated single-character detections and web addresses are ignored to avoid false overlays. It does not alter the original app's content.
- Android excludes protected screens from screen capture. System dialogs and some apps may hide overlays or stop capture. The session ends if Android revokes screen capture or the device is locked.
- Android requires screen-capture consent and either Accessibility for the solid overlay or display-over-apps for the fallback. The screen-capture prompt appears at every new session on Android 14 and newer.
- A true system-wide live overlay is not available to an ordinary third-party iOS app. This deliverable is Android only.

## Changes in v0.5.2

- Frame conversion supports absent final-row padding and keeps the OCR bitmap alive when a crop shares its input.
- Invalidated readers and older OCR errors no longer update a new capture cycle. Failed OCR submission releases its bitmap.
- Added three Android bitmap regression tests; CI compiles their instrumentation APK.

## Changes in v0.5.1

- Removed the Google attribution badge from the floating helper and provider wording from its menu.
- Added an explicit **Close helper** action that ends the session and removes all app overlays.

## Changes in v0.5.0

- Scrollable, shorter home screen with Start, Continue, and separate Stop actions. Setup details are optional and collapsed. Notification denial does not block translation or repeat each session.
- Smaller 56 dp duck touch target, animated edge docking, remembered position, and clear active/preparing/paused indicators. The menu puts Pause/Resume and writing first and closes after inactivity.
- Refreshes wait until motion/scrolling stops. Cached translations appear quickly; unchanged label views and text layout measurements are reused. No periodic rescan on a screen with no Chinese text.
- Composer quick starts, saved drafts/results, fixed keyboard-safe footer, source-matched copying, and return to the shopping app after copying. Back from a duck-launched composer also returns to the underlying app.

## Verification

v0.5.2: APK build/signature verification and 19 JVM tests pass. Three bitmap instrumentation tests pass on Android 14 (arm64), covering unpadded rows, padded rows, and absent final-row padding. Lint reports zero errors and 21 warnings. Live Taobao and supported-version/lifecycle coverage remain limited as described in the code review.

v0.5.1: APK build, signature verification, and 19 integrated JVM tests pass. Android lint reports zero errors and 19 warnings. Source inspection confirms no floating attribution view or provider wording in the helper menu; Close helper uses the existing session shutdown path. No new connected-device check was performed for this UI update.

v0.5.0: build, signature verification, and 19 integrated JVM tests pass. Android lint reports zero errors and 20 warnings. Tests cover source changes, frame motion and overlay masks, and waiting through sustained scrolling. Android 14 emulator checks cover home/composer layout, one-tap stock question, draft/result restoration after closing and reopening, stale-copy prevention after editing, Translate remaining above the keyboard, notification denial allowing screen sharing, and capture cancellation leaving Start retryable, the small duck/menu and edge docking, Pause/Continue state, and Copy & return restoring the previous app. Further device testing is still needed for live Taobao and continuously animated pages.

v0.4.2: the APK builds and Android lint passes with no errors (34 remaining warnings are documented in the code review). Five integrated JVM tests cover unchanged screens, upper-screen changes, changes remaining dirty until another source capture, overlay exclusion, and source snapshot ownership. Android 14 emulator checks confirmed composer input/result restoration after rotation, overlay startup and Chinese OCR/translation on a locally displayed Taobao product screenshot, plus a settled static page that triggered no further scans during observation. A clean source copy also built and tested without the project’s private signing key using cached dependencies. These checks do not establish live Taobao behavior on the user's phone or replace testing on Android 8–9.

The earlier v0.4.0 Android 14 emulator checks covered locally displayed home, product, and reviews screenshots, duck dragging/pause controls, and a sample composer message. They are recorded as prior-version evidence rather than v0.4.2 regression coverage.

Translation is powered by [Google Translate](https://translate.google.com) through ML Kit. The app includes Google's official attribution badge and disclaimer. References: [Android MediaProjection](https://developer.android.com/media/grow/media-projection), [Android overlay touch rules](https://developer.android.com/reference/android/view/WindowManager.LayoutParams), [ML Kit Chinese OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [ML Kit translation](https://developers.google.com/ml-kit/language/translation/android), and [translation attribution](https://docs.cloud.google.com/translate/attribution).

## Changes in v0.4.2

Android 8–9 startup uses the compatible foreground-service API. Refreshes stay queued while translation is running; captured source pixels remain the comparison baseline when labels redraw. Capture temporarily hides every app-owned overlay window, including the duck/menu. Composer drafts and results survive recreation, and callbacks from a destroyed composer are ignored. Local build artifacts and signing keys are excluded from Git.

### Earlier speed improvements (v0.4.1)

v0.4.1 reduced the visual refresh cooldown from 3.5 to 0.9 seconds; v0.5.0 replaces it with waiting for 0.3 seconds of quiet. Cached lines render immediately after OCR, duplicate text is translated once per scan, and new results render within a 50 ms drawing window instead of 180 ms. Actual OCR and inference time depends on the phone and the amount of new text.

## License

Application code is available under the [MIT license](LICENSE). Dependencies, the Gradle wrapper, and Google brand assets retain their respective licenses and usage requirements.
