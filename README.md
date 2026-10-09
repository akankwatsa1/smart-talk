# SmartTalk

A conversational AI assistant. Two editions, one product:

| Edition | Location | Stack | Status |
| :--- | :--- | :--- | :--- |
| **Desktop** (Windows) | repository root | Python local server + web UI, wrapped with PyInstaller/Electron | working |
| **Android** | `android/` | Kotlin + Jetpack Compose, native | in development |

Both editions talk to any OpenAI-compatible `/chat/completions` endpoint, so the
provider list, models, and API keys you already use on the desktop apply
unchanged on the phone.

## Licence

**SmartTalk is commercial, proprietary software. It is licensed, not sold.**

Reverse engineering, decompilation, modification, redistribution, resale, or
hosting as a service requires **prior written permission** from the copyright
holder. See [`LICENSE.txt`](LICENSE.txt) — that file is the authoritative terms,
and it applies to the desktop and Android editions alike.

Copyright (c) 2026 Akankwatsa Andrew OBA. All rights reserved.

## The Android edition

See [`android/README.md`](android/README.md) for build, test and release
instructions, and for the reasoning behind what did and did not port from the
desktop app.

Quick start, once `android/tools/` has been provisioned:

```powershell
cd android
pwsh -File tools\build-apk.ps1 -Install
```

## Reporting a problem

When something fails, capture the **exact error text** first — for an Android
install that is normally the `adb install` output, e.g.

```
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.smarttalk.app
signatures do not match previously installed version]
```

That one line identifies the cause. A paraphrase such as "app was not
installed" does not, and leads to guessing.
