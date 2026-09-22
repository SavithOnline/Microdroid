# Microdroid — technical reference

Implementation-level companion to [README.md](README.md): the script contract, the adb API,
trigger and constraint schemas, storage layout, and how the chat agent works.

## App facts

| | |
|---|---|
| Platform | Android 11+ (API 30), compile/target SDK 34 |
| Language | Java 17 |
| Application ID / namespace | `com.example.screenshotmover` |
| Version | 2.0 (versionCode 2) |
| Build | `./gradlew :app:assembleDebug` |
| Output | `app/build/outputs/apk/debug/app-debug.apk` |
| Signing | none configured — the debug APK is debug-signed |
| Script engine | BeanShell (`org.beanshell:bsh:2.0b5`) |
| UI deps | AppCompat 1.7.0, Material 1.12.0, RecyclerView 1.3.2 |
| Tests | none in the repo |

## Source map

| Package | Responsibility |
|---|---|
| `com.example.screenshotmover` | Activities: `MainActivity` (scripts), `EditActivity` (editor), `LogsActivity`/`LogActivity`, `SettingsActivity`, `ProvidersActivity` (chat providers), `NavTabs` (bottom navigation) |
| `.automation` | `Automation` interface and `Automations` registry. `Automations.all()` is deliberately empty — installed scripts are the only automations |
| `.plugin` | Script storage, validation, BeanShell execution: `PluginManager`, `PluginContext`/`PluginContextImpl`, `ScriptAutomationHolder` (adapts a script to `Automation`) |
| `.core` | `Store` (prefs, per-automation alarm scheduling, run bookkeeping) and `MicrodroidReceiver` (generic adb broadcast API) |
| `.trigger` | Trigger/constraint types and engine, system-broadcast receiver, resident watcher service, notification listener, accessibility service, schedule/geofence/sunrise/HTTP-poll runners, LAN webhook server |
| `.chat` | LLM clients, provider catalogue, agent tools, chat persistence, encrypted key store |

## Script contract

A script is a BeanShell file with four required functions (Java-like syntax, untyped variables,
no imports or class definitions needed):

```java
id() { return "my_plugin"; }          // [a-z0-9_]{2,32}; "all" is reserved
name() { return "My plugin"; }
description() { return "What it does."; }
run(ctx) {
  ctx.log("hello");
  return "OK";                        // becomes last_<id> and the run log entry
}
```

An optional `on_event(ev, ctx)` handles trigger payloads; without it, the normal `run(ctx)` runs.
`ev` is a `Map<String, String>` with a `type` key plus payload keys (see Triggers below).

Validation happens in `PluginManager.inspect()` before anything is saved: the four functions must
exist, `id()` must match `[a-z0-9_]{2,32}`, `"all"` is rejected, and an id reserved by a built-in
automation is rejected. While editing, `id()` cannot change. `save()` writes the source and a
`meta.json`; a newly saved script starts disabled.

`ScriptAutomationHolder` interprets the current source on every run (scripts are small, so this
keeps edits live without a compile step). On failure the script gets a strike; **3 consecutive
failures auto-disable** it. Success clears the counter. Each script keeps the last 200 log lines
in prefs; the Logs tab merges up to 300 entries across scripts.

### Script storage

```
files/plugins/<id>/Plugin.java   // source
files/plugins/<id>/meta.json     // id, name, description, version timestamp
```

A script can be created in the phone UI (FAB **+** in the Scripts tab), imported as a `.java`
file through the system file picker, or pushed and imported over adb (see adb API). Removal
deletes the script directory, its schedule, log, failure counter and per-plugin state.

### `ctx` API

File operations (absolute device paths only):

| Method | Notes |
|---|---|
| `log(msg)` | Append to the script's run log |
| `list(dir)` | Names inside a directory, `null` if missing |
| `exists(path)`, `isFile(path)`, `isDirectory(path)`, `length(path)` | `length` is `-1` when missing |
| `mkdirs(dir)` | Create directories |
| `copy(src, dstDir)`, `move(src, dstDir)` | Return the destination path or `null`; **never overwrite** (`_1`, `_2` suffixes); `move` copies, verifies the size, then deletes the source |
| `delete(path)` | File or empty directory; refuses non-empty directories |
| `scan(path)` | Ask MediaStore to index a new file |

Device actions: `flashlight(on)`, `blink(times, onMs, offMs)`, `notify(title, text)`,
`vibrate(ms)`, `speak(text)`, `setVolume(0-100)`, `setBrightness(0-100)`, `dnd(on)`.

Apps, network and clipboard: `launchApp(pkg)`, `openUrl(url)`, `sendSms(number, text)`,
`call(number)`, `httpGet(url)`, `httpPost(url, body, contentType)`, `setClipboard(text)`,
`toast(msg)`.

UI automation (needs Accessibility access): `tap(x, y)`, `swipe(x1, y1, x2, y2, ms)`,
`typeText(text)`, `pressBack()`, `home()`, `scroll(forward)`, `clickText(text)`,
`findText(text)`, `currentApp()`.

Per-script state: `getData(key, def)` / `putData(key, value)`.

Actions that need user-granted access return `false` or an `ERROR: ...` string when unavailable.

### BeanShell limits

No `import`, no class definitions. Variables are untyped (`files = ctx.list(...)`); for-each works
(`for (f : files) { ... }`); no lambdas, streams or records. Keep scripts small.

## Scheduling

Each automation has its own repeating interval (minimum 15 minutes, default 360). Schedules are
inexact repeating `ELAPSED_REALTIME_WAKEUP` alarms that fire the `ACTION_RUN` broadcast for that
id; the first run is one minute after arming. PendingIntents are kept distinct per automation with
a `microdroid://alarm/<id>` data URI.

- Enabling a script schedules it; disabling cancels its alarm.
- The scheduler can be started/stopped in bulk (Settings, Scripts menu, or adb); the state is
  tracked with a `scheduler_on` pref and shown in Settings.
- `BOOT_COMPLETED` reschedules everything currently enabled and marks the scheduler on.
- New scripts are saved disabled — nothing runs until you enable it or hit Run.

## adb API

All broadcasts are handled by `com.example.screenshotmover/.core.MicrodroidReceiver` and must be
**explicit** (`-n`) on Android 8+. The Settings tab shows these commands in-app.

```
# run one script, or every enabled script
adb shell am broadcast -a com.microdroid.ACTION_RUN      -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
adb shell am broadcast -a com.microdroid.ACTION_RUN      -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id all

# enable / disable / interval (minutes)
adb shell am broadcast -a com.microdroid.ACTION_ENABLE   -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
adb shell am broadcast -a com.microdroid.ACTION_DISABLE  -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin
adb shell am broadcast -a com.microdroid.ACTION_SET_INTERVAL -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin --ei interval_min 360

# scheduler on/off, list current state
adb shell am broadcast -a com.microdroid.ACTION_START_SCHEDULER -n com.example.screenshotmover/.core.MicrodroidReceiver
adb shell am broadcast -a com.microdroid.ACTION_STOP_SCHEDULER  -n com.example.screenshotmover/.core.MicrodroidReceiver
adb shell am broadcast -a com.microdroid.ACTION_LIST            -n com.example.screenshotmover/.core.MicrodroidReceiver

# import / remove (import needs "Allow ADB imports" ON)
adb push my_plugin.java /storage/emulated/0/Download/my_plugin.java
adb shell am broadcast -a com.microdroid.ACTION_PLUGIN_IMPORT -n com.example.screenshotmover/.core.MicrodroidReceiver --es path /storage/emulated/0/Download/my_plugin.java
adb shell am broadcast -a com.microdroid.ACTION_PLUGIN_REMOVE -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin

# triggers / constraints (JSON; *_b64 takes base64 of the same JSON — easier when quoting is painful)
adb shell am broadcast -a com.microdroid.ACTION_SET_TRIGGERS    -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin --es triggers_b64 <base64>
adb shell am broadcast -a com.microdroid.ACTION_SET_CONSTRAINTS -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id my_plugin --es constraints_b64 <base64>

# inject an event by hand
adb shell am broadcast -a com.microdroid.ACTION_EVENT -n com.example.screenshotmover/.core.MicrodroidReceiver --es event_type screen --es event_json_b64 <base64>
```

Extras: `automation_id`, `interval_min`, `path`, `triggers`/`triggers_b64`,
`constraints`/`constraints_b64`, `event_type`, `event_json`/`event_json_b64`. Trigger and
constraint JSON is validated before it is stored; errors are written to the `last_triggers` pref
and toasted. `ACTION_LIST` writes its result to the `list` pref (also toasted). Import status goes
to `last_import`. A state-change broadcast (`com.microdroid.ACTION_STATE_CHANGED`, package-scoped)
refreshes the UI.

## Triggers

Triggers are stored per script as a JSON array (`triggers_<id>`); any of them firing runs the
script (through `on_event` when defined, otherwise `run`). All matching triggers are ORed.

| id | Fields | Notes |
|---|---|---|
| `time` | `at` (HH:mm), `days` | Exact when exact-alarm access is granted |
| `boot` | — | |
| `power` | `action` (any/connected/disconnected) | |
| `battery` | `level`, `direction` (below/above), `charging` (any/yes/no) | Resident watcher |
| `screen` | `action` (on/off) | Resident watcher |
| `headset` | `state` (plugged/unplugged) | |
| `network` | `transport` (any/wifi/mobile/ethernet) | Resident watcher |
| `wifi` | `action` (any/connected/disconnected), `ssid` | Resident watcher |
| `package` | `action` (installed/removed), `package` | |
| `media` | — | Media button |
| `broadcast` | `action` (blank = any) | Any matching broadcast intent |
| `notification` | `action` (posted/removed), `package`, `contains` | Notification access |
| `sms` | `from`, `contains` | SMS permission |
| `call` | `state` (any/incoming/outgoing/missed) | Phone state; incoming/ended are reported, the rest are best-effort |
| `app` | `action` (opened/closed), `package` | Usage access or Accessibility |
| `location` | `action` (enter/exit), `lat`, `lng`, `radius_m` | Location; proximity alerts |
| `sensor` | `gesture` (shake/flip/proximity) | Resident watcher |
| `unlock` | — | |
| `calendar` | `when` (starts/ends), `keyword` | Calendar access; resident watcher |
| `webhook` | `secret` (blank = none) | LAN server on port 8765 |
| `http_poll` | `url`, `interval_min`, `contains` | Fires on match, or any change when `contains` is blank |
| `sun` | `event` (sunrise/sunset), `offset_min` | Computed locally |

Event payload keys (`ev` in `on_event`, or `event_json` over adb): `battery(level, charging)`,
`screen(action)`, `network(transport)`, `wifi(action, ssid)`, `power(action)`, `headset(state)`,
`package(action, package)`, `broadcast(action)`, `notification(action, package, title, text)`,
`sms(from, body)`, `call(state)`, `app(action, package)`, `location(action, lat, lng)`,
`sensor(gesture)`, `calendar(when, title)`, `time(at, days)`, `webhook(secret + query keys)`,
`http_poll(url, body)`, `sun(event)`.

Runtime pieces behind the trigger types:

- `TriggerReceiver` — system broadcasts, boot, time/sun alarms, webhook and geofence intents.
- `TriggerService` — resident foreground watcher for screen, battery, network, Wi-Fi, sensors,
  app open/close (fallback) and calendar, started only when an enabled script needs it.
- `NotificationTriggerService` — notification listener (user enables Notification access).
- `MicrodroidAccessibilityService` — precise app-open/close plus all UI automation actions.
- `ScheduleTrigger` — exact alarms per time trigger; `SunTrigger` — local sunrise/sunset;
  `GeofenceTrigger` — location enter/exit; `HttpPollTrigger` — periodic HTTP checks;
  `WebhookServer` — LAN HTTP listener on `:8765`; every request dispatches a webhook event
  carrying its query parameters (`secret` included), `path` and `body`.
- `TriggerEngine` dispatches matching events to enabled scripts off the main thread.

## Constraints

Constraints are a per-script JSON array (`constraints_<id>`); **all** must match for a fired
trigger to run the script.

| id | Fields |
|---|---|
| `time_window` | `from`, `to` (HH:mm), `days` |
| `battery_min` | `min` (%) |
| `charging` | `state` (yes/no) |
| `screen` | `state` (on/off) |
| `wifi_ssid` | `ssid` |
| `network` | `transport` (any/wifi/mobile) |
| `foreground_app` | `package` |
| `headphones` | `state` (yes/no) |
| `dnd` | `state` (on/off) |
| `location` | `lat`, `lng`, `radius_m` |

## AI chat

Configure providers in Settings → Model providers. Built-ins:

| Provider | Wire format | Default base URL | Default model |
|---|---|---|---|
| OpenAI-compatible | OpenAI chat completions | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | Messages API | `https://api.anthropic.com/v1` | `claude-sonnet-4-5` |
| Gemini | `generateContent` | `https://generativelanguage.googleapis.com/v1beta` | `gemini-2.5-flash` |
| OpenCode Go | OpenAI-compatible | `https://opencode.ai/zen/go/v1` | `deepseek-v4.1-flash` |

Custom providers can be added with a name, a wire format, a base URL and a model. Base URL and
model can be overridden per provider; the provider dialog has a connection test.

Clients (`OpenAiCompatibleClient`, `AnthropicClient`, `GeminiClient`, all on `BaseClient`) stream
SSE responses and support tool calling. The agent loop runs at most 8 model round-trips per user
message and can be stopped mid-flight. Conversation history is kept in `files/chat/history.json`;
starting a new chat clears it.

Agent tools: `list_scripts`, `read_script`, `save_script`, `delete_script`, `run_script`,
`set_schedule`, `set_triggers`, `set_constraints`.

Confirmation policy:

- Read-only tools never ask.
- Ask mode (default): `save_script` shows a create/update preview with a diff; `run_script`,
  `delete_script` and `set_schedule` ask; trigger/constraint changes ask.
- Bypass mode: saves and runs apply immediately; deletes, schedules and trigger/constraint
  changes still ask.

`save_script` is validated by BeanShell before writing, so compile errors are returned to the
model for repair. Models without tool calling get a fallback: if a fenced block in the reply
contains `id()` and `run(`, the app offers a **Save script** button.

The system prompt includes the script contract, the `ctx` API, trigger/constraint schemas,
BeanShell rules, the installed script list, storage paths, and prompt-injection rules (file names
and script contents are treated as untrusted data). One SD-card volume path is currently baked
into that prompt; other cards need their path supplied by the user.

### API keys

Keys are AES/GCM-encrypted with a 256-bit key held in the Android Keystore; prefs store
`base64(iv):base64(ciphertext)` only (`ApiKeyStore`). Keys are per provider, sent only to that
provider's configured base URL, and `microdroid_llm.xml` is excluded from backup (the Keystore is
never backed up, so a restored ciphertext simply decrypts to "missing").

## Permissions

| Access | Used for |
|---|---|
| All files (`MANAGE_EXTERNAL_STORAGE`) | File operations across internal/SD storage; `READ_EXTERNAL_STORAGE` fallback on API 30–32 |
| Notifications (`POST_NOTIFICATIONS`) | `notify` action and the trigger service notice |
| Notification access | Notification triggers |
| Accessibility | `on_event` app opened/closed and tap/swipe/type UI automation |
| Display over other apps | Launching apps / opening URLs from background triggers |
| Usage access | App opened/closed fallback when Accessibility is off |
| Location, background location | Location triggers and the near-location constraint |
| SMS | SMS received trigger, `sendSms` action |
| Phone | Call trigger, `call` action |
| Calendar | Calendar event triggers |
| Write settings | `setBrightness` |
| DND access | `dnd` action |
| Exact alarms | Time triggers firing at the exact minute |
| Battery optimization exemption | Keeping triggers and schedules reliable |
| `INTERNET` | Chat LLM calls and script HTTP |
| `FLASHLIGHT` | `flashlight`/`blink` (normal permission) |

## Preferences and data

`shared_prefs/microdroid.xml`:

- Per script: `enabled_<id>`, `interval_<id>`, `last_<id>`, `triggers_<id>`, `constraints_<id>`,
  `log_<id>`, `fail_<id>`.
- Global: `scheduler_on`, `allow_adb_import`, `list` (last `ACTION_LIST` result), `last_import`,
  `last_triggers`.

`shared_prefs/microdroid_llm.xml`: active `provider`, chat `mode`, per-provider `base_<id>` /
`model_<id>` / encrypted `key_<id>`, `custom_providers`, `session_id`, `chat_reset_at`.

`files/`: `plugins/<id>/…` (scripts) and `chat/history.json` (transcript).

## Security model

- BeanShell scripts are **not sandboxed**. They run in-process with the app's permissions and can
  reach the full Java API, including the network and the app's own stored data (which includes
  the encrypted LLM key). Only import or generate scripts you trust.
- The adb import gate (`allow_adb_import`, on by default) can be turned off to block script
  pushes; it does not block the other adb actions.
- Permissions are requested individually from the permission center, only as needed.
- The receiver is exported (that is how adb control works); broadcasts must be explicit.

## Known limits

- Background activity launches (`launchApp`/`openUrl` inside triggers) can still be blocked by
  Android; the overlay permission improves the odds.
- The webhook listens on the LAN only, port 8765; there is no TLS.
- Location triggers use proximity alerts, not continuous geofencing.
- After a reboot the OS may defer `BOOT_COMPLETED` for cached apps until the app is opened;
  schedules are restored when the broadcast arrives.
- No release signing or minification is configured.
