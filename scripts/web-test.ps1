# Runs Gradle tasks in the dev image with a throwaway PostgreSQL 16 and MinIO (S3) next to it
# (PHASE6_SPEC.md 11). The web tests need real servers; they read TEST_DB_URL / TEST_S3_ENDPOINT and
# fail loudly if missing.
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
$minio = 'tailor-test-minio'

if (-not $NoBuild) {
    docker build -q -f docker/Dockerfile -t resume-tailor . | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'image build failed' }
}

docker network inspect $net 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { docker network create $net | Out-Null }
docker rm -f $pg 2>&1 | Out-Null
docker rm -f $minio 2>&1 | Out-Null

docker run -d --name $minio --network $net `
    -e MINIO_ROOT_USER=tailor -e MINIO_ROOT_PASSWORD=tailor-secret `
    bitnamilegacy/minio:latest | Out-Null

docker run -d --name $pg --network $net `
    -e POSTGRES_USER=tailor -e POSTGRES_PASSWORD=tailor -e POSTGRES_DB=tailor_test `
    postgres:16 | Out-Null
try {
    for ($i = 0; $i -lt 60; $i++) {
        docker exec $pg pg_isready -U tailor -d tailor_test 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }

    for ($i = 0; $i -lt 60; $i++) {
        $code = docker run --rm --network $net resume-tailor curl -s -o /dev/null -w '%{http_code}' "http://${minio}:9000/minio/health/live"
        if ($code -eq '200') { break }
        Start-Sleep -Seconds 1
    }

    $env_args = @('-e', "TEST_DB_URL=jdbc:postgresql://${pg}:5432/tailor_test",
        '-e', "TEST_S3_ENDPOINT=http://${minio}:9000")
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
    docker rm -f $minio 2>&1 | Out-Null
}
