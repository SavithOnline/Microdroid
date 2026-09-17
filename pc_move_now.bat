@echo off
REM Microdroid PC control - run an automation on the connected phone.
REM Usage: pc_move_now.bat [automation_id]   (default: all enabled)
setlocal
set DEVICE=R9AMA0LCXEJ
set ID=%1
if "%ID%"=="" set ID=all
adb -s %DEVICE% shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.core.MicrodroidReceiver --es automation_id %ID%
pause
