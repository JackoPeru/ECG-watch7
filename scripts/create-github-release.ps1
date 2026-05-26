param(
    [Parameter(Mandatory = $true)]
    [string] $Tag,

    [string] $Title = "",
    [string] $Notes = "",

    [switch] $AllowNoSensorSdk
)

$ErrorActionPreference = "Stop"

if ($Tag -notmatch '^v?\d+\.\d+\.\d+.*$') {
    throw "Tag must look like v0.1.1"
}

$normalized = $Tag.TrimStart("v", "V")
if ([string]::IsNullOrWhiteSpace($Title)) {
    $Title = "Watch7 Health $Tag"
}
if ([string]::IsNullOrWhiteSpace($Notes)) {
    $Notes = "Internal update release for Watch7 Health."
}

gh auth status | Out-Null

$sensorSdk = "wear\libs\samsung-health-sensor-api.aar"
if (!(Test-Path $sensorSdk) -and !$AllowNoSensorSdk) {
    throw "Missing $sensorSdk. Download Samsung Health Sensor SDK from Samsung Developer while logged in, extract samsung-health-sensor-api.aar, then rerun. Use -AllowNoSensorSdk only for UI-only releases."
}

.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :wear:assembleDebug --no-daemon

$assetDir = Join-Path "release-assets" $Tag
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

$mobileSource = "mobile\build\outputs\apk\debug\mobile-debug.apk"
$wearSource = "wear\build\outputs\apk\debug\wear-debug.apk"
$mobileAsset = Join-Path $assetDir "Watch7Health-mobile-$normalized.apk"
$wearAsset = Join-Path $assetDir "Watch7Health-wear-$normalized.apk"

Copy-Item -LiteralPath $mobileSource -Destination $mobileAsset -Force
Copy-Item -LiteralPath $wearSource -Destination $wearAsset -Force

gh release create $Tag $mobileAsset $wearAsset --repo JackoPeru/ECG-watch7 --title $Title --notes $Notes

Write-Host "Release created: https://github.com/JackoPeru/ECG-watch7/releases/tag/$Tag"
Write-Host "Assets:"
Write-Host " - $mobileAsset"
Write-Host " - $wearAsset"
