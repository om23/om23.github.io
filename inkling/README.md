# Inkling — an AI diary for Supernote

Write with your pen. Rest it. The page drinks your ink and answers in a flowing hand.

Inkling is a port of the idea behind [Riddle](https://github.com/MaximeRivest/Riddle) — Tom's diary
for the reMarkable Paper Pro — to **Supernote** e-ink tablets (Nomad, Manta, A5X/A6X). Where the
reMarkable runs Linux and Riddle reads pen input from raw evdev in Rust, Supernote devices run
Android with sideloading, so Inkling is a small Kotlin app.

## How it feels

1. Open Inkling. You get a blank white page.
2. Write to it with the pen. When the pen rests for ~2.5 seconds, your handwriting fades into
   the paper.
3. A moment later the diary answers — word by word, in a flowing script — holds the thought long
   enough to read, then fades back into the page.
4. Write again. The diary remembers the session's conversation.

**Gestures**

| Gesture | Effect |
|---|---|
| Write, then rest the pen | The diary reads and answers |
| Pen's eraser end | Unwrite strokes |
| Draw only a `?` | The diary explains itself |
| Two-finger hold on the page | Settings (API key, model, persona) |

## Device compatibility

| Device | Android | Runs Inkling? | How to install |
|---|---|---|---|
| Nomad (A6X2), Manta (A5X2) | 11 (API 30) | Yes | Official sideloading (Chauvet firmware) or ADB |
| A5X, A6X | 8.1 (API 27) | Yes at the API level (`minSdk 27`; verified by compiling against the real API 27 framework) | No official sideloading — ADB only, where available |

Sideloaded apps don't get Ratta's low-latency ink pipeline, so strokes appear with the
normal Android-view latency (~100–300 ms) rather than the native Notes feel. For a diary
you write a line into and wait on, that's fine — set expectations accordingly.

## How it works

- `InkView` captures stylus strokes (`MotionEvent.TOOL_TYPE_STYLUS`, with eraser-tool support)
  and runs the rest-timer. When ink rests, the strokes are rendered to a cropped PNG.
- `Oracle` sends that PNG to Claude (`claude-opus-4-8` by default) via the official
  [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java). One structured-output
  call returns both a transcription of the handwriting and the diary's reply; transcriptions are
  kept as text-only session history so the diary remembers the conversation without resending
  images.
- `ReplyView` inks the reply back word-by-word in Dancing Script, then fades it out with stepped
  gray levels (discrete alpha steps read better on e-ink than smooth animation).

No handwriting-recognition engine is involved — the vision model reads the page directly, which is
the same trick Riddle uses.

## Building

Requires JDK 17+ and the Android SDK (API 34). No Android Studio needed:

```sh
gradle wrapper            # once, to generate the wrapper
./gradlew assembleRelease # or assembleDebug for a debuggable build
```

The APK lands in `app/build/outputs/apk/`. The Dancing Script typeface is downloaded
automatically on first build (it's OFL-licensed; the repo carries no binaries).

## API keys & security

Inkling can answer via **Anthropic (Claude)** directly or via **[OpenRouter](https://openrouter.ai)**
(an OpenAI-compatible gateway to many models — Claude, GPT, Gemini, …). You pick the provider,
key, and model in the settings drawer (two-finger hold on the page). Get keys at
[platform.claude.com](https://platform.claude.com) or
[openrouter.ai/keys](https://openrouter.ai/keys).

**No keys live in this repository.** They come from one of two places, both kept out of git:

**1. Entered on-device (most secure).** Type the key once in settings. It's stored **encrypted at
rest** via the Android Keystore (AES-256-GCM; the key material is non-exportable and never written
to disk in plaintext) and sent only to the provider you selected. Nothing is baked into the APK.

**2. Pushed over adb (no typing, no secret in the APK — recommended for sideloading).** Since you
already sideload over adb, provision the key the same way. Copy `inkling.properties.example` to
`inkling.properties`, fill it in, and push it to the app's own external files dir:

```
adb push inkling.properties /sdcard/Android/data/com.ommahida.inkling/files/inkling.properties
```

On the next launch (or when you re-focus the app) Inkling reads it, stores the key **encrypted via
the Android Keystore**, and **deletes the plaintext file** from the device. The key never touches
the e-ink keyboard, is never embedded in the APK, and `inkling.properties` is gitignored so it never
reaches git. (If the push fails because the directory doesn't exist yet, launch the app once first —
that creates it — then push and re-focus.)

**3. Baked in at build time (convenient, but embeds the key in the APK).** You can instead supply
the key at build time from `local.properties` (gitignored) or environment variables — copy
`local.properties.example` to `local.properties`:

```
sdk.dir=/path/to/android/sdk
inkling.apiKey=sk-ant-...          # or set INKLING_API_KEY in the environment
inkling.openrouterKey=sk-or-...    # or INKLING_OPENROUTER_KEY
```

The build reads these into `BuildConfig`; an on-device key still overrides them. **Trade-off:** a
baked-in key is embedded in the APK binary and can be extracted from it, so prefer method 2 unless
the APK never leaves your device — and either way, set a spend limit on the key. `local.properties`
and common secret files are gitignored, so a key set this way never reaches git.

## Installing on a Supernote

1. Enable sideloading on the device (Nomad/Manta): **Settings → Security → Install unknown apps**
   (or transfer the APK and open it from the Files app, depending on firmware).
2. Copy the APK to the device over USB, or install directly with
   `adb install app/build/outputs/apk/debug/app-debug.apk` if ADB is enabled.
3. If you didn't inject a key at build time, open Inkling, two-finger hold, choose your provider,
   and enter your key.

## Credits

- Concept: [Riddle](https://github.com/MaximeRivest/Riddle) by Maxime Rivest — "Tom's diary" for
  the reMarkable Paper Pro.
- Reply typeface: [Dancing Script](https://github.com/googlefonts/DancingScript), SIL Open Font
  License 1.1 (see `licenses/DancingScript-OFL.txt`).
