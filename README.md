# Wordscapes Auto — native Android accessibility app

**A complete Android Studio project (Kotlin, Android 11+).** ADB / Python are not used at runtime.

## What it does (v1.5)

- **CAL:** tap the precise CENTER of the letter wheel on the screenshot; use a slider to make the gold circle **fully enclose** the wheel and all possible letters. Save once. This is only a **search radius**; it is not the exact radius of the letters.
- **SCAN:** within that circle, detect *actual* isolated characters via two full-region ML Kit OCR passes and bright/dark connected-component glyph detection. Fit the visible positions into one circular layout (3–12 letters). The earlier angular brightness-peak guessing algorithm is **no longer used for scans**. Do not fabricate missing touch points.
- **Per-letter OCR:** three independent crops, plus locally learned glyph examples from confirmed corrections. Where whole-region OCR and per-letter OCR disagree, require review.
- **Review:** show the ACTUAL scanned screenshot region with numbered markers at the detected touch points and an editable character field. This makes it clear whether a failure is character recognition or position finding. Count corrections still trigger fresh scans.
- **Auto-run:** enabled by default; only starts if position evidence and letter OCR pass the confidence gates. If uncertain, review; do not automatically swipe guessed coordinates.
- **RUN:** 84,377 word local dictionary (mostly 3–8 letters) with ranked candidates and optional Datamuse suggestions; swipe using Android Accessibility gestures.
- **STOP:** cancel the remaining word queue. **HIDE:** stop and remove overlay. Restore with **SHOW FLOATING CONTROLS** inside the launcher app.
- **Completion checks:** compare saved screenshot glyph patches after swiping and require two consecutive checks before stopping on a completed level. Still heuristic; not game-state integration.
- **Privacy:** screenshots and learned glyph templates remain on device. Optional Datamuse gets letter-pattern queries only.

## Build / install

1. Download/unzip project. Open **this directory** in **Android Studio** (`File > Open`).
2. Android Studio: allow Gradle sync, JDK 17, install Android SDK Platform 35 if prompted. Project uses Android Gradle plugin 8.9.2 / Gradle 8.11.1 / Kotlin 2.0.21 and ML Kit 16.0.1.
3. Connect your Samsung via USB (Developer options → USB debugging). Click **Run ▶** in Android Studio and choose the phone.
4. Open app → tap **Enable Accessibility Service** → enable **Wordscapes Auto accessibility**. If Android blocks the sideloaded service, go to Settings → Apps → Wordscapes Auto → ⋮ → **Allow restricted settings** (when that option exists), then retry.
5. Launch a Wordscapes level. Floating buttons appear near the top: `CAL`, `SCAN`, `STOP`, `HIDE`.
6. **Once:** tap **CAL**, tap the wheel's **center**, move the **radius slider** to completely enclose every letter, and tap **SAVE SEARCH CIRCLE**.
7. **Each level:** tap **SCAN**. If all letters have consistent recognition and the detector is confident, the bot starts solving immediately (unless you disable the **Auto-run after confident SCAN** checkbox on the main screen). Otherwise, review and correct the text; confirmed letters improve the local recognizer over time. Use `− COUNT` or `+ COUNT` for a missed/extra letter **without recalibrating**. Press **RUN** or **STOP**.
8. Advance through level results and ads yourself. On the next puzzle tap **SCAN again**; the letter count resets to automatic detection.

## Design and privacy

- No broad overlay permission (`SYSTEM_ALERT_WINDOW`) or `MediaProjection` consent required. Accessibility screenshot/gestures permission **is** required and is sensitive: only enable the service if you trust the code.
- The app **does not upload screenshots**. It stores only the last calibration screenshot in private app cache, and the center/radius/count in private preferences. Word list and Latin OCR model are bundled. When RUN is pressed and the internet is available, the app sends up to four wildcard word-pattern requests to the Datamuse API; it sends **no screenshots or personal data**. Offline solving runs even without internet.
- **HIDE** removes all floating controls until you tap **SHOW FLOATING CONTROLS** in the launcher app. Accessibility permission stays enabled. Disable the service from Android Settings if you want to revoke permission entirely.

## Known limitations and debugging

- **This source is not device-tested here**; no Android SDK/device is available in the build environment. It is an Android Studio project, not an already compiled APK.
- Letter positions/count depend on the game UI. Use `− COUNT` / `+ COUNT` in SCAN review if detection misses a position; only repeat CAL if the wheel moves outside the saved circle.
- OCR of isolated characters can misread letters; manual review is intentionally required. `I` uses a narrow-stroke fallback **only** when ML Kit returns no letter; it never rewrites an `S`, `E`, or `F` that was recognized.
- Wordscapes accepts only a subset of dictionary words. You may see rejected swipes. All 84k words are drawn from a pronunciation-dictionary vocabulary and can contain unusual/proper words. The offline solver starts with up to 130 candidates and can grow to 170 if online extras arrive. Some puzzles may need more words or a narrower accepted-word list.
- Completion detection is visual and may incorrectly stop or fail to stop on animations. `STOP` always cancels the pending queue.
- Accessibility screenshot limits can temporarily reject rapid screenshot requests; press `SCAN` again if a capture fails.

## Structure

- `MainActivity.kt` – service onboarding
- `CalibrationActivity.kt` – screenshot, center tap, radius slider, count preview/override
- `WordscapesService.kt` – accessibility screen capture, floating controls, word swiping, stop logic
- `WheelDetector.kt` – light/dark glyph angular ring detection (3–12 count)
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

## Version 1.2 — hide / restore the floating overlay

Tap **HIDE** on the bar to stop solving and remove both the bar and any review overlay. The hidden state persists across Accessibility Service restarts. Open the app normally and tap **SHOW FLOATING CONTROLS** to restore without navigating through Accessibility settings. Hiding does not revoke Accessibility permissions.

## Version 1.3 — improved OCR and optional automatic solving

- Uses **three independently processed views** of each letter rather than a single ML Kit crop. A vote determines the initial reading.
- Learns normalized letter-shape templates **only from letters the user explicitly confirms by tapping RUN in review mode**; no remote training or screenshots uploaded. Templates remain in the app's private preferences.
- Learned examples can resolve recurring ML Kit errors without blanket forced substitutions of S/F/E/I. Ambiguous results still require review.
- A new **Auto-run after confident SCAN** checkbox is enabled by default. Confident scans automatically proceed to swipe words. Uncertain scans continue to the manual correction panel; the setting can be turned off.
- When the word candidate queue is exhausted, the last letter review is offered again.
- This is a heuristic recognizer, not a guaranteed fix for every Wordscapes font or background. Actual phone testing is still needed.
\n## Version 1.4 — bounding-box search\n\nCAL saves a rectangle containing the wheel, not a radius. SCAN searches several circle centers and radii within the rectangle and infers **3–12** letter positions anew for each puzzle, then OCR and gesture logic use the detected coordinates. The optional per-level count correction now supports 3–12. The existing offline word list still contains primarily **3–8 letter words**; online Datamuse supplementation may contribute longer words but is not guaranteed to solve every 9–12 letter anagram.\n
## Version 1.5 — circular search + OCR boxes (experimental)

The entire calibration was changed back from two rectangular corners to **one center tap plus a radius slider**. The radius now encloses the entire search region, not a fixed ring of letter centers. SCAN combines whole-region OCR text boxes with visual connected components to find actual character coordinates, then checks the ring geometry instead of fabricating regular radial points from angular brightness peaks. Review now shows the screenshot with its predicted touch markers, so bad position predictions are inspectable. This is an experimental detection algorithm: it has to be evaluated against a real Wordscapes screenshot on the phone; compilation alone does not establish accuracy.

## Version 1.6 — unattended AUTO and dual OCR engines

- **AUTO on the floating toolbar:** click when on a Wordscapes Level menu or active puzzle. The bot uses on-device ML Kit text recognition to find the **Level** button and **Tap to Skip** screen. It requires two consecutive matching frames before tapping these controls, then scans/solves the new puzzle. It can loop through multiple levels while the phone is plugged in and the game stays in the foreground. The floating control keeps the screen on while AUTO is active. Click AUTO again, STOP, or HIDE to stop. No ads or unknown screens are blindly tapped, and it pauses if it cannot find a recognized screen after about 15 minutes.
- **Independent pretrained OCR:** the APK bundles the Tesseract 5 English fast model, downloaded from the official `tessdata_fast` repository as part of the GitHub Actions build. Tesseract PSM_SINGLE_CHAR and A-Z whitelist provide a second OCR engine per letter in addition to ML Kit and confirmed local glyph templates. If engines disagree, recognition can require manual review. The actual phone accuracy is not verified; model size alone cannot guarantee success.
- **Longer dictionary:** GitHub Actions builds an expanded CMUdict vocabulary of 3–12 alphabetic letters before compiling. This helps 9–12 letter puzzles beyond the previous 3–8 limit.
- **Failure handling:** AUTO retries unrecognized puzzle rings up to five times, then stops rather than drawing arbitrary words. It also never auto-clicks advertisements or purchase buttons. Tesseract model initialization can briefly slow down the first scan.
- **Building locally:** download `eng.traineddata` from https://github.com/tesseract-ocr/tessdata_fast/blob/main/eng.traineddata and place it in `app/src/main/assets/tessdata/eng.traineddata` before building in Android Studio. Alternatively use GitHub Actions, which bundles it automatically. The official CMUdict expansion also runs automatically in GitHub Actions.

## Version 1.7 — readable floating controls

The small single-row floating toolbar is now two rows so no buttons overlap or squash. The primary row has three equal-size 44 dp buttons: **AUTO, SCAN, STOP**. A separate secondary row has **CAL, HIDE**, and a flexible two-line status indicator. The toolbar stays within the phone screen and is limited to 560 dp wide on tablets. AUTO changes to **AUTO ON** with a green background while running. The review panel now starts below the taller toolbar. The solver and OCR algorithms are unchanged in this release.

## Version 1.8 — round O and incomplete ring recovery

- All OCR pathways normalize the digit **0** to the letter **O** when reading the Wordscapes wheel, where numeric tiles are not valid. This prevents an otherwise correctly located O from being silently discarded by the A–Z validator.
- When four visible character positions form a near-complete five-letter ring, the geometry detector may test one **missing** position if the screenshot shows contrasting character ink there. It then runs individual ML Kit and Tesseract OCR on that location; an inferred coordinate is not itself treated as a recognized letter.
- The scan status explicitly notes how many letter positions were recovered and still opens the screenshot review when confidence is insufficient. There is no claim of verified overnight reliability without actual device testing.

## Version 1.9 — no more strict whole-wheel OCR gate

- Fixed an important root cause of **No reliable letter ring found**: ML Kit splits detected text into lines, elements, and individual symbols. Previously any element containing two or more characters was discarded entirely! The detector now uses individual **Symbol** bounding boxes with their real screen coordinates.
- Introduced `RadialFallback.kt`: if symbol OCR and the original glyph contour detector cannot prove a full ring, the app searches plausible radii and angular arrangements within the saved circular CAL area, using image brightness/contrast and detected OCR anchors. It then tries both on-device OCR engines on every candidate position.
- When the fallback estimates positions, its result has deliberately low geometry confidence, so the app shows its annotated screenshot in the editable review rather than automatically swiping potentially wrong coordinates. **AUTO** still pauses after repeated uncertain puzzles; the fallback by itself is not proof of reliable unattended play.
- Calibration remains **center tap and a radius slider**, and the two-row toolbar is unchanged. No extra API key or model download is required at runtime. These changes are algorithmic and must be evaluated using screenshots of actual Wordscapes levels.
