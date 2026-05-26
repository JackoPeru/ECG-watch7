param(
    [string] $AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string] $DeviceId = "",
    [string] $PackageName = "com.galaxywatch7.health.mobile"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path -LiteralPath $AdbPath)) {
    throw "adb not found at $AdbPath"
}

$deviceArgs = @()
if (![string]::IsNullOrWhiteSpace($DeviceId)) {
    $deviceArgs = @("-s", $DeviceId)
}

& $AdbPath @deviceArgs "devices" "-l"

$permissions = @(
    "android.permission.BODY_SENSORS",
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.health.READ_HEART_RATE",
    "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
)

foreach ($permission in $permissions) {
    Write-Host "Grant $permission"
    & $AdbPath @deviceArgs "shell" "pm" "grant" $PackageName $permission
}

Write-Host ""
Write-Host "Set AppOps where available"
$appOps = @(
    "BODY_SENSORS",
    "ACTIVITY_RECOGNITION"
)

foreach ($op in $appOps) {
    Write-Host "AppOps allow $op"
    & $AdbPath @deviceArgs "shell" "cmd" "appops" "set" $PackageName $op "allow"
}

Write-Host ""
Write-Host "Permission status:"
& $AdbPath @deviceArgs "shell" "dumpsys" "package" $PackageName |
    Select-String -Pattern "BODY_SENSORS|ACTIVITY_RECOGNITION|READ_HEART_RATE|READ_ADDITIONAL_HEALTH_DATA" -Context 0,1

Write-Host ""
Write-Host "AppOps status:"
& $AdbPath @deviceArgs "shell" "cmd" "appops" "get" $PackageName |
    Select-String -Pattern "BODY_SENSORS|ACTIVITY_RECOGNITION|HEART|SENSOR"

Write-Host ""
Write-Host "Done. Reopen watch app, press Policy info, then ECG 30s."
