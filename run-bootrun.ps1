$ErrorActionPreference = "Stop"

$build = & .\gradlew.bat idea-plugin:build
if ($LASTEXITCODE -ne 0) {
    $build
    exit $LASTEXITCODE
}

$logDir = "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logPath = Join-Path $logDir "bootRun_$timestamp.log"

& .\gradlew.bat bootRun *>&1 | Tee-Object -FilePath $logPath
exit $LASTEXITCODE
