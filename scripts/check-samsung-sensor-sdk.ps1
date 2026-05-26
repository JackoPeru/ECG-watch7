$ErrorActionPreference = "Stop"

$target = "wear\libs\samsung-health-sensor-api.aar"
if (Test-Path $target) {
    $file = Get-Item $target
    if ($file.PSIsContainer) {
        Write-Host "Invalid: $target is a directory, expected AAR file."
        exit 1
    }
    if ($file.Length -lt 10000) {
        Write-Host "Invalid: $target is too small ($($file.Length) bytes), expected Samsung AAR."
        exit 1
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $target))
        $hasClasses = $zip.Entries | Where-Object { $_.FullName -eq "classes.jar" } | Select-Object -First 1
        $zip.Dispose()
        if (-not $hasClasses) {
            Write-Host "Invalid: $target does not contain classes.jar."
            exit 1
        }
    } catch {
        Write-Host "Invalid: $target is not a readable AAR/ZIP: $($_.Exception.Message)"
        exit 1
    }
    Write-Host "OK: $target exists ($($file.Length) bytes) and contains classes.jar."
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
