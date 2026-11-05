$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleProperties = Join-Path $env:USERPROFILE ".gradle\gradle.properties"
$requiredKeys = @(
    "jazzFirebaseApiKey",
    "jazzFirebaseAppId",
    "jazzFirebaseProjectId",
    "jazzFirebaseSenderId"
)

if (-not (Test-Path $gradleProperties)) {
    throw "Gradle properties file not found: $gradleProperties"
}

$content = Get-Content $gradleProperties
$missing = @()
foreach ($key in $requiredKeys) {
    $configured = $false
    foreach ($line in $content) {
        if ($line -match "^\s*$([regex]::Escape($key))\s*=\s*(.+)$" -and $Matches[1].Trim()) {
            $configured = $true
            break
        }
    }
    if (-not $configured) { $missing += $key }
}

if ($missing.Count -gt 0) {
    throw "Missing Firebase Gradle properties: $($missing -join ', '). Values are not displayed."
}

Push-Location $projectRoot
try {
    Write-Host "Building Jazz Recovery with locally configured Firebase values..." -ForegroundColor Cyan
    & ".\gradlew.bat" ":app:assembleDebug" "--no-daemon"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }

    $apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        throw "Gradle reported success but the expected APK was not found."
    }

    $artifactDir = Join-Path $projectRoot "artifacts"
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $destination = Join-Path $artifactDir "JazzRecovery-FCM-Reconnect-Debug.apk"
    Copy-Item -Force $apk $destination

    $hash = (Get-FileHash -Algorithm SHA256 $destination).Hash
    Write-Host ""
    Write-Host "BUILD SUCCESS" -ForegroundColor Green
    Write-Host "APK: $destination"
    Write-Host "SHA256: $hash"
    Write-Host "No Firebase values or device tokens were printed."
}
finally {
    Pop-Location
}
