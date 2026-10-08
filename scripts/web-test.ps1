# Runs Gradle tasks in the dev image with a throwaway PostgreSQL 16 next to it (PHASE6_SPEC.md 11).
# The web tests need a real server; they read TEST_DB_URL and fail loudly if it is missing.
#
#   scripts/web-test.ps1                       # :web:test
#   scripts/web-test.ps1 :web:test :renderer:test
#   scripts/web-test.ps1 -UpdateOpenApi        # regenerate web/openapi.json from the code
#   scripts/web-test.ps1 -NoBuild              # reuse the last resume-tailor image
param(
    [string[]]$Tasks = @(':web:test'),
    [switch]$UpdateOpenApi,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$net = 'tailor-test-net'
$pg = 'tailor-test-pg'

if (-not $NoBuild) {
    docker build -q -f docker/Dockerfile -t resume-tailor . | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'image build failed' }
}

docker network inspect $net 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { docker network create $net | Out-Null }
docker rm -f $pg 2>&1 | Out-Null

docker run -d --name $pg --network $net `
    -e POSTGRES_USER=tailor -e POSTGRES_PASSWORD=tailor -e POSTGRES_DB=tailor_test `
    postgres:16 | Out-Null
try {
    for ($i = 0; $i -lt 60; $i++) {
        docker exec $pg pg_isready -U tailor -d tailor_test 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }

    $env_args = @('-e', "TEST_DB_URL=jdbc:postgresql://${pg}:5432/tailor_test")
    $mounts = @('-v', 'tailor-gradle:/root/.gradle')
    if ($UpdateOpenApi) {
        $spec = Join-Path $root 'web/openapi.json'
        if (-not (Test-Path $spec)) { New-Item -ItemType File -Path $spec | Out-Null }
        $mounts += @('-v', "${spec}:/app/web/openapi.json")
        $env_args += @('-e', 'OPENAPI_UPDATE=1')
    }

    docker run --rm --network $net @env_args @mounts resume-tailor `
        gradle --no-daemon @Tasks
    exit $LASTEXITCODE
}
finally {
    docker rm -f $pg 2>&1 | Out-Null
}
