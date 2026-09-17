# Microdroid (was ScreenshotMover)

Android automation toolbox. Every automation is a **BeanShell script that runs on-device** —
there are no built-in automations. UI, scheduler, the adb API and the optional **AI chat agent**
pick scripts up automatically.
Package `com.example.screenshotmover` (label **Microdroid**), debug signature, minSdk 30 / targetSdk 34.

## Script contract

A script defines four functions (Java-like, untyped, no imports/classes):

```java
id() { return "my_plugin"; }          // [a-z0-9_]{2,32}, becomes automation_id, "all" is reserved
name() { return "My plugin"; }
description() { return "What it does."; }
run(ctx) {
  ctx.log("hello");
  return "OK";                        // becomes last_<id> and the run log
}
```

`ctx` API: `log, list, exists, isFile, isDirectory, length, mkdirs, copy, delete, move, scan, getData, putData`.
`move`/`copy` never overwrite (adds `_1`, `_2`…); `move` verifies the copy before deleting the source.
Scripts are **not sandboxed** — BeanShell can reach the full Java API with the app's all-files
permission, so only import scripts you trust. Failures: 3 strikes → auto-disabled.

Storage: `files/plugins/<id>/Plugin.java` + `meta.json`. Add a script via the phone UI (FAB **+**)
or by pushing a `.java` and importing it over adb.

## PC control (explicit `-n` required on Android 8+)

```
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id all
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_ENABLE -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_DISABLE -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_SET_INTERVAL -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin --ei interval_min 360
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_LIST -n com.example.screenshotmover/.core.MicrodroidReceiver
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_START_SCHEDULER -n com.example.screenshotmover/.core.MicrodroidReceiver
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_STOP_SCHEDULER -n com.example.screenshotmover/.core.MicrodroidReceiver

# push a script then import it (import requires "Allow ADB imports", ON by default)
adb -s R9AMA0LCXEJ push my_plugin.java /storage/emulated/0/Download/my_plugin.java
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_PLUGIN_IMPORT -n com.example.screenshotmover/.core.MicrodroidReceiver --es path /storage/emulated/0/Download/my_plugin.java
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_PLUGIN_REMOVE -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
```

`pc_move_now.bat [automation_id]` wraps ACTION_RUN (defaults to `all`).

## AI chat (bottom tab: Chat)

Configure providers in the **Settings** tab → **Model providers**: built-ins (OpenAI-compatible, Anthropic,
Gemini, OpenCode Go) plus your own entries (**Add provider** with a name, API format, base URL, model and key).
Each row shows its base URL/model and key status; tap it to **Set as active**, **Edit** (base URL, model, API
key, with Test) or **Delete** (custom ones). Keys are AES/GCM-encrypted per provider.

| provider | notes |
|---|---|
| OpenAI-compatible | OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Ollama/LM Studio; base URL editable |
| Anthropic | native Messages API (streaming + tool use) |
| Gemini | native generateContent (streaming + function calling) |

- Keys are AES/GCM-encrypted with the Android Keystore and sent only to the configured base URL.
  The key prefs are excluded from backup (Keystore is never backed up).
- **Ask** mode (default): the agent proposes create/edit/delete/run; you see a diff and tap Apply.
  **Bypass** mode: saves/edits/runs apply immediately — deletes and schedule changes still ask.
- Agent tools: `list_scripts`, `read_script`, `save_script`, `delete_script`, `run_script`, `set_schedule`.
  Save results are validated by BeanShell, so compile errors are returned to the model for repair.
  Models without tool calling get a fallback: a fenced code block comes back with a **Save script** button.
- The agent's system prompt includes the script contract, `ctx` API, phone paths and prompt-injection rules.
- Security note: the app now has `INTERNET`, and BeanShell is **not** sandboxed — scripts can use the
  network and could read the stored API key. Only import/ask for scripts you trust; use Ask mode by default.

## Triggers (per script: More → Triggers & constraints)

A script can run on events instead of only the interval timer. Optional `on_event(ev, ctx)` handles events;
without it the normal `run(ctx)` runs. `ev` is a Map (`type`, `package`, `text`, `level`, `ssid`, ...).

- **No extra access**: time/day schedule (exact alarms), boot, power, headset, app installed/removed,
  media button, broadcast intent, battery, screen on/off, network/Wi-Fi, motion (shake/flip/proximity),
  device unlock, sunrise/sunset, LAN webhook + periodic HTTP checks.
- **Needs access (grant in menu → Permissions)**: notifications (Notification access), SMS/calls,
  app opened/closed (usage access or accessibility), calendar, location (fine + background).
- **Constraints** (all must match): time window, battery, charging, screen, Wi-Fi SSID, network type,
  foreground app, headphones, DND, near location.
- **Actions** added to `ctx`: `notify`, `vibrate`, `speak`, `setVolume`, `setBrightness`, `dnd`,
  `launchApp`, `openUrl`, `sendSms`, `call`, `httpGet/httpPost`, `setClipboard`, `toast`.
- **UI automation** (`ctx.tap/swipe/typeText/pressBack/home/scroll/clickText/findText/currentApp`)
  works when the Accessibility service is enabled.
- Known limits: background activity launches (`launchApp`/`openUrl` inside triggers) can be blocked by
  Android; the webhook listens on the LAN only (`:8765`); location triggers use proximity alerts.

## Status

```
adb -s R9AMA0LCXEJ shell run-as com.example.screenshotmover cat /data/data/com.example.screenshotmover/shared_prefs/microdroid.xml
```

## Upgrade notes

- Alarms are identified per plugin id; on first launch after upgrading, old-format alarms are
  cancelled and enabled scripts rescheduled (flag `migrated_v2`).
- Legacy v1 `mover` prefs (`interval_min`, `last_status`) are carried over to the `screenshots` keys once.
- `ACTION_MOVE_NOW` / `.MoveReceiver` (pre-Microdroid) no longer exist; use ACTION_RUN.
- After a reboot the OS may defer the BOOT_COMPLETED broadcast (cached-app broadcast deferral)
  until the app is opened; enabled schedules are restored on delivery.

## Verified 2026-09-16 (SM-A207F, Android 11 / API 30)

- Build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Upgrade: legacy alarms cancelled/rescheduled once, `migrated_v2=true`, no duplicate alarms
- Import/run/remove script over adb; move semantics (no-overwrite + verify-then-delete) on device
- AI chat: settings dialog, connection test against a live endpoint (401 handled), key encrypted at
  rest (`microdroid_llm.xml` shows IV:ciphertext only), no-key guard opens settings
