$ErrorActionPreference = "Stop"

$target = "wear\libs\samsung-health-sensor-api.aar"
if (Test-Path $target) {
    $file = Get-Item $target
    Write-Host "OK: $target exists ($($file.Length) bytes)."
    exit 0
}

Write-Host "Missing: $target"
Write-Host ""
Write-Host "Official SDK page:"
Write-Host "https://developer.samsung.com/health/sensor/overview.html"
Write-Host ""
Write-Host "Samsung download endpoint found:"
Write-Host "https://developer.samsung.com/SHealth/file/13ab7f19-be94-4b52-917f-34dd688cf857"
Write-Host ""
Write-Host "This endpoint redirects to Samsung login when unauthenticated."
Write-Host "Log in with your Samsung Developer account, download Samsung Health Sensor SDK v1.4.1, extract samsung-health-sensor-api.aar, and put it at:"
Write-Host $target
exit 1
