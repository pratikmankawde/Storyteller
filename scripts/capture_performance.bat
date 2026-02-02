@echo off
REM Capture performance timings from device logcat (PROFILING.md).
REM Usage: Run this, then on device do the slow action (e.g. Analyse Chapters, open chapter), then press Enter.
set DEVICE=
if "%DEVICE%"=="" for /f "tokens=1" %%a in ('adb devices ^| findstr "device$"') do set DEVICE=%%a
if "%DEVICE%"=="" set DEVICE=04f8cf65
echo Using device: %DEVICE%
echo Clearing logcat...
adb -s %DEVICE% logcat -c
echo.
echo On the device: do the action to profile (e.g. Analyse Chapters, open a chapter, open Insights).
echo When done, press Enter here to capture the last 2000 lines filtered by "took".
pause
set LOGFILE=performance_%date:~-4,4%%date:~-10,2%%date:~-7,2%_%time:~0,2%%time:~3,2%%time:~6,2%.txt
set LOGFILE=%LOGFILE: =0%
echo Dumping performance logs to %LOGFILE%...
adb -s %DEVICE% logcat -d -t 2000 | findstr "took" > "%LOGFILE%"
echo Done. Open %LOGFILE% or run: findstr "QwenModel ChapterCharExtract BookDetailFragment" "%LOGFILE%"
