# Verification record

## Current version

v0.5.2: APK build/signature verification and 19 JVM tests pass. Three bitmap instrumentation tests pass on Android 14 (arm64), covering unpadded rows, padded rows, and absent final-row padding. Lint reports zero errors and 21 warnings. Live Taobao and supported-version/lifecycle coverage remain limited as described in the code review.

v0.5.1: APK build, signature verification, and 19 integrated JVM tests pass. Android lint reports zero errors and 19 warnings. Source inspection confirms no floating attribution view or provider wording in the helper menu; Close helper uses the existing session shutdown path. No new connected-device check was performed for this UI update.

v0.5.0: build, signature verification, and 19 integrated JVM tests pass. Android lint reports zero errors and 20 warnings. Tests cover source changes, frame motion and overlay masks, and waiting through sustained scrolling. Android 14 emulator checks cover home/composer layout, one-tap stock question, draft/result restoration after closing and reopening, stale-copy prevention after editing, Translate remaining above the keyboard, notification denial allowing screen sharing, and capture cancellation leaving Start retryable, the small duck/menu and edge docking, Pause/Continue state, and Copy & return restoring the previous app. Further device testing is still needed for live Taobao and continuously animated pages.

v0.4.2: the APK builds and Android lint passes with no errors (34 remaining warnings are documented in the code review). Five integrated JVM tests cover unchanged screens, upper-screen changes, changes remaining dirty until another source capture, overlay exclusion, and source snapshot ownership. Android 14 emulator checks confirmed composer input/result restoration after rotation, overlay startup and Chinese OCR/translation on a locally displayed Taobao product screenshot, plus a settled static page that triggered no further scans during observation. A clean source copy also built and tested without the project’s private signing key using cached dependencies. These checks do not establish live Taobao behavior on the user's phone or replace testing on Android 8–9.

The earlier v0.4.0 Android 14 emulator checks covered locally displayed home, product, and reviews screenshots, duck dragging/pause controls, and a sample composer message. They are recorded as prior-version evidence rather than v0.4.2 regression coverage.

## GitHub checks

The [fresh-checkout CI run for v0.5.2](https://github.com/darren236/instant-translate/actions/runs/36972807922) passed on 2 October 2026. It built the app, ran JVM tests and lint, and compiled the Android instrumentation APK. Instrumentation tests were run separately on the local Android 14 emulator; they do not run in GitHub CI.

## Screenshot provenance

The README gallery was captured from the actual v0.5.2 debug app on an Android 14 emulator at 720 × 1560 pixels. The overlay and helper menu are shown over a synthetic native shopping screen, not live Taobao. Its text and illustration are demonstration content. The composer uses a built-in stock question. These screenshots illustrate the UI; they do not establish live Taobao reliability or translation quality on every device.

The app screenshots are unedited screen captures and retain actual OCR/translation output, including missed or mistranslated text. The demonstration app and local build fixtures are excluded from Git; only the screenshots are included.
