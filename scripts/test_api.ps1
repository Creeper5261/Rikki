$baseUrl = "http://localhost:8080"
$sessionId = "test-session-$(Get-Date -UFormat %s)"

Write-Host "Testing /api/agent/chat/stream..." -ForegroundColor Cyan

$payload = @{
    message = "Hello, what can you do?"
} | ConvertTo-Json

$tempFile = New-TemporaryFile
$payload | Out-File -FilePath $tempFile -Encoding utf8

try {
    # Using curl.exe with a data file to avoid quoting issues
    curl.exe -N -X POST "$baseUrl/api/agent/chat/stream" `
         -H "Content-Type: application/json" `
         -d "@$($tempFile.FullName)" `
         --max-time 600
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "`n`nSUCCESS: API call finished." -ForegroundColor Green
    } else {
        Write-Host "`n`nFAILED: API call failed with exit code $LASTEXITCODE." -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "`n`nFAILED: Exception occurred: $_" -ForegroundColor Red
    exit 1
} finally {
    Remove-Item $tempFile -ErrorAction SilentlyContinue
}
