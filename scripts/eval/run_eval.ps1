param(
    [string]$ServerUrl = "http://localhost:8080",
    [string]$WorkspaceRoot = "",
    [string]$WorkspaceName = "",
    [string]$CasesFile = "scripts/eval/eval_cases.json",
    [int]$TimeoutSec = 180
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $CasesFile)) {
    Write-Host "Cases file not found: $CasesFile"
    exit 2
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = (Resolve-Path ".").Path
}

try {
    $health = Invoke-RestMethod -Method Get -Uri "$ServerUrl/api/agent/health" -TimeoutSec 10
} catch {
    Write-Host "Health check failed: $ServerUrl/api/agent/health"
    Write-Host $_.Exception.Message
    exit 2
}

$cases = Get-Content -Raw -Path $CasesFile | ConvertFrom-Json
$results = @()
$pass = 0
$fail = 0
$manual = 0

foreach ($c in $cases) {
    if ($c.type -ne "auto") {
        $results += [PSCustomObject]@{ id = $c.id; status = "MANUAL"; detail = $c.expect.notes }
        $manual++
        continue
    }

    $body = @{
        goal = $c.goal
        workspaceRoot = $WorkspaceRoot
        workspaceName = $WorkspaceName
        history = @()
    } | ConvertTo-Json -Depth 6

    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$ServerUrl/api/agent/chat" -ContentType "application/json" -Body $body -TimeoutSec $TimeoutSec
    } catch {
        $results += [PSCustomObject]@{ id = $c.id; status = "FAIL"; detail = "request_failed" }
        $fail++
        continue
    }

    $meta = $resp.meta
    $ok = $true
    $details = @()

    if ($c.expect.meta_auto_skills) {
        $expected = @($c.expect.meta_auto_skills)
        $actual = @()
        if ($meta -and $meta.auto_skills) {
            $actual = @($meta.auto_skills)
        }
        foreach ($s in $expected) {
            if (-not ($actual -contains $s)) {
                $ok = $false
                $details += "missing auto_skills: $s"
            }
        }
    }

    if ($ok) {
        $results += [PSCustomObject]@{ id = $c.id; status = "PASS"; detail = "" }
        $pass++
    } else {
        $results += [PSCustomObject]@{ id = $c.id; status = "FAIL"; detail = ($details -join "; ") }
        $fail++
    }
}

Write-Host "Evaluation Results"
$results | Format-Table -AutoSize
Write-Host "PASS=$pass FAIL=$fail MANUAL=$manual"

if ($fail -gt 0) {
    exit 1
}
exit 0
