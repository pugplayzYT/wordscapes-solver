# v2.9 — Restore successful multi-letter wheel recognition

Regression fixed: v2.8 switched Gemma to a JSON `{"letters":"WORD"}`
response even though the previous more reliable prompt asked for an array
of individual wheel glyphs. On real Wordscapes screenshots it often
returned **only one letter**.

Improvements:
- Restores the older per-letter array recognition:
  `{"letters":[{"c":"E","x":500,"y":150}, ...]}`.
- Counts and reads every visible wheel letter, including duplicates
  when they are separate tiles. If the response has fewer than three
  readable letters, it performs one alternate recognition attempt with
  a larger image crop and a different direct character-counting prompt.
- **Respects the CAL circle you highlighted** before the model's
  potentially incorrect wheel rectangle. The previous logic
  prioritized the model's rectangle, resulting in accidental tight
  one-letter crops.
- Keeps good model-detected glyph touch coordinates, rather than
  throwing them away as in v2.8.
- When Gemma repeats coordinates or omits some positions, it
  preserves the transcribed letters and tries screenshot-based radial
  fitting, with deterministic geometry as a last resort. It never
  rejects letters with `Duplicated letter location`.
- Word selection remains **entirely from the bundled dictionary**.
  Gemma no longer generates candidate words. Royal Match mode is
  unchanged.

The model can still misread characters and some inferred touch
positions may be imperfect; this release directly addresses both
the one-character regression and the duplicated-coordinate errors
without bringing back AI hallucinated word lists.

---

# v2.8 — Wordscapes offline dictionary restored; simpler OCR geometry

Wordscapes mode no longer asks the language model to make up words.
The bundled `words.txt` and `word_frequency.tsv` provide candidates.
`WordEngine.solve()` ranks full-wheel anagrams and common words ahead
of obscure words. The bot tries an initial set of up to 260 dictionary
words, then can expand to 850 total if still unsolved. No Gemini/Gemma
anagram generation and no Datamuse API are involved in Wordscapes AUTO.

**Wheel vision reliability overhaul:** Previously we asked Gemma for
the exact 0..1000 coordinates of every single glyph, then rejected
the whole wheel for any two closely estimated positions ("Duplicated
letter location") or disagreement between repeated position readings.
This produced needless retries even when the model read the letters.

In v2.8, Gemma's wheel task is limited to the **character string in
clockwise order** plus an optional rough coordinate for the topmost
glyph. Kotlin calculates uniformly spaced ring points from the
detected wheel bounding rectangle. Thus individual duplicate
coordinate guesses cannot cause a failure. The app no longer makes a
second wheel read just to compare fragile coordinate values. The
wheel crop is expanded to reduce cases where tiles are cut off.

On rare layouts these geometrically estimated touch points may be
misaligned; use SCAN to inspect the wheel. The model may still misread
character shapes or tile count, but this change removes one entire
class of coordinate errors. The review UI remains available.

Royal Match mode is unchanged.

---

## v2.7 — Wordscapes continuous Gemini/Gemma answer generation

Bug fixed: previous AI-only mode accepted just the first model list
(up to 32, often only 2–3 words) and treated it as exhaustive. It would
discard the confirmed wheel / restart the scan rather than ask for more
words when the board was still unsolved.

v2.7 now uses **multi-batch, adaptive AI-only Wordscapes solving**:
- Gemma initially suggests common English words grounded in the
  crossword screenshot. An initial return of 0, 2, or 3 words is
  NOT a reason to stop or restart the level.
- The player tries valid suggestions (at least 3 letters, uses only
  wheel letters, including duplicate multiplicity). Kotlin's
  WordEngine is used **only for letter validation and swipe routing**;
  no bundled dictionary or Datamuse enumeration runs in AI mode.
- After the pending list runs out, it captures the current crossword
  and asks Gemma for another batch. It excludes all previously
  suggested words and cycles strategies: longer anagrams, short
  common words, inflections, and partially filled crossword slots.
- Each generated batch may return up to 72 words; there is NO fixed
  limit of 32 words or total attempts per puzzle. Every word from a
  valid batch is appended in Gemma's priority order.
- To avoid an uncontrolled infinite loop of expensive local LLM
  requests, **six consecutive attempts with no novel valid words**
  pause for manual review. Successful batches reset this safeguard.
- Android will still verify that the puzzle wheel disappears after
  a swipe and transition to level/victory flow.
- Royal Match mode and the no-flash DARK curtain remain available
  and are otherwise unchanged.

Limitations: model suggestions are not guaranteed to be accepted by
Wordscapes; the AI may still fail to solve a particular puzzle. Each
extra batch requires another inference on the user's PC, so a slow
CPU-offloaded Gemma model can add delay. This addresses hard list
truncation, NOT errors in the model's letter/board recognition.

---

# v2.6 — Royal Match AI mode (new)

This version adds a game selector under **SELECT GAME** in the Android
launcher: **Wordscapes — word wheel** (original mode), or
**Royal Match — AI match-3** (new mode). The selected mode is persisted.

In Royal Match mode, tap AUTO from the **Level** screen. The phone sends
screenshots to your configured Gemma 4 vision model on your Ollama PC.
The agent recognizes the Level button, the **Play** button, an active
match-3 board, victory screens, and free retry options. On each active
board it asks Gemma for ONE action (a match-3 swipe between adjacent
cells, or activation of a free ON-BOARD power-up), carries it out with
Accessibility gestures, allows animations to settle, captures the new
board, gives the prior-action observation to Gemma, and asks for the
next move.

The planner prioritizes level goals shown in the top-left of Royal
Match, remaining moves in the top-right, 4+ piece matches, and
power-ups (Rocket/TNT/Propeller/Light Ball). For **Marble** obstacles
it prefers hitting Marble with an on-board power-up before clearing
revealed Plaster with a match. For typical grid-based boards, it
converts zero-based row/column pairs into actual tile center pixels
to make adjacent swipes rather than arbitrary drags.

Safety / reliability guardrails:
- Never intentionally click a purchase, coin spend, paid booster,
  additional paid moves, optional rewarded advert, or install offer.
- If a page is unknown or no free next step is obvious, wait and
  eventually pause. Free retries are limited.
- Only horizontal/vertical adjacent board swaps are accepted when
  tile indices can be recognized. Without a grid, only short
  approximately straight horizontal/vertical drags are permitted.
- Normal menu/Play/Continue taps are confirmed with a second
  screenshot; one move is executed at a time on live boards.
- The app compares before/after screenshot regions to detect
  ineffective actions and avoids repeating moves endlessly.
- STOP interrupts the agent and removes DARK curtain.
- DARK's Android 14+ window capture can remain enabled for Royal Match.
- Wordscapes's previous AI word-solving mode remains available when
  you switch back in the app settings.

**Important limitations:** This is a first build and there is no
promise that Gemma can reliably beat difficult boards. Vision models
can mistake tiles, grid coordinates, objectives, power-up types or
interstitials. The last-move feedback is only a coarse image-change
metric, not a complete simulation of gravity/cascades. Match-3 boards
with irregular layouts or moving obstacles may require a dedicated
board detector and forward-search solver. A successful Android build
does not prove that any particular match or level can be completed.

Official game: Royal Match by Dream Games
(https://play.google.com/store/apps/details?id=com.dreamgames.royalmatch).

---

## Wordscapes Auto 2.5 — no-flash dark capture + AI-only answers

### DARK curtain: no repeating flashes

Android 14 (API 34) introduced
[`AccessibilityService.takeScreenshotOfWindow`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)).
That API **captures a specific app window even underneath an
accessibility overlay**. Unlike previous releases, v2.5 does **not
briefly uncover the game** while taking each screenshot. The curtain
remains fully visible (black) while the screenshot comes directly
from Wordscapes' underlying window.

This is not physical display-off. The panel stays logically awake to
receive gestures, and the dark WAKE tab remains visible. The app
enables `canRetrieveWindowContent` and
`flagRetrieveInteractiveWindows` to identify the actual foreground
application window, excluding its own accessibility overlays.
A window-only image is mapped into full-screen device coordinates so
Gemma's detected touch locations remain aligned with Android swipes.

When DARK mode cannot obtain the game window on a particular ROM,
AUTO **pauses with an explicit error**; it will NOT resume the older
hide-curtain / flash / capture loop. Android 14+ is required for
no-flash DARK; the regular non-dark mode supports older Android
versions. Users who already enabled the accessibility service may
need to disable and re-enable the service after updating because its
window-information capability metadata has changed.

### Common-first, fully Gemma-generated words

When Gemma mode is enabled, `WordEngine.solve()` and Datamuse are
**not used at all** for the word list. The bot:
1. Uses Gemma vision to read each actual wheel letter and its touch
   coordinates, and validates them with a second independent read.
2. Sends the **full puzzle screenshot** and the confirmed wheel letters
   to Gemma for likely answers, asking it to inspect the crossword
   blanks and predict their lengths.
3. Gets at most **32 likely, common English words in priority order**.
   The words stay in Gemma's own common-first ranking; never sort them
   alphabetically or send a 170-word brute-force dictionary queue.
4. Uses only a lightweight local validator to make sure every suggested
   word has >=3 letters and uses only available wheel tiles in the
   correct multiplicity. **No two-letter words are attempted.**
5. Swipes words with the original Android Accessibility gesture engine,
   checking whether the puzzle is complete.

This can be more selective than dictionary enumeration, but model
predictions are not always correct, and Wordscapes may reject
AI-proposed words. The optional classic on-device OCR solver still
uses the bundled offline dictionary when Gemma is disabled.

**Verify on an actual S24+**: CI compilation confirms Android source
validity, but screen-coordinate mapping and window availability depend
on device/OS. If the curtain pauses with `DARK cannot capture game`,
disable and re-enable Wordscapes Auto's accessibility service and try
again. No-flash mode is a functional change, not a guarantee of
platform compatibility.

---

## v2.4 — missing full-length word no longer rejects puzzle

When the model reliably identified the wheel, v2.3 still required
`WordEngine.solve(letters)` to contain a full-length anagram. That was
a bad gate: bundled dictionary coverage is incomplete, and even genuine
full-length words could disappear when the top-130 result limit favored
shorter, more frequent words. The app misleadingly told the user
**"no full-length word in dictionary"** and refused to play.

Fixes:

- The confirmed letter wheel is preserved even when the local dictionary
  has no full-length word. Shorter playable words are not discarded.
- The offline dictionary sorts full-length anagrams ahead of shorter
  words, so the result limit cannot silently remove them.
- If no full-length word is found, the app now asks the same configured
  local Ollama model for English anagrams from the detected tile letters.
  Responses are valid JSON and every suggestion is checked for length
  and letter multiplicity using `WordEngine.isValid`. The bot tries
  these candidates **before** the existing offline-dictionary words.
- Failed anagram requests do not erase available shorter dictionary
  words. If neither AI nor dictionary returns any candidates, AUTO
  pauses and opens manual review instead of repeatedly re-scanning.
- The floating toolbar reports the letters it recognized and whether
  it is looking for a missing full-length word.

Wordscapes uses its own accepted-word list, so neither the bundled
dictionary nor an AI suggestion is guaranteed to be accepted in-game.
Model-proposed anagrams may be linguistically wrong despite letter
validation. The gesture/level logic is otherwise unchanged.

---

## v2.3 — black screen curtain while AUTO keeps playing

Android's normal Accessibility screenshots and gestures do **not**
continue reliably when the physical display is put into its actual
power-button OFF state. The new **Screen Curtain** is the supported
workaround: it paints an opaque black, non-touchable accessibility overlay
over the OLED display and requests minimum brightness, while keeping the
display electronically awake.

To use it:
1. Open Wordscapes Auto; check **Black screen curtain during AUTO**.
2. Open Wordscapes and press AUTO as usual.
3. The display becomes almost entirely black. A tiny, dark **WAKE**
   label at the top-right is the one area that can be tapped by a human.
4. Tap WAKE to uncover the game **without stopping AUTO**.
5. Alternatively start AUTO with the checkbox off, then tap the new
   floating **DARK** button. STOP always removes the curtain.

While blacked out, gestures pass through the full-screen curtain because
it has `FLAG_NOT_TOUCHABLE`. Before every screenshot, the app briefly
hides the curtain and the WAKE label, allows the compositor to update,
captures the underlying Wordscapes image, then restores the curtain.
**Brief visible flashes are expected** around screenshot capture; they
cannot be eliminated by this simple overlay-based implementation.

This is **not genuine panel power-off**, and the game/AI still run. On
OLED screens the predominantly black image may reduce display power and
burn-in risk versus leaving a bright puzzle on overnight, but savings
are hardware- and brightness-dependent. Android status/navigation bars
may remain visible depending on OS UI restrictions. Avoid physically
pressing the power key to switch off the screen, which may interrupt
screenshot/gesture operation.

CI building confirms compilation, not the behavior of screenshot
masking or touch pass-through on every manufacturer/Android version.
Test a few puzzles in DARK mode first before an overnight run.

---

## v2.2 — Gemma 4 empty-JSON fix

An on-device message such as **`End of input at character 0`** means
Android received an empty final answer from Ollama, *not* that it failed
to read the printed letters. In v2.1 the app asked Gemma 4 for JSON but
did not disable its thinking channel, and capped output at 700 tokens.
In some Gemma 4 / Ollama versions the thinking channel consumes that
budget, producing an HTTP 200 response with an **empty `message.content`**.

This release now:
- Adds `"think": false` to all `/api/chat` image requests so the model
  generates the JSON action/letter answer directly.
- Increases the output budget to 1,100 tokens for normal reads and
  up to 1,800 tokens for the fallback attempt.
- Handles blank `message.content`, empty HTTP bodies, and malformed
  JSON **without throwing a misleading JSON parsing exception**;
  makes one controlled retry and reports the actual cause.
- Logs `done_reason`, generated token count, and thinking field length
  (no screenshots or image content) under Android logcat tag
  `WordscapesAuto` to aid diagnosis.

**Gemma mode does not require CAL.** The AI normally locates its own
wheel crop; CAL can be used as a fallback if the model does not return
a wheel area. An empty JSON response is independent of CAL.

To capture diagnostics while running the puzzle:

```cmd
"%LOCALAPPDATA%\\Android\\Sdk\\platform-tools\\adb.exe" logcat -s WordscapesAuto:W
```

This is a protocol-handling fix and has not been proven against the
user's Ollama installation or actual puzzle screens until field tested.

---

## v2.1 — Level button tapping fix (October 2026)

If Gemma correctly says `menu / level` but the bot never clicks Level, the failure was the **tapping verification**, not AI image classification. Older v2.0 discarded navigations when Gemma omitted x/y, reported a low self-confidence score (below 0.79), or its estimated location shifted between reads. The floating toolbar only showed `Gemma menu / level`.

- AI still classifies the entire scene.
- Android runs independent on-device ML Kit OCR on that SAME screenshot to locate the visible text **Level 123** or **Level** and uses the center of its REAL bounding box as the touch target. The on-device OCR label serves as independent verification, so one AI + OCR frame is enough for Level/Continue.
- Without OCR, a reported Gemma tap point is accepted after two spatially similar results, with its confidence threshold reduced to 0.52 for normal game navigation. The returned point is strictly bounded to the screen.
- **Ads:** still require a visible and OCR-detected close/skip/X control and two matching frames. It never taps a guessed corner or an ad engagement button.
- The toolbar now shows the decision (`AI tapping level: x,y` or why it is waiting) rather than hiding the reason. Android Logcat tag `WordscapesAuto` records taps.
- Three repeat taps of the same Level button without an observed screen transition pause AUTO instead of looping forever.

A successful CI compile does not verify that the Level button is actually tappable on all Wordscapes screens. Test the new build on your device and observe whether a real Level click happens.

---

# Wordscapes Auto — native Android accessibility app

**Android Studio / Kotlin, Android 11+.** The classic offline OCR mode needs no PC. The new Gemma mode connects to an Ollama service on your PC by USB ADB reverse or private Wi-Fi.

## Wordscapes Auto 2.0 — Gemma 4 / Ollama mode (new)

**This version introduces a new AI-first mode.** The Android accessibility service
captures the current Wordscapes screen, sends the screenshot **to your own
Ollama server on the PC**, and executes validated screen taps and word swipes
on the phone. It does not require the old fragile wheel detector to recognize
letters. Gemma supports starting from the home screen.

### First-time PC setup (Windows / USB)

1. Install [Ollama for Windows](https://ollama.com/download/windows).
2. On your Windows PC, start the Ollama app and verify the installed model:

   ```cmd
   ollama pull gemma4:12b
   ollama list
   ```

   The Ollama app serves the local API on port 11434. Gemma 4 12B can be
   slow on a GTX 1080 or other 8 GB VRAM GPUs; the model may use PC RAM.

3. Connect the Samsung phone by USB, enable USB debugging, then in **CMD**:

   ```cmd
   "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" devices
   "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" reverse tcp:11434 tcp:11434
   ```

   Keep USB connected, and ensure the PC **does not go to sleep**.

4. In the Wordscapes Auto 2.0 launcher app on the phone, enable
   **Use Gemma instead of old wheel detector**.
   Set Ollama server URL to `http://127.0.0.1:11434`
   and model to `gemma4:12b`.
   Tap **SAVE SETTINGS + TEST OLLAMA**; it should report connected
   and that the model was found.

5. Enable Accessibility for Wordscapes Auto (if not already enabled).
   Open Wordscapes at the **main menu** and tap the floating **AUTO** button.
   AUTO ON (green) indicates it is running. Use **STOP** to cancel.
   No CAL is needed for Gemma mode; CAL still works for the older OCR engine.

For home Wi-Fi use the Windows PC's private LAN IP in the Ollama URL
instead of loopback and configure Ollama's `OLLAMA_HOST` / Windows Firewall
for private LAN access. Do **not** forward Ollama port 11434 to the internet.
USB ADB reverse is recommended because the default Ollama server can remain
bound to the PC's loopback interface.

### AI control loop

- **Main menu:** a full-screen Gemma screenshot query identifies the actual
  Level button and its center; two independent screenshot checks are required
  before a tap is issued by Android's Accessibility gesture service.
- **Puzzle:** Gemma locates the wheel from a full-screen screenshot; a second,
  enlarged **wheel crop** is sent to the model to obtain letters *and each
  letter's touch coordinates*. The crop is then re-read to check the letter
  sequence and approximate coordinates before any auto-swiping. Existing
  local dictionary + gesture code enters candidate words.
- **Win / Tap to Skip:** detects the visible Continue/Tap to Skip control,
  checks it again, and taps it; then begins looking for the next Level button.
- **Interstitial ads:** identifies an ad, waits, and closes/skips it
  **only if the screenshot clearly shows an actionable X, Close, or Skip ad
  control** on two checks. It never deliberately clicks install, download,
  purchase, watch-to-earn, or engagement controls. Ads without a usable close
  option remain on-screen until dismissible; repeated unresolved screens stop
  AUTO.
- **Failure behavior:** AI connection failures, disagreeing letter reads,
  unclear positions, or unrecognized screens cause retries and eventually
  pause automation rather than guessing at random buttons. The **SCAN** button
  can perform a single Gemma scan and display an annotated review to diagnose
  letter-coordinate errors. The old scanner remains available by disabling
  the Gemma checkbox.

Gemma 4 12B is **not running on the phone**. Ollama/PC must stay awake and
connected while the app uses the model. App screenshots are sent to the URL
configured by the user. Keep that URL limited to a trusted local server.

**Limits:** A successful GitHub Actions build confirms only compilation.
AI vision accuracy, real-world ad layouts, overnight stability, and all
Wordscapes variants require **on-device testing**. Vision models can read text
well while still getting exact screen coordinates wrong; this version verifies
position agreement but cannot guarantee flawless gameplay. Ads may be
unskippable or show controls late, and the automation never bypasses a timer.

### Local API details

The app uses Ollama's `POST /api/chat` with base64-encoded JPEG screenshots
in `messages[].images`, `format: "json"`, `stream: false`, and
`temperature: 0`. Calls are asynchronous on Android worker threads.
The model name and URL are editable in the app. Model data never ships
inside the APK. Internet access is requested so Android can access the user
configured Ollama endpoint and optional legacy dictionary suggestions.

---

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
