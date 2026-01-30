# PowerShell script to hibernate computer
# Usage: Run this script to hibernate immediately.
# Note: shutdown /t does not work with /h on Windows; use Task Scheduler for delayed hibernation.

Write-Host "Hibernating computer now..." -ForegroundColor Yellow

# /h = hibernate, /f = force close applications (optional)
# /t is not supported with /h on Windows
& shutdown.exe /h /f

Write-Host "Hibernation initiated." -ForegroundColor Green
Write-Host "To cancel, run: shutdown /a" -ForegroundColor Cyan
