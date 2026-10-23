# Wordscapes Auto — native Android accessibility app

**A complete Android Studio project (Kotlin, Android 11+).** ADB / Python are not used at runtime.

## What it does

- AccessibilityService (opt-in) captures the game screen with `takeScreenshot()` and draws continuous finger gestures with `dispatchGesture()`.
- **CAL (one time):** save only the wheel center and ring radius. No letter count is stored. Geometry scales to current screen resolution.
- **SCAN (every new level):** automatically detect the current letter count and positions from a fresh screenshot using the saved geometry; run ML Kit OCR; show a numbered ring preview and editable letters. If a position is missing, use `− COUNT` or `+ COUNT` in review to rescan just this level. `AUTO` resets the count to detection. These buttons do not change calibration.
- **RUN:** uses a bundled offline word list (84,377 alphabetic entries, 3–8 characters) plus offline word-frequency rankings to create permutations, preferring familiar words. It also optionally fetches extra candidates from Datamuse (best-effort, in the background). Dispatches continuous swipes, one candidate at a time, then checks whether the wheel disappeared. Two captures must agree before stopping.
- **STOP:** immediately cancels the remaining queue. Does **not** skip ads, advance results screens, or automatically start the next level.

## Build / install

1. Download/unzip project. Open **this directory** in **Android Studio** (`File > Open`).
2. Android Studio: allow Gradle sync, JDK 17, install Android SDK Platform 35 if prompted. Project uses Android Gradle plugin 8.9.2 / Gradle 8.11.1 / Kotlin 2.0.21 and ML Kit 16.0.1.
3. Connect your Samsung via USB (Developer options → USB debugging). Click **Run ▶** in Android Studio and choose the phone.
4. Open app → tap **Enable Accessibility Service** → enable **Wordscapes Auto accessibility**. If Android blocks the sideloaded service, go to Settings → Apps → Wordscapes Auto → ⋮ → **Allow restricted settings** (when that option exists), then retry.
5. Launch a Wordscapes level. Floating buttons appear near the top: `CAL`, `SCAN`, `STOP`.
6. **Once:** tap **CAL**, select the center of the wheel, move the radius slider until the yellow circle passes through the letters, then **SAVE**.
7. **Each level:** tap **SCAN**. Check the detected positions/count in the ring preview and correct any `?` or mistaken letter in the editor. Use `− COUNT` or `+ COUNT` for a missed/extra letter **without recalibrating**. Press **RUN** or **STOP**.
8. Advance through level results and ads yourself. On the next puzzle tap **SCAN again**; the letter count resets to automatic detection.

## Design and privacy

- No broad overlay permission (`SYSTEM_ALERT_WINDOW`) or `MediaProjection` consent required. Accessibility screenshot/gestures permission **is** required and is sensitive: only enable the service if you trust the code.
- The app **does not upload screenshots**. It stores only the last calibration screenshot in private app cache, and the center/radius/count in private preferences. Word list and Latin OCR model are bundled. When RUN is pressed and the internet is available, the app sends up to four wildcard word-pattern requests to the Datamuse API; it sends **no screenshots or personal data**. Offline solving runs even without internet.
- The floating controls are shown while the accessibility service is active. Disable the service from Settings when done.

## Known limitations and debugging

- **This source is not device-tested here**; no Android SDK/device is available in the build environment. It is an Android Studio project, not an already compiled APK.
- Letter positions/count depend on the game UI. Use `− COUNT` / `+ COUNT` in SCAN review if detection misses a position; only repeat CAL if the actual wheel center or ring radius has moved.
- OCR of isolated characters can misread letters; manual review is intentionally required. `I` uses a narrow-stroke fallback **only** when ML Kit returns no letter; it never rewrites an `S`, `E`, or `F` that was recognized.
- Wordscapes accepts only a subset of dictionary words. You may see rejected swipes. All 84k words are drawn from a pronunciation-dictionary vocabulary and can contain unusual/proper words. The offline solver starts with up to 130 candidates and can grow to 170 if online extras arrive. Some puzzles may need more words or a narrower accepted-word list.
- Completion detection is visual and may incorrectly stop or fail to stop on animations. `STOP` always cancels the pending queue.
- Accessibility screenshot limits can temporarily reject rapid screenshot requests; press `SCAN` again if a capture fails.

## Structure

- `MainActivity.kt` – service onboarding
- `CalibrationActivity.kt` – screenshot, center tap, radius slider, count preview/override
- `WordscapesService.kt` – accessibility screen capture, floating controls, word swiping, stop logic
- `WheelDetector.kt` – light/dark glyph angular ring detection (3–8 count)
- `LetterReader.kt` – ML Kit letter crops, safe `I` fallback
- `WordEngine.kt` – anagram matching, dictionary candidate ranking, duplicate letter-safe paths
- `Settings.kt` – saved wheel center/radius
- `app/src/main/assets/words.txt` – offline word list, derived from CMU Pronouncing Dictionary (see `DICTIONARY_LICENSE.txt`)
- `DatamuseClient.kt` – best-effort Datamuse vocabulary supplements in a background thread
- `app/src/main/assets/word_frequency.tsv` – offline frequency ranking (see `WORD_FREQUENCY_ATTRIBUTION.txt`)

## Download a compiled APK from GitHub Actions

The repository builds a **debug APK** automatically on pushes to `main`. To build
on demand, open [Actions → Build Android APK](https://github.com/pugplayzYT/wordscapes-solver/actions/workflows/build-apk.yml),
choose **Run workflow**, select `main`, and start the build.

When the workflow completes successfully, open its run page and download the
**WordscapesAuto-debug-apk** artifact under **Artifacts**. GitHub downloads it as
a ZIP. Extract `app-debug.apk` from the ZIP and install that APK on your
Android phone (allow installations from that source if prompted). The artifact
expires after 30 days; simply run the workflow again to get a fresh APK.

This is a development/debug-signed app, **not** a Google Play release build.
It can be installed for testing, but the source has not yet been validated on a
physical Android device. If the build fails, open the failed step in the Actions
run to inspect the compiler log.

## Gradle

`gradle/wrapper/gradle-wrapper.properties` is supplied. The GitHub repository includes the official Gradle 8.11.1 wrapper JAR and launchers. Open the repository root in Android Studio, or use `gradlew.bat assembleDebug` on Windows (with Android SDK and JDK 17 configured).

## GitHub repository notes

The repository includes the source code, the Gradle 8.11.1 wrapper, a generated
84,377-word CMUdict-based offline list, and a frequency-ranked TSV derived from
the open 20k English word list (see attribution). The Android app itself remains
untested on a physical phone; this is source code rather than a prebuilt APK.

## Version 1.1 — one-time calibration

Wheel center and radius persist across app launches. The old saved letter-count override is removed. Every new SCAN automatically redetects 3–8 positions; wrong counts can be corrected using `− COUNT`, `AUTO`, `+ COUNT` without visiting CAL. Android GitHub Actions builds and uploads a debug APK on each push.
