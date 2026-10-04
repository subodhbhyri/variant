<#
.SYNOPSIS
    P5-T8 operator tooling: runs the full onboard -> intake-template -> intake-fill ->
    generate --live -> match-batch pipeline, in Docker, against the operator's own private
    resume material.

.DESCRIPTION
    Everything this script writes lands under <PrivateDir>\out; nothing from <PrivateDir> is
    ever baked into a Docker image or written into the repo — every step runs against the
    already-built "resume-tailor" image with <PrivateDir> and the MiniLM model directory passed
    in only as read/write bind mounts, never as build context. Stops at the first failing step.

.PARAMETER PrivateDir
    The operator's own folder: exactly one .docx directly inside it, project_datasets.json,
    and a jds\ subfolder of job-description .txt files.

.PARAMETER ModelsDir
    Parent folder of the all-MiniLM-L6-v2 model directory (mounted read-only). Defaults to the
    path this project's own live MiniLM testing has used throughout.

.EXAMPLE
    .\scripts\run-private.ps1 -PrivateDir C:\Users\ashok\my-resume-material
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$PrivateDir,

    [string]$ModelsDir = "C:\Users\ashok\javaworld\models"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $RepoRoot ".env"
$OutDir = Join-Path $PrivateDir "out"

function Invoke-DockerStep {
    param(
        [string]$Name,
        [string[]]$DockerArgs,
        [string]$CaptureVar
    )
    Write-Host ""
    Write-Host "=== $Name ===" -ForegroundColor Cyan
    if ($CaptureVar) {
        & docker @DockerArgs 2>&1 | Tee-Object -Variable capturedLines | ForEach-Object { Write-Host $_ }
        Set-Variable -Name $CaptureVar -Value ($capturedLines -join "`n") -Scope Script
    }
    else {
        & docker @DockerArgs
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Error "$Name failed (docker exit code $LASTEXITCODE) -- stopping."
        exit 1
    }
}

if (-not (Test-Path $PrivateDir)) {
    throw "PrivateDir not found: $PrivateDir"
}
if (-not (Test-Path $EnvFile)) {
    throw "$EnvFile not found -- needed for the --live generate step (ANTHROPIC_API_KEY)."
}
if (-not (Test-Path $ModelsDir)) {
    throw "ModelsDir not found: $ModelsDir"
}

$docxFiles = Get-ChildItem -Path $PrivateDir -Filter *.docx -File
if ($docxFiles.Count -eq 0) {
    throw "no .docx file found directly in $PrivateDir"
}
if ($docxFiles.Count -gt 1) {
    throw "multiple .docx files found directly in $PrivateDir ($($docxFiles.Name -join ', ')) -- expected exactly one."
}
$docxName = $docxFiles[0].Name

$datasetsPath = Join-Path $PrivateDir "project_datasets.json"
if (-not (Test-Path $datasetsPath)) {
    throw "project_datasets.json not found in $PrivateDir"
}

$jdsDir = Join-Path $PrivateDir "jds"
if (-not (Test-Path $jdsDir)) {
    throw "jds\ folder not found in $PrivateDir"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "Private dir:  $PrivateDir"
Write-Host "Resume:       $docxName"
Write-Host "Models dir:   $ModelsDir"
Write-Host "Output dir:   $OutDir"

# --- build the image fresh, so every step below runs the current code -----------------------
Invoke-DockerStep -Name "docker build" -DockerArgs @("build", "-f", "docker/Dockerfile", "-t", "resume-tailor", $RepoRoot)

# --- 1. onboard -------------------------------------------------------------------------------
Invoke-DockerStep -Name "onboard" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "gradle", "--no-daemon", ":cli:run",
    "--args=onboard /private/$docxName /private/out/onboard"
)

# --- 2. intake-template -------------------------------------------------------------------------
Invoke-DockerStep -Name "intake-template" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "gradle", "--no-daemon", ":cli:run",
    "--args=intake-template /private/out/onboard/normalized.docx /private/out/intake-template.json"
)

# --- 3. intake-fill -----------------------------------------------------------------------------
Invoke-DockerStep -Name "intake-fill" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "gradle", "--no-daemon", ":cli:run",
    "--args=intake-fill /private/out/intake-template.json /private/project_datasets.json /private/out/intake-filled.json"
)

# --- 4. generate --live (needs ANTHROPIC_API_KEY from the repo's own .env) ----------------------
Invoke-DockerStep -Name "generate --live" -CaptureVar "generateOutput" -DockerArgs @(
    "run", "--rm",
    "--env-file", $EnvFile,
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "gradle", "--no-daemon", ":cli:run",
    "--args=generate /private/out/onboard/normalized.docx /private/out/intake-filled.json /private/out/generate --live"
)

# library.json needs the unwrapped {"projects": [...]} shape; generate's own output nests it one
# level deeper ({"jobs": ..., "projects": {"projects": [...]}}). Pure host-side JSON surgery --
# never touches a container, and <PrivateDir> stays outside the repo/image either way.
$generateJson = Get-Content (Join-Path $OutDir "generate\variants.json") -Raw | ConvertFrom-Json
$generateJson.projects | ConvertTo-Json -Depth 20 | Set-Content -Path (Join-Path $OutDir "library.json") -Encoding utf8

# --- 5. match-batch (real MiniLM model, read-only) -----------------------------------------------
Invoke-DockerStep -Name "match-batch" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "-v", "${ModelsDir}:/models:ro",
    "-e", "VARIANT_MODEL_DIR=/models/all-MiniLM-L6-v2",
    "resume-tailor", "gradle", "--no-daemon", ":cli:run",
    "--args=match-batch /private/out/onboard/normalized.docx /private/out/generate/variants.json /private/out/library.json /private/jds /private/out/match-batch --embedder minilm"
)

Write-Host ""
Write-Host "=== done ===" -ForegroundColor Green
Write-Host "All output under: $OutDir"

$costLine = ($generateOutput -split "`n") | Select-String -Pattern "cost_usd=([\d.]+)" | Select-Object -Last 1
if ($costLine -and $costLine.Matches.Count -gt 0) {
    Write-Host "Generation cost: `$$($costLine.Matches[0].Groups[1].Value)" -ForegroundColor Yellow
}
else {
    Write-Warning "Could not find a cost_usd= line in generate's own output."
}
