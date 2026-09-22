# Microdroid

Microdroid is a personal automation toolbox for Android. It has no built-in automations: every
automation is a small script that runs on the phone itself. Write one in the editor, import a
`.java` file, or describe what you want in the Chat tab and let an AI agent write it for you.

It is built for the small, personal jobs a phone should be able to do on its own — filing
downloads, reacting to notifications, keeping an eye on battery and Wi-Fi, nudging you at the
right time — without handing your data to a service you don't control.

## What an automation looks like

A script declares an id, a name, a description and a `run(ctx)`. It is BeanShell — Java-like and
interpreted on-device — so there is no build step and no project to set up:

```java
id() { return "file_downloads"; }
name() { return "File the downloads"; }
description() { return "Moves new files out of Download."; }
run(ctx) {
    ctx.log("hello");
    return "OK";
}
```

`ctx` covers what automations actually do: file operations, notifications, TTS, HTTP requests,
launching apps, and UI automation (tap/swipe/type on screen when Accessibility is enabled).
A script can also define `on_event(ev, ctx)` to react to trigger payloads directly.

Scripts run with the app's permissions and are **not sandboxed** — only run code you trust. A
script that fails three times in a row is disabled automatically, and everything it logs shows up
in the Logs tab.

## Running automations

- **On demand** — tap Run on any script.
- **On a schedule** — give each script its own repeating interval, from 15 minutes to 24 hours.
- **On an event** — triggers watch for a time of day, boot, battery level, Wi-Fi network,
  notifications, SMS, calls, app installs, motion gestures, location, sunrise/sunset, a LAN
  webhook, or a periodic HTTP check.
- **Only when it makes sense** — constraints filter triggers: time window, battery, charging,
  screen state, Wi-Fi SSID, network type, foreground app, headphones, DND, and location.

## The app

Four tabs and a couple of menus, deliberately small:

| Tab | What it's for |
|---|---|
| **Scripts** | Your automations: run, enable, schedule, edit, read logs, set triggers |
| **Chat** | An AI agent that can write, edit, run and schedule scripts for you |
| **Logs** | One feed of run results, trigger firings and errors |
| **Settings** | Model providers, permissions, scheduler, ADB imports, version |

## An agent that writes automations

The Chat tab talks to a model provider you configure — OpenAI-compatible endpoints, Anthropic,
Gemini, or your own entry — and gives it tools to list, read, create, edit, delete, run and
schedule your scripts, plus set their triggers and constraints.

- **Ask mode** (default): every change is shown first — script diffs, run intent, schedule — and
  you tap Apply.
- **Bypass mode**: saves, edits and runs apply immediately; deletes and schedule changes still ask.
- API keys are AES/GCM-encrypted with the Android Keystore and sent only to the base URL you set.
- Models without tool calling still work: a fenced script block in the reply gets a **Save script**
  button.

## From a PC

The app exposes an adb broadcast API to run scripts, change schedules, import or remove scripts,
set triggers, and inject events — handy from a keyboard. Script imports over adb sit behind an
**Allow ADB imports** toggle (on by default), and Settings shows the exact commands.

## Permissions

Microdroid asks for access only for the features you use: all-files access for file automations,
notification access for notification triggers, accessibility for UI automation, plus optional
SMS, phone, calendar, location, DND, brightness and exact-alarm access. The permission center
lists each one with what it unlocks.

## Building

Requires JDK 17 and Android SDK 34; the app runs on Android 11 (API 30) and newer.

```
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Technical reference

Implementation-level notes live in [TECHNICAL.md](TECHNICAL.md): the full script contract and
`ctx` API, the adb action list, trigger and constraint schemas, storage layout, chat architecture,
and the security model.
