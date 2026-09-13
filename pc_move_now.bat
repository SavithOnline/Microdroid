@echo off
REM ScreenshotMover PC control - explicit broadcasts (required on Android 8+)
REM Usage: pc_move_now.bat / pc_schedule_on.bat / pc_schedule_off.bat
adb -s R9AMA0LCXEJ shell am broadcast -a com.example.screenshotmover.ACTION_MOVE_NOW -n com.example.screenshotmover/.MoveReceiver
pause
