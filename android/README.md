# SmartTalk for Android

Native Kotlin / Jetpack Compose edition of SmartTalk. It talks directly to any
OpenAI-compatible `/chat/completions` endpoint, streaming responses as they
arrive.

- **Package** `com.smarttalk.app`
- **minSdk 26** (Android 8.0) — **targetSdk 35** — **compileSdk 35**
- **Tested on** Infinix X6528, Android 13 (API 33), arm64-v8a
- **Licence** commercial and proprietary — see [`LICENSE.txt`](LICENSE.txt)

---

## What this app does

| Feature | Notes |
| :--- | :--- |
| Streaming chat | Server-sent events, rendered token by token |
| Multiple providers | DeepSeek, TokenHarbor, APINex, OpenAI, Gemini, OpenRouter, Anthropic, Ollama, or any custom base URL |
| Per-provider settings | Each provider keeps its own base URL, key, model and token limit, so switching never loses a key |
| Saved conversations | One JSON file per chat, in the app's private storage |
| Markdown rendering | Headings, lists, tables-free code blocks, quotes, inline emphasis |
| Voice input | The on-device Android speech recogniser, with partial results |
| Web grounding | Optional live search (DuckDuckGo Lite, Google News, Wikipedia) fed to the model as context, with the sources offered as buttons |
| Accessibility | Heading semantics, live regions, labelled controls, 48 dp targets, custom TalkBack actions |

---

## Deliberate decisions: what did NOT port from the desktop app

Porting feature-for-feature is a trap. These were dropped on purpose.

**The local Python proxy (`proxy.py`) — removed entirely.** On the desktop it
existed for two reasons: to defeat browser CORS rules, and to keep the API key
out of page JavaScript. On Android neither problem exists — the app makes the
HTTP call itself, from Kotlin — so a proxy would be a second moving part with no
benefit.

**The FFmpeg transcription pipeline — replaced.** The desktop app recorded
audio, shelled out to FFmpeg to resample it, then drove the Windows
`System.Speech` engine through PowerShell. Android has a speech recogniser in
the platform, so that whole chain collapses to one API call, and it works
offline on most phones.

**The file scanner — not yet ported.** The desktop app walks the filesystem to
build context from local files. Android has no such thing as a raw filesystem
path for user documents: everything arrives as a `content://` URI behind a
runtime-granted permission. Doing this properly means the Storage Access
Framework, so it is planned rather than half-done.

**No icon font and no `material-icons-extended`.** Every control is a
text-labelled button. That is smaller, and it reads better under TalkBack than
an unlabelled glyph. It also removes a dependency whose API surface has a habit
of changing between versions.

---

## Prerequisites

Everything lives under this folder to keep `C:` free. Nothing needs installing
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
fails with `schannel: AcquireCredentialsHandle failed (SEC_E_NO_CREDENTIALS)`,
while Node's own TLS stack works. If a download ever fails, that is the reason,
not the network.

---

## Building

```powershell
cd android
pwsh -File tools\build-apk.ps1              # build and verify only
pwsh -File tools\build-apk.ps1 -Install     # build, verify, install
```

The script does not merely claim success. It checks the APK exists, runs
`apksigner verify --print-certs`, dumps the manifest with `aapt dump badging`,
and prints a SHA-256 checksum. Compiling, signing, and installing are three
separate claims; this makes each one evidenced.

---

## The signing key — read this before anything else

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
genuine SmartTalk update. If the repository is ever made private, committing
them instead is a reasonable trade — the priority is that the key is never lost
and never regenerated.

Losing the key means this app can never be updated again. There is no recovery.

The certificate SHA-256 fingerprint printed by `make-keystore.ps1` must match
what `apksigner verify --print-certs` reports after every build. If it does not,
the wrong key was used and the build must not be shipped.

---

## Installing on a device over wireless debugging

The phone must be paired first. Pairing codes expire, so do this immediately
before connecting.

```powershell
$adb = "C:\Users\HP ELITEBOOK 840\Documents\_adb\platform-tools\adb.exe"

# 1. Pair (code and port come from the phone's "Pair device with pairing code" dialog)
& $adb pair 192.168.100.207:<pairing_port> <6-digit-code>

# 2. Find the live debugging port, then connect
& $adb mdns services
& $adb connect 192.168.100.207:<debug_port>

# 3. Confirm, then install
& $adb devices -l
& $adb install -r app\build\outputs\apk\release\app-release.apk
```

`-r` replaces an existing install while keeping the same key and preserving user
data. If it fails, **read the exact failure code before changing anything.**

Note that the adb daemon does not survive between separate shell invocations on
this host, so connect and the following command usually need to be in the same
call.

---

## CI

`.github/workflows/build-android-apk.yml` builds and verifies an APK on every
push. If the four `SMARTTALK_*` repository secrets are present it builds a
signed **release** APK; otherwise it falls back to a debug APK so the workflow is
never broken by a missing secret.

To enable release signing in CI, add these repository secrets:

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
├── app/
│   ├── build.gradle.kts          pinned versions, signing config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/smarttalk/app/
│       │   ├── MainActivity.kt         two-screen host
│       │   ├── ChatScreen.kt           chat UI + markdown rendering
│       │   ├── SettingsScreen.kt       provider and model settings
│       │   ├── ChatViewModel.kt        all conversation state
│       │   ├── ChatApi.kt              streaming HTTP client
│       │   ├── WebSearch.kt            DuckDuckGo / News / Wikipedia
│       │   ├── SpeechInput.kt          on-device speech recognition
│       │   ├── Markdown.kt             small parser, no dependency
│       │   ├── Models.kt               messages and conversations
│       │   ├── Providers.kt            provider presets and settings
│       │   ├── SmartTalkStore.kt       persistence
│       │   └── Theme.kt                Material 3 colour schemes
│       └── res/                        strings, vectors, network config
├── tools/
│   ├── make-keystore.ps1         run once, ever
│   ├── build-apk.ps1             build + verify + install
│   └── dl.mjs                    Node downloader for the toolchain
├── LICENSE.txt
└── README.md
```

---

## Security notes

- **No API key is stored in this repository or in the source.** The user types
  their key on the device; it is kept in the app's private `SharedPreferences`.
- **Cleartext HTTP is refused**, except to `localhost`/`127.0.0.1`, so a local
  Ollama server works while every remote provider must use HTTPS.
- **Backup and device transfer are disabled** in the manifest, so conversations
  and keys are not silently copied to a new device or to a cloud backup.
- The desktop `settings.json` in the repository root contains a **live API key**
  and is gitignored for that reason. If it has ever been committed, rotate that
  key at the provider.

---

## The pre-flight checklist this project follows

Derived from a costly earlier port of a desktop app to Android.

- [x] Project on a drive with >50 GB free — F: has 218 GB; C: has 12 GB
- [x] Keystore created ONCE, never regenerated, backed up
- [x] CI builds, signs, verifies and publishes on every push
- [x] Device connected over wireless debugging *before* feature work
- [x] minSdk 26, every ABI kept, R8 off for the first releases
- [x] Storage uses app-private files, not raw paths
- [x] Accessibility semantics written alongside each control, not retrofitted
- [x] Every API verified against the actual dependency versions in use
- [ ] Proven by installing over an existing copy — pending the second release
- [x] On any failure: capture the error code first, fix second
