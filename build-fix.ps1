# Build fix: use when build fails with "Permission denied" or "Binary store (exist: true)"
# Stops Gradle daemons, cleans native build dir, then builds.
Set-Location $PSScriptRoot
Write-Host "Stopping Gradle daemons..."
& .\gradlew --stop 2>$null
if (Test-Path "app\.cxx") {
    Write-Host "Removing app\.cxx (native build cache)..."
    Remove-Item -Recurse -Force "app\.cxx" -ErrorAction SilentlyContinue
}
Write-Host "Running assembleDebug..."
& .\gradlew assembleDebug
