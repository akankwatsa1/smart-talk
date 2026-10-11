# SmartTalk for Android

Native Kotlin / Jetpack Compose edition of SmartTalk. It talks directly to any
OpenAI-compatible `/chat/completions` endpoint, streams responses as they
arrive, and can read pages, photographs and files.

- **Package** `com.smarttalk.app`
- **Version** 1.1.0 (versionCode 2)
- **minSdk 26** (Android 8.0) â€” **targetSdk 35** â€” **compileSdk 35**
- **Tested on** Infinix X6528, Android 13 (API 33), arm64-v8a
- **Licence** commercial and proprietary â€” see [`LICENSE.txt`](LICENSE.txt)

---

## What this app does

| Feature | Notes |
| :--- | :--- |
| Streaming chat | Server-sent events, rendered token by token |
| **Multiple API accounts** | Hold several keys at once, name them, and switch between them |
| **Per-capability routing** | Point chat, image understanding, image generation and read-aloud at *different* accounts, so a cheap model can chat while a vision model reads photos and an image model draws |
| **Live web grounding** | Automatic or always-on search that downloads the top pages, keeps table structure, and states today's date so current facts are actually answerable |
| File attachments | Pick any document; text, code, CSV, JSON and **.docx** are read into the prompt |
| Folder scanner | Choose a folder and it is walked and condensed into one attachment |
| Camera capture | Photograph a page and send it to the image-understanding account |
| **Read aloud** | A button on every answer, using the free on-device voice, with an optional cloud voice |
| **Image generation** | Describe a picture and the image-generation account draws it, shown in the chat |
| Image understanding | Attach or photograph an image and the vision account describes it or reads its text |
| Saved conversations | One JSON file per chat, in the app's private storage |
| Markdown rendering | Headings, lists, code blocks, quotes, inline emphasis |
| Accessibility | Heading semantics, live regions, labelled controls, 48 dp targets, custom TalkBack actions |

---

## Accounts and capability routing

A **provider** is a company. An **account** is one credential you hold with them.
The earlier design stored a single active provider, which could not express
"two keys with the same company" or "a different model for each job". Accounts
fix that:

- **Settings â†’ API accounts** lists every account, with Add / Edit / Delete and
  a *Make default* action that reorders the list.
- **Settings â†’ Which account does what** binds each capability to an account, or
  leaves it on *Same as chat*.

| Capability | Used for |
| :--- | :--- |
| Chat | Text conversation |
| Image understanding | Describing a photo, or reading text out of one |
| Image generation | Creating a picture from a description |
| Cloud read-aloud voice | Optional paid voice; the device voice is the default |

When a message carries an image and a dedicated image-understanding account is
configured, that request is routed there automatically; otherwise the chat
account handles it.

Keys live only in this app's private `SharedPreferences`. They are never written
to this repository, and the manifest disables backup and device transfer so they
do not silently leave the phone.

---

## Live information, and why it used to fail

Asking the app for the current Premier League table returned nothing useful.
Three separate defects were found by probing the real endpoints, not by guessing:

**1. The search source was silently dead.** DuckDuckGo Lite **rejects plain GET
requests**, answering HTTP 202 with an empty shell; it only returns results for a
POSTed form. The previous code used GET, so it contributed zero results while
appearing to work. It is now a POST, and Bing is queried alongside it so one
blocked source cannot starve the model.

**2. Snippets are not answers.** Search-result teasers do not contain a league
table. The app now downloads the top pages and gives the model their content.

**3. Tables were being destroyed twice over.** Wikipedia's plain-text extract API
strips tables out completely, so even a perfect Wikipedia lookup returned the
heading `== League table ==` with nothing beneath it. And a general HTML-to-text
pass turns `</div>` into a line break, which is right for prose but shatters a
single table cell â€” club names ended up stranded on their own lines, leaving rows
of bare numbers that a model would confidently map to the wrong teams.

Tables are now converted **first and separately**: each `<tr>` becomes exactly
one pipe-separated line and a cell can never wrap. Verified against live data:

```
1 | Manchester City | 5 | 5 | 0 | 0 | 13 | 5 | +8 | 15
2 | Arsenal | 6 | 5 | 0 | 1 | 10 | 5 | +5 | 15
3 | Brighton & Hove Albion | 6 | 4 | 1 | 1 | 18 | 5 | +13 | 13
```

The date is also injected into every request, because a model asked for "the
current table" will otherwise answer from its training data and be confidently
wrong.

**Search modes.** *Automatic* (the default) searches when a question looks
time-sensitive â€” "current", "latest", "standings", "price", a recent year, and
so on. *Always* searches every turn. *Off* never does. Downloading pages can be
disabled, but it is what makes tables and figures accurate.

`probes/` in this folder holds the probes used to establish all of this
(`probe-search.mjs`, `probe-sources.mjs`, `probe-e2e.mjs`, `probe-tables.mjs`,
`probe-trigger.mjs`, `probe-results.mjs`, `probe-fetchable.mjs`,
`probe-engines.mjs`, `probe-bingdecode.mjs`).

### Four more defects found by asking for yesterday's EPL results

That question still failed after the fixes above, for four further reasons. All
four are now handled.

**Bing's links are redirects.** Bing returns `bing.com/ck/a?...&u=a1<base64>` for
every result. Fetching that redirect gives HTTP 204 with **no body**, so Bing
appeared useless. The real destination is base64-encoded in the `u=` parameter;
decoding it turned Bing from a dead source into a working one.

**Search engines cannot resolve "yesterday".** Sent literally, "yesterday's epl
results" made Bing return pages about *Windows File Explorer*. The app now
resolves relative dates before searching and sends both the original question and
a date-anchored version ("premier league results 10 October 2026"), which returned
relevant results.

**The best result is often unfetchable.** For that query the top hit was
sportytrader, which answers HTTP 403. More candidates are now tried than are
needed, up to three times the page budget, and the failures are skipped, so a
search that *found* the right page becomes an answer that actually contains the
facts.

**DuckDuckGo blocks bursts.** After a run of queries it answers HTTP 202 with zero
links even when spaced several seconds apart. It is therefore best-effort with a
ten-minute cooldown rather than the primary source, and Bing carries the load.

### How answers are presented

The answer is analysed prose, not a paste of page text or a wall of URLs. Sources
are named inside the sentences where they are used ("According to BBC Sport, ..."),
and every real link is a button beneath the message. Any raw address that does
reach the body is collapsed to the source name, so nothing unreadable is left in
the text and nothing is lost from the citations.

---

## Deliberate decisions: what did NOT port from the desktop app

Porting feature-for-feature is a trap. These were dropped on purpose.

**The local Python proxy (`proxy.py`) â€” removed entirely.** On the desktop it
defeated browser CORS rules and kept the API key out of page JavaScript. Android
has neither problem, so a proxy would be a second moving part with no benefit.

**The FFmpeg transcription pipeline â€” replaced.** The desktop app recorded
audio, shelled out to FFmpeg to resample it, then drove Windows `System.Speech`
through PowerShell. Android has a speech recogniser in the platform, so that
chain collapses to one API call, and it works offline.

**The raw filesystem scanner â€” replaced with the Storage Access Framework.**
`file-scanner.js` walked real paths. Android has no such thing for user
documents: a folder arrives as a `content://` tree URI and every child is another
content URI reached through `DocumentsContract`. `FileScanner.kt` is that port.
The scan is bounded (40 files, 60,000 characters, depth 4) because pasting a
whole phone folder into one prompt would exceed every context window and cost
real money.

**No icon font and no `material-icons-extended`.** Every control is a
text-labelled button â€” smaller, and far better under TalkBack. The action strip
is laid out over two rows rather than one scrolling row, because a control
scrolled off the edge is invisible on a touch device: there is no hover and no
scrollbar to hint that it exists.

---

## Prerequisites

Everything lives under this folder to keep `C:` free. Nothing is installed
system-wide.

| Tool | Location | Purpose |
| :--- | :--- | :--- |
| JDK 21 (Temurin) | `Documents\_jdk\jdk` | Gradle and `keytool` |
| Android SDK + platform-tools | `android\tools\sdk` | compileSdk 35, build-tools 35.0.0 |
| Gradle 8.9 | `android\tools\gradle\gradle-8.9` | Build runner |
| Gradle caches | `android\gradle-home` | Keeps `~/.gradle` off `C:` |
| adb | `Documents\_adb\platform-tools\adb.exe` | Device install |

`tools\dl.mjs` is a small Node downloader used to fetch these. Node is used
rather than `Invoke-WebRequest` because this host's PowerShell/.NET TLS stack
fails with `schannel: AcquireCredentialsHandle failed (SEC_E_NO_CREDENTIALS)`
while Node's own TLS stack works.

---

## Building

```powershell
cd android
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1            # build + verify
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -Install   # build + verify + install
```

The script does not merely claim success. It checks the APK exists, runs
`apksigner verify --print-certs`, dumps the manifest with `aapt dump badging`,
and prints a SHA-256 checksum. Compiling, signing, installing and running are
four separate claims; this makes each one evidenced.

Gradle is launched through `cmd` so its output is captured raw. Captured through
PowerShell, every line of Gradle's stderr becomes a formatted `ErrorRecord`,
which wraps long Kotlin diagnostics across several lines and buries them under
`At line: ...` noise â€” that alone cost a diagnosis round-trip.

---

## The signing key â€” read this before anything else

`tools\make-keystore.ps1` creates the release key **once** and then refuses to
run again. That is deliberate.

Android identifies an app by its package name *and* its signing key. If the key
changes, the device rejects the update and reports only the misleading
`app was not installed`; the real cause is:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.smarttalk.app
signatures do not match previously installed version
```

Users would have to uninstall first, losing every saved conversation.

**Back up these two files now:**

```
android\keystore\smarttalk-release.jks
android\keystore.properties
```

They are excluded from git because this repository is public: anyone holding the
keystore and its password could sign an APK that Android would accept as a
genuine SmartTalk update. If the repository is ever made private, committing them
is a reasonable trade â€” the priority is that the key is never lost and never
regenerated.

Losing the key means this app can never be updated again. There is no recovery.

The certificate fingerprint printed by `make-keystore.ps1` must match what
`apksigner verify --print-certs` reports after every build:

```
SHA-256  44:65:47:CC:C5:92:07:D6:E7:A8:0A:29:22:18:E7:72:
         DE:EB:EF:53:D6:52:46:72:AF:9E:D1:3A:DA:BB:C2:F1
```

If it differs, the wrong key was used and the build must not be shipped.

---

## Installing on a device over wireless debugging

The debugging **port changes every time wireless debugging is toggled**, so never
hard-code it â€” rediscover it each session:

```powershell
$adb = "C:\Users\HP ELITEBOOK 840\Documents\_adb\platform-tools\adb.exe"
& $adb start-server
& $adb mdns services                       # read the IP:port from the output
& $adb connect 192.168.100.207:<port>
& $adb -s 192.168.100.207:<port> install -r app-release.apk
```

Two host quirks worth knowing: the adb daemon does **not** survive between
separate shell invocations here, so discovery, connect and the command must share
one call; and it is usually simpler to address the stable mDNS name
(`adb-11217373C6012038-n4g6fI._adb-tls-connect._tcp`) instead of the rotating IP
and port.

`-r` replaces an existing install, keeping the same key and the user's data. If it
fails, **read the exact failure code before changing anything.**

---

## CI

`.github/workflows/build-android-apk.yml` builds and verifies an APK on every
push. If the four `SMARTTALK_*` repository secrets are present it builds a signed
**release** APK; otherwise it falls back to a **debug** APK so the workflow is
never broken by a missing secret.

> A debug APK is a **different application** â€” package `com.smarttalk.app.debug`,
> signed with the throwaway debug key. It will not update a release install.

No third-party Android setup action is used: the `ubuntu-latest` image already
ships the SDK and cmdline-tools, and `apksigner`/`aapt` are invoked by absolute
path because the build-tools `bin` directory is not guaranteed to be on `PATH`.

| Secret | Value |
| :--- | :--- |
| `SMARTTALK_KEYSTORE_BASE64` | `base64 -w0 android/keystore/smarttalk-release.jks` |
| `SMARTTALK_STORE_PASSWORD` | from `keystore.properties` |
| `SMARTTALK_KEY_ALIAS` | `smarttalk` |
| `SMARTTALK_KEY_PASSWORD` | from `keystore.properties` |

---

## Project layout

```
android/
â”œâ”€â”€ app/src/main/
â”‚   â”œâ”€â”€ AndroidManifest.xml          permissions, FileProvider for the camera
â”‚   â”œâ”€â”€ java/com/smarttalk/app/
â”‚   â”‚   â”œâ”€â”€ MainActivity.kt          two-screen host
â”‚   â”‚   â”œâ”€â”€ ChatScreen.kt            chat UI, attachments, read-aloud
â”‚   â”‚   â”œâ”€â”€ SettingsScreen.kt        accounts, routing, live-data settings
â”‚   â”‚   â”œâ”€â”€ ChatViewModel.kt         all conversation and speech state
â”‚   â”‚   â”œâ”€â”€ Accounts.kt              ApiAccount, Capability routing, AppSettings
â”‚   â”‚   â”œâ”€â”€ Providers.kt             provider presets and model suggestions
â”‚   â”‚   â”œâ”€â”€ ChatApi.kt               streaming client, vision image parts
â”‚   â”‚   â”œâ”€â”€ ImageGen.kt              /images/generations
â”‚   â”‚   â”œâ”€â”€ WebSearch.kt             DuckDuckGo POST, Bing, news, page fetching
â”‚   â”‚   â”œâ”€â”€ Attachments.kt           file reading, .docx extraction, downscaling
â”‚   â”‚   â”œâ”€â”€ FileScanner.kt           Storage Access Framework folder walk
â”‚   â”‚   â”œâ”€â”€ Speech.kt                on-device text-to-speech
â”‚   â”‚   â”œâ”€â”€ SpeechInput.kt           on-device speech recognition
â”‚   â”‚   â”œâ”€â”€ CloudVoice.kt            optional cloud read-aloud
â”‚   â”‚   â”œâ”€â”€ Markdown.kt              small parser, no dependency
â”‚   â”‚   â”œâ”€â”€ Models.kt                messages and conversations
â”‚   â”‚   â”œâ”€â”€ SmartTalkStore.kt        persistence and settings migration
â”‚   â”‚   â””â”€â”€ Theme.kt                 Material 3 colour schemes
â”‚   â””â”€â”€ res/                         strings, vectors, file paths, network config
â”œâ”€â”€ tools/
â”‚   â”œâ”€â”€ make-keystore.ps1            run once, ever
â”‚   â”œâ”€â”€ build-apk.ps1                build + verify + install
â”‚   â””â”€â”€ dl.mjs                       Node downloader for the toolchain
â”œâ”€â”€ LICENSE.txt
â””â”€â”€ README.md
```

---

## Security notes

- **No API key is stored in this repository or in the source.** Keys are typed on
  the device and kept in the app's private storage.
- **Cleartext HTTP is refused** except to `localhost`/`127.0.0.1`, so a local
  Ollama server works while every remote provider must use HTTPS.
- **Backup and device transfer are disabled**, so conversations and keys are not
  silently copied to a new device or cloud backup.
- **The `CAMERA` permission is deliberately not declared.** The camera is reached
  through `ACTION_IMAGE_CAPTURE`, and declaring the permission would make that
  intent throw until the user granted it.
- The desktop `settings.json` in the repository root contains a **live API key**
  and is gitignored for that reason. If it has ever been committed, rotate it.

---

## Pre-flight checklist this project follows

Derived from a costly earlier port of a desktop app to Android.

- [x] Project on a drive with >50 GB free â€” F: has 216 GB; C: has 12 GB
- [x] Keystore created ONCE, never regenerated, backed up in two places
- [x] CI builds, signs, verifies and publishes on every push
- [x] Device connected over wireless debugging *before* feature work
- [x] minSdk 26, every ABI kept, R8 off for the first releases
- [x] Storage uses the Storage Access Framework, not raw paths
- [x] Accessibility semantics written alongside each control, not retrofitted
- [x] Every API verified against the actual dependency versions in use
- [x] **Proven by installing over an existing copy** â€” v1.0.0 updated in place to
      v1.1.0 with `adb install -r`, returning `Success`
- [x] On any failure: capture the error code first, fix second
