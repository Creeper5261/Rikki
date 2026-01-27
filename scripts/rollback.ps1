param(
  [string]$ComposeFile = "docker-compose.yml",
  [string]$Image = "code-agent:rollback"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ComposeFile)) {
  throw "compose file not found: $ComposeFile"
}

$env:CODE_AGENT_IMAGE = $Image
docker compose -f $ComposeFile up -d --no-build
