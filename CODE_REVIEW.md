# Code review — Instant Translate v0.5.2

Reviewed 2 October 2026 for portfolio and public-source readiness. Scope: screen capture and bitmap ownership, refresh scheduling, service/composer lifecycle, permissions, clipboard actions, local privacy, build packaging, and CI.

## Findings addressed in v0.5.2

### P1 — Capture buffers could be rejected when final-row padding is absent

The old conversion copied the entire padded bitmap from the image plane. Android only guarantees buffer data through the final pixel of the last row; its trailing padding may be unmapped. Such a buffer caused `copyPixelsFromBuffer` to fail instead of producing an OCR image. `FrameBitmap` now fills only the missing trailing padding before copying and preserves the actual image dimensions and RGBA colors. It also guards bitmap ownership when a crop returns its input and releases temporary allocations on conversion errors.

Three Android instrumentation tests cover unpadded rows, fully padded rows, and an unmapped final-row padding region. All three pass on Android 14 and verify usable ML Kit input plus pixel colors on both rows. The ownership guard is defensive; the review does not establish that mutable full-size crops returned their input on the tested Android version.

References: [Android Image.Plane buffer contract](https://developer.android.com/reference/android/media/Image.Plane#getBuffer()), [Bitmap crop ownership](https://developer.android.com/reference/android/graphics/Bitmap#createBitmap(android.graphics.Bitmap,%20int,%20int,%20int,%20int)).

### P2 — Invalidated capture callbacks could overwrite the current session state

Frame handling now preserves the acquisition epoch and rechecks the reader after acquisition. OCR failures and read errors from an older session/rotation are ignored before updating status or finishing the current cycle. If OCR submission throws, the unsubmitted bitmap is recycled. Error instructions now refer to the existing Refresh action.

This is a source/lifecycle correction; the three bitmap tests do not prove every rotation race is eliminated.

## Remaining release findings

### P1 — Live overlay attribution remains unresolved

The floating helper and menu intentionally omit provider branding following the accepted UI change. The composer and About screen retain Google attribution, but the live translated screen has no adjacent badge and its manual Refresh action has no provider attribution. ML Kit's published guidelines require following the Cloud Translation attribution requirements for applications, including attribution adjacent to results and attribution on the translation action. About/README attribution alone does not address that placement requirement.

Resolve this with a compliant placement or a different translation provider before presenting the app as a finished consumer release. Source publication does not establish compliance. The review leaves the accepted helper design intact.

Sources checked 2 October 2026: [ML Kit usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms), [Google translation attribution requirements](https://docs.cloud.google.com/translate/attribution).

### P2 — Continuous screen animation can delay automatic refresh indefinitely

The refresh policy waits for 300 ms of quiet and deliberately has no forced capture deadline while pixels keep moving. Repeated visible animation or accessibility content events can therefore postpone a scan even after the user stops scrolling. Manual Refresh bypasses the quiet delay. A live Taobao device check is still needed to judge the impact of its banners, videos, and product-page animations.

This remains a documented prototype limitation; a change to motion classification needs meaningful device evidence and should preserve the accepted behavior during scrolling.

## Verification

- APK build and signature verification: passed.
- Integrated JVM tests: **19 passed**.
- Android 14 instrumentation tests: **3 passed**, using Android's actual bitmap implementation.
- Android lint: **0 errors / 21 warnings**.
- Instrumentation APK builds; CI now compiles it alongside the unit tests, lint, and app APK. CI does not start an emulator or run instrumentation tests automatically.
- Packaging uses an allowlist; signing keys, generated APKs, build folders, capture fixtures, and local Gradle caches are excluded from the source ZIP and Git staging.
- The v0.5.0 UI checks remain prior-version evidence. This review has not retested live Taobao, Android 8–9, or all rotation/model-download sequences.

## Maintenance notes

The overlay service remains large and coordinates several asynchronous components. Moving frame conversion into `FrameBitmap` is a small tested boundary; a broader rewrite is outside this wrap-up. Remaining lint warnings include old target/dependency versions, inset handling, and Java/API/style warnings. They are not a clean production-readiness claim. Test the behavior changes before raising the target SDK or replacing dependencies.

Captured frames are processed in memory, not written by the app or sent to a translation endpoint. Composer drafts are stored in private preferences; backup is disabled. Only the launcher activity is externally exported; the accessibility service requires Android's binding permission. Translation models still require an initial network download.

[Historical v0.4.1 review and v0.4.2 fixes](docs/reviews/v0.4.1.md)
