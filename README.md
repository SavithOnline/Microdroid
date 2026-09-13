# Microdroid (was ScreenshotMover)

Android automation toolbox. Screenshot mover is now one module among many.
Same package (`com.example.screenshotmover`), same debug signature — installs as upgrade,
All-files grant + 6h screenshots schedule preserved (migrated to `microdroid` prefs).

Launcher name is now **Microdroid**.

## Modules (registry: automation/Automations.java)
| id | name | does |
|----|------|------|
| screenshots | Screenshot mover | Internal DCIM/Screenshots (+Pictures/Screenshots) -> SD/DCIM/Screenshots, no-overwrite, verify-then-delete |
| storage_report | Storage report | counts + writes Download/microdroid_report.txt |

Add a new one in 3 steps: implement `Automation`, add to `Automations.all()`, rebuild.
UI, scheduler, adb API pick it up automatically.

## PC control (explicit -n required on Android 8+)
```
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id screenshots
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id all
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_ENABLE -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id storage_report
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_DISABLE -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id storage_report
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_SET_INTERVAL -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id screenshots --ei interval_min 360
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_LIST -n com.example.screenshotmover/.core.MicrodroidReceiver
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_START_SCHEDULER -n com.example.screenshotmover/.core.MicrodroidReceiver
adb -s R9AMA0LCXEJ shell am broadcast -a com.microdroid.ACTION_STOP_SCHEDULER -n com.example.screenshotmover/.core.MicrodroidReceiver
```
Legacy v1 still works:
```
adb -s R9AMA0LCXEJ shell am broadcast -a com.example.screenshotmover.ACTION_MOVE_NOW -n com.example.screenshotmover/.MoveReceiver
```

## Status
```
adb -s R9AMA0LCXEJ shell run-as com.example.screenshotmover cat /data/data/com.example.screenshotmover/shared_prefs/microdroid.xml
```

## Verified 2026-09-13, SM-A207F Android 11
- Generic RUN screenshots moved micro_test.png (internal 0, SD 107)
- Legacy MOVE_NOW moved legacy_test.png
- ENABLE+RUN storage_report wrote Download/microdroid_report.txt
- LIST shows both enabled, interval 360
