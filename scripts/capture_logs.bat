@echo off
REM Capture device logcat for Storyteller crash debugging.
REM Usage: Run this, then reproduce the crash on device, then press Enter.
set DEVICE=04f8cf65
echo Clearing logcat...
adb -s %DEVICE% logcat -c
echo Reproduce the crash on the device (e.g. Analyse Space story), then press Enter here.
pause
set LOGFILE=logcat_%date:~-4,4%%date:~-10,2%%date:~-7,2%_%time:~0,2%%time:~3,2%%time:~6,2%.txt
set LOGFILE=%LOGFILE: =0%
echo Dumping logcat to %LOGFILE%...
adb -s %DEVICE% logcat -d -t 3000 > "%LOGFILE%"
echo Done. Search for: AndroidRuntime, FATAL, Exception, BookDetailFragment, ChapterCharExtract, QwenStub, QwenModel
