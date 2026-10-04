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

# --- build the "build" stage fresh (dev + the compiled tailor.jar) -------------------------
# Every step below invokes that jar directly with each argument as its own array element,
# never through gradle's ":cli:run --args=<one joined string>" -- Gradle's --args splits that
# string on whitespace before handing it to picocli, which silently breaks any path (a resume
# filename, a folder name) that contains a space. Passing argv entries straight through, as an
# array, has no such splitting at any step of the chain (PowerShell's @() splat -> Docker's own
# array-form command -> the JVM's own argv) -- a path with spaces is simply one array element.
Invoke-DockerStep -Name "docker build" -DockerArgs @(
    "build", "-f", "docker/Dockerfile", "--target", "build", "-t", "resume-tailor", $RepoRoot
)
$Jar = "/app/cli/build/libs/tailor.jar"

# --- 1. onboard -------------------------------------------------------------------------------
Invoke-DockerStep -Name "onboard" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "java", "-jar", $Jar,
    "onboard", "/private/$docxName", "/private/out/onboard"
)

# --- 2. intake-template -------------------------------------------------------------------------
Invoke-DockerStep -Name "intake-template" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "java", "-jar", $Jar,
    "intake-template", "/private/out/onboard/normalized.docx", "/private/out/intake-template.json"
)

# --- 3. intake-fill -----------------------------------------------------------------------------
# --add-unmatched: a dataset with no matching position becomes an added project (project-new-N)
# instead of failing the run -- the operator's own project_datasets.json commonly has more
# projects than the resume has existing positions for.
Invoke-DockerStep -Name "intake-fill" -DockerArgs @(
    "run", "--rm",
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "java", "-jar", $Jar,
    "intake-fill", "/private/out/intake-template.json", "/private/project_datasets.json", "/private/out/intake-filled.json",
    "--add-unmatched"
)

# --- 4. generate --live (needs ANTHROPIC_API_KEY from the repo's own .env) ----------------------
Invoke-DockerStep -Name "generate --live" -CaptureVar "generateOutput" -DockerArgs @(
    "run", "--rm",
    "--env-file", $EnvFile,
    "-v", "${PrivateDir}:/private",
    "resume-tailor", "java", "-jar", $Jar,
    "generate", "/private/out/onboard/normalized.docx", "/private/out/intake-filled.json", "/private/out/generate", "--live"
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
    "resume-tailor", "java", "-jar", $Jar,
    "match-batch", "/private/out/onboard/normalized.docx", "/private/out/generate/variants.json",
    "/private/out/library.json", "/private/jds", "/private/out/match-batch", "--embedder", "minilm"
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
