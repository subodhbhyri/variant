# P6-T9, the container half: the renderer image runs hardened and cannot reach any outside host.
# (The identity and process-recovery halves are the renderer module's corpus tests.)
#
# Builds the renderer image, runs it the way production does (non-root, read-only root, tmpfs
# scratch, all capabilities dropped, on a network with no route out) and checks:
#   - it serves a render for a client on the same internal network
#   - it runs as uid 10001 and cannot write outside /scratch
#   - it cannot open a connection to an outside address or resolve an outside name
#   - no request file is left in /scratch afterwards
param([switch]$KeepRunning)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$net = 'tailor-renderer-net'
$name = 'tailor-renderer-check'
$failures = New-Object System.Collections.Generic.List[string]

function Check([string]$what, [bool]$ok) {
    if ($ok) { Write-Host "ok    $what" } else { Write-Host "FAIL  $what"; $failures.Add($what) }
}

docker build -q -f docker/Dockerfile --target renderer -t tailor-renderer . | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'renderer image build failed' }
docker build -q -f docker/Dockerfile -t resume-tailor . | Out-Null

docker rm -f $name 2>&1 | Out-Null
docker network rm $net 2>&1 | Out-Null
# --internal: the network has no gateway, so nothing on it can reach the outside.
docker network create --internal $net | Out-Null

try {
    docker run -d --name $name --network $net `
        --read-only --user 10001 --cap-drop ALL --security-opt no-new-privileges `
        --tmpfs /scratch:rw,size=512m,uid=10001,gid=10001 `
        --tmpfs /tmp:rw,size=64m,uid=10001,gid=10001 `
        --memory 2g --pids-limit 512 `
        tailor-renderer | Out-Null

    $healthy = $false
    for ($i = 0; $i -lt 90 -and -not $healthy; $i++) {
        $code = docker run --rm --network $net resume-tailor curl -s -o /dev/null -w '%{http_code}' "http://${name}:8090/health" 2>$null
        if ($code -eq '200') { $healthy = $true } else { Start-Sleep -Seconds 1 }
    }
    Check 'health check (renders a 1-page fixture) answers 200' $healthy

    $magic = docker run --rm --network $net resume-tailor sh -c "curl -sf -X POST --data-binary @/app/fixtures/phase2/ok_synthetic.docx http://${name}:8090/render | head -c 5"
    Check 'a render over the internal network returns a PDF' ($magic -eq '%PDF-')

    $uid = (docker exec $name id -u).Trim()
    Check 'runs as uid 10001 (not root)' ($uid -eq '10001')

    docker exec $name sh -c 'touch /app/should-not-exist' 2>&1 | Out-Null
    Check 'the root filesystem is read-only' ($LASTEXITCODE -ne 0)

    docker exec $name sh -c 'touch /scratch/ok && rm /scratch/ok' 2>&1 | Out-Null
    Check '/scratch (tmpfs) is writable' ($LASTEXITCODE -eq 0)

    # The probe command has no nested double quotes: Windows PowerShell 5.1 mangles them.
    $probe = "timeout 5 bash -c 'exec 3<>/dev/tcp/1.1.1.1/443'"
    # Control: the same probe from the same image on an ordinary network must succeed, or the
    # probe proves nothing.
    docker run --rm --entrypoint bash tailor-renderer -c $probe 2>&1 | Out-Null
    Check 'control: the probe connects from an ordinary network' ($LASTEXITCODE -eq 0)

    docker exec $name bash -c $probe 2>&1 | Out-Null
    Check 'cannot open a TCP connection to an outside IP (1.1.1.1:443)' ($LASTEXITCODE -ne 0)

    docker exec $name bash -c 'timeout 5 getent hosts example.com' 2>&1 | Out-Null
    Check 'cannot resolve an outside name (example.com)' ($LASTEXITCODE -ne 0)

    $left = (docker exec $name sh -c 'ls /scratch/requests | wc -l').Trim()
    Check 'no request file is left in /scratch/requests' ($left -eq '0')
}
finally {
    if (-not $KeepRunning) {
        docker rm -f $name 2>&1 | Out-Null
        docker network rm $net 2>&1 | Out-Null
    }
}

if ($failures.Count -gt 0) { Write-Host "`n$($failures.Count) check(s) failed"; exit 1 }
Write-Host "`nall checks passed"
