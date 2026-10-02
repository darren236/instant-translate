# Changelog

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

## Changes in v0.4.2

Android 8–9 startup uses the compatible foreground-service API. Refreshes stay queued while translation is running; captured source pixels remain the comparison baseline when labels redraw. Capture temporarily hides every app-owned overlay window, including the duck/menu. Composer drafts and results survive recreation, and callbacks from a destroyed composer are ignored. Local build artifacts and signing keys are excluded from Git.

### Earlier speed improvements (v0.4.1)

v0.4.1 reduced the visual refresh cooldown from 3.5 to 0.9 seconds; v0.5.0 replaces it with waiting for 0.3 seconds of quiet. Cached lines render immediately after OCR, duplicate text is translated once per scan, and new results render within a 50 ms drawing window instead of 180 ms. Actual OCR and inference time depends on the phone and the amount of new text.
