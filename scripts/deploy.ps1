param(
  [string]$ComposeFile = "docker-compose.yml",
  [string]$Image = "code-agent:latest",
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ComposeFile)) {
  throw "compose file not found: $ComposeFile"
}

if (-not $SkipBuild) {
  .\gradlew clean bootJar
}

$existing = docker images -q $Image 2>$null
if ($existing) {
  docker tag $Image code-agent:rollback
}

$env:CODE_AGENT_IMAGE = $Image
docker compose -f $ComposeFile up -d --build
