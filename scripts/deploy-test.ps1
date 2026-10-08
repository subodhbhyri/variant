<#
.SYNOPSIS
  Tests the single-server deployment (PHASE6_SPEC.md section 10A) on this machine: builds and starts the production
  compose stack (deploy/docker-compose.prod.yml, with deploy/docker-compose.test.yml turning on sign-in links in the
  log and recorded generation), then checks
    P6-T19  exposure: only Caddy's two ports are published, Postgres/api/renderer are unreachable from outside, the
            renderer and the database have no route out, and the containers are hardened;
    the whole user journey through Caddy at /api (same-origin cookies and redirects, files streamed by the api);
    P6-T17  backup and restore: deploy/backup.sh, then the stack is destroyed and deploy/restore.sh rebuilds it from the
            encrypted archive; users, libraries, snapshots and files are identical, and the archive is unreadable
            without the private key.
  backup.sh and restore.sh run inside a small "server" container (scripts/harness) that talks to this machine's Docker,
  so they run exactly as they do on the real server. Port 22 (SSH) belongs to the real server and is not tested here.

.PARAMETER NoBuild
  Reuse the images from the last run.

.PARAMETER KeepRunning
  Leave the test stack up at the end (it is removed by default, with its volumes).
#>
param(
    [switch]$NoBuild,
    [switch]$KeepRunning,
    [int]$HttpPort = 18080,
    [int]$HttpsPort = 18443
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$project = 'variant-test'
$envFile = Join-Path $root 'deploy/.env.test'
$work = Join-Path ([IO.Path]::GetTempPath()) ("variant-deploy-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work, (Join-Path $work 'backups') | Out-Null
$failures = New-Object System.Collections.Generic.List[string]
$started = Get-Date

function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "ok    $what" } else { Write-Host "FAIL  $what"; $script:failures.Add($what) }
}
function Section([string]$title) { Write-Host "`n=== $title ===" -ForegroundColor Cyan }
function Compose {
    docker compose -p $project -f deploy/docker-compose.prod.yml -f deploy/docker-compose.test.yml `
        --env-file deploy/profiles/small.env --env-file deploy/.env.test @args
}
# Docker Desktop shows the Windows drive to the daemon at /run/desktop/mnt/host/<drive>/..., which is what bind mounts
# made from INSIDE the harness container must name.
function VmPath([string]$windowsPath) {
    $p = (Resolve-Path $windowsPath).Path
    $drive = $p.Substring(0, 1).ToLower()
    '/run/desktop/mnt/host/' + $drive + ($p.Substring(2) -replace '\\', '/')
}
function Harness([string[]]$command, [string[]]$extra = @()) {
    $vm = VmPath $root
    docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -v "${root}:/repo" -v "$($work -replace '\\','/')/backups:/backups" `
        -v "$($work -replace '\\','/'):/work" `
        -e VARIANT_PROJECT=$project -e VARIANT_ENV_FILE=/repo/deploy/.env.test -e VARIANT_BACKUP_DIR=/backups `
        -e VARIANT_STATE_DIR=/tmp/state -e "CADDYFILE_PATH=$vm/deploy/Caddyfile" -e "SITE_DIR=$vm/deploy/site" `
        -e "FIXTURES_PATH=$vm/fixtures/phase4" `
        @extra -w /repo variant-harness bash -c "git config --global --add safe.directory /repo; $($command -join ' ')"
}
function Fingerprint {
    $tables = 'users', 'resumes', 'section_roles', 'intake_sections', 'libraries', 'library_items', 'generation_runs',
        'postings', 'matches', 'snapshots', 'usage_ledger'
    $sql = ($tables | ForEach-Object { "select '$_' as t, count(*) as n, coalesce(md5(string_agg(x::text, '|' order by x::text)), '') as h from $_ x" }) -join ' union all '
    $db = (Compose exec -T postgres psql -U tailor -d tailor -Atc "$sql order by 1" | Out-String).Trim()
    $files = (docker run --rm -v "${project}_storage:/data:ro" postgres:16.4-alpine sh -c 'cd /data && find . -type f | sort | while read f; do sha256sum "$f"; done | sha256sum && find . -type f | wc -l' | Out-String).Trim()
    "$db`n$files"
}

try {
    # ------------------------------------------------------------------------------------------------------------
    Section 'set up'
    $random = { -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ }) }
    docker build -q -t variant-harness scripts/harness | Out-Null
    $keygen = (docker run --rm variant-harness age-keygen 2>&1 | Out-String)
    $public = [regex]::Match($keygen, 'age1[0-9a-z]+').Value
    $private = ($keygen -split "`n" | Where-Object { $_ -like 'AGE-SECRET-KEY-*' } | Select-Object -First 1).Trim()
    $other = (docker run --rm variant-harness age-keygen 2>&1 | Out-String)
    $otherPrivate = ($other -split "`n" | Where-Object { $_ -like 'AGE-SECRET-KEY-*' } | Select-Object -First 1).Trim()
    [IO.File]::WriteAllText((Join-Path $work 'key.txt'), $private + "`n")
    [IO.File]::WriteAllText((Join-Path $work 'wrong-key.txt'), $otherPrivate + "`n")
    $lines = @(
        'APP_HOST=localhost', "APP_ORIGIN=https://localhost:$HttpsPort", "HTTP_PORT=$HttpPort", "HTTPS_PORT=$HttpsPort",
        'PROFILE=small', "POSTGRES_PASSWORD=$(& $random)", "APP_STORAGE_LINK_SECRET=$(& $random)", "BACKUP_AGE_RECIPIENT=$public",
        'APP_MAIL_MODE=off')
    [IO.File]::WriteAllText($envFile, ($lines -join "`n") + "`n")
    Check ($public.StartsWith('age1')) 'an age key pair was made'

    if (-not $NoBuild) {
        Compose build
        if ($LASTEXITCODE -ne 0) { throw 'image build failed' }
    }
    # Ports that something else on this machine already uses cannot be judged: note them and leave them out.
    $busy = @()
    foreach ($port in 5432, 8080, 8090, 2019, 9000) {
        $c = New-Object Net.Sockets.TcpClient
        try { $c.Connect('127.0.0.1', $port); $busy += $port } catch { }
        $c.Close()
    }
    if ($busy.Count -gt 0) { Write-Host "note: ports in use by other programs on this machine are not judged: $($busy -join ', ')" }
    Compose up -d --wait --wait-timeout 420
    if ($LASTEXITCODE -ne 0) { Compose ps; Compose logs --tail 40; throw 'the stack did not become healthy' }
    Compose ps

    # ------------------------------------------------------------------------------------------------------------
    Section 'P6-T19  exposure'
    $rows = docker ps --filter "label=com.docker.compose.project=$project" --format '{{.Names}}|{{.Ports}}'
    $published = @()
    foreach ($r in $rows) {
        $svc, $ports = $r -split '\|', 2
        $svc = $svc -replace "^${project}-", '' -replace '-\d+$', ''
        foreach ($m in [regex]::Matches($ports, '(\d+)->(\d+)/tcp')) { $published += [pscustomobject]@{ Service = $svc; Host = $m.Groups[1].Value; Container = $m.Groups[2].Value } }
    }
    Check (($published | Where-Object { $_.Service -ne 'caddy' }).Count -eq 0) 'only caddy publishes ports'
    Check ((@($published | ForEach-Object { $_.Container } | Sort-Object -Unique) -join ',') -eq '443,80') "caddy publishes exactly 80 and 443 (found: $((@($published | ForEach-Object { $_.Container } | Sort-Object -Unique)) -join ','))"

    $probe = { param($target) docker run --rm --add-host=host.docker.internal:host-gateway postgres:16.4-alpine sh -c "nc -z -w 3 host.docker.internal $target" 2>&1 | Out-Null; $LASTEXITCODE -eq 0 }
    Check (& $probe $HttpsPort) 'from outside the stack, the HTTPS port answers'
    Check (& $probe $HttpPort) 'from outside the stack, the HTTP port answers'
    foreach ($port in (5432, 8080, 8090, 2019, 9000 | Where-Object { $busy -notcontains $_ })) {
        Check (-not (& $probe $port)) "from outside the stack, port $port does not answer (postgres 5432, api 8080, renderer 8090, caddy admin 2019)"
    }
    foreach ($port in (5432, 8080, 8090, 2019 | Where-Object { $busy -notcontains $_ })) {
        $c = New-Object Net.Sockets.TcpClient
        $open = $false
        try { $c.Connect('127.0.0.1', $port); $open = $true } catch { }
        $c.Close()
        Check (-not $open) "from this machine, 127.0.0.1:$port is closed"
    }

    $backend = "${project}_backend"
    Check ((docker network inspect $backend --format '{{.Internal}}') -eq 'true') 'the backend network is internal (no route out)'
    foreach ($svc in 'renderer', 'postgres') {
        $id = (Compose ps -q $svc | Out-String).Trim()
        $nets = (docker inspect $id --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}').Trim()
        Check ($nets -eq $backend) "$svc is on the backend network only ($nets)"
    }
    $egress = { param($cmd) docker run --rm --network $backend postgres:16.4-alpine sh -c $cmd 2>&1 | Out-Null; $LASTEXITCODE -eq 0 }
    Check (& $egress 'nc -z -w 3 renderer 8090') 'control: from the backend network the renderer answers (the worker can reach it)'
    Check (-not (& $egress 'nc -z -w 3 1.1.1.1 443')) 'the renderer network cannot open a connection to an outside address (1.1.1.1:443)'
    Check (-not (& $egress 'nslookup example.com')) 'the renderer network cannot resolve an outside name'
    Check (-not (& $egress 'nc -z -w 3 host.docker.internal 22')) 'the renderer network cannot reach the host'

    foreach ($svc in 'renderer', 'api', 'worker', 'caddy') {
        $id = (Compose ps -q $svc | Out-String).Trim()
        $cfg = docker inspect $id --format '{{.HostConfig.ReadonlyRootfs}}|{{range .HostConfig.CapDrop}}{{.}}{{end}}|{{range .HostConfig.SecurityOpt}}{{.}}{{end}}|{{.Config.User}}|{{.State.Health.Status}}'
        $ro, $drop, $sec, $user, $health = $cfg -split '\|'
        Check ($drop -eq 'ALL') "$svc drops all capabilities"
        Check ($sec -match 'no-new-privileges') "$svc has no-new-privileges"
        if ($svc -ne 'caddy') { Check ($ro -eq 'true') "$svc has a read-only root filesystem"; Check (($user -eq '10001') -or ($user -eq 'tailor')) "$svc runs as a non-root user ($user; uid 10001)" }
        Check ($health -eq 'healthy') "$svc is healthy ($health)"
    }

    # ------------------------------------------------------------------------------------------------------------
    Section 'the site through Caddy'
    $base = "https://localhost:$HttpsPort"
    $root1 = (curl.exe -sk -o NUL -w '%{http_code}' "$base/" | Out-String).Trim()
    Check ($root1 -eq '200') "GET / serves the single-page app ($root1)"
    $spa = (curl.exe -sk "$base/some/client/route" | Out-String)
    Check ($spa -match 'Variant') 'an unknown path falls back to the app shell'
    $cfg = (curl.exe -sk "$base/api/config" | Out-String)
    Check ($cfg -match '"signin"') "GET /api/config answers through the /api prefix ($cfg)"
    $hdr = (curl.exe -sk -D - -o NUL "$base/api/auth/csrf" | Out-String)
    Check ($hdr -match '(?i)set-cookie: XSRF-TOKEN=[^;]+;[^\n]*Secure') 'the CSRF cookie is Secure, through Caddy'
    Check ($hdr -match '(?i)SameSite=Lax') 'the CSRF cookie is SameSite=Lax'
    Check ($hdr -match '(?i)strict-transport-security') 'HSTS is sent'
    $plain = (curl.exe -s -o NUL -w '%{http_code} %{redirect_url}' "http://localhost:$HttpPort/api/config" | Out-String)
    Check ($plain -match '^30[1-8] https://') "plain HTTP is redirected to HTTPS ($plain)"

    # ------------------------------------------------------------------------------------------------------------
    Section 'the whole journey through Caddy (sign-in confirmation page, uploads, files streamed by the api)'
    $composeArgs = "-p $project -f deploy/docker-compose.prod.yml -f deploy/docker-compose.test.yml --env-file deploy/profiles/small.env --env-file deploy/.env.test"
    $env:SMOKE_COMPOSE_ARGS = $composeArgs
    & powershell -NoProfile -File scripts/smoke-test.ps1 -BaseUrl "$base/api" -Insecure -KeepAccount
    Check ($LASTEXITCODE -eq 0) 'smoke-test.ps1 passed against the production stack'

    # ------------------------------------------------------------------------------------------------------------
    Section 'P6-T17  backup and restore'
    $before = Fingerprint
    Write-Host $before
    Check ($before -match 'users\|[1-9]') 'there is data to back up (users)'
    Check ($before -match 'snapshots\|[1-9]') 'there is data to back up (snapshots)'

    Harness @('bash deploy/backup.sh')
    Check ($LASTEXITCODE -eq 0) 'backup.sh ran'
    $backup = Get-ChildItem (Join-Path $work 'backups') -Filter 'variant-backup-*.tar.age' | Select-Object -First 1
    Check ($null -ne $backup) 'an encrypted backup file was written'
    $bytes = [IO.File]::ReadAllBytes($backup.FullName)
    Check ([Text.Encoding]::ASCII.GetString($bytes, 0, 21) -eq 'age-encryption.org/v1') 'the backup is an age file'
    $text = [Text.Encoding]::GetEncoding(28591).GetString($bytes)
    Check (($text -notmatch 'PGDMP') -and ($text -notmatch '%PDF') -and ($text -notmatch 'smoke\+') -and ($text -notmatch 'ustar')) 'the archive contains no readable database, PDF, email or tar data'
    Harness @("age -d -i /work/wrong-key.txt /backups/$($backup.Name) > /dev/null")
    Check ($LASTEXITCODE -ne 0) 'the archive cannot be decrypted with another key'
    Harness @("age -d /backups/$($backup.Name) > /dev/null < /dev/null")
    Check ($LASTEXITCODE -ne 0) 'the archive cannot be read without a key'

    Write-Host 'destroying the stack and its volumes ...'
    Compose down -v
    Check ((docker volume ls -q --filter "name=${project}_" | Out-String).Trim() -eq '') 'the stack and its volumes are gone'

    Harness @("bash deploy/restore.sh /backups/$($backup.Name) /work/key.txt")
    Check ($LASTEXITCODE -eq 0) 'restore.sh ran into the empty stack'
    $after = Fingerprint
    Check ($before -eq $after) 'users, libraries, postings, matches, snapshots, ledger rows and every file are identical after the restore'
    if ($before -ne $after) { Write-Host "BEFORE:`n$before`nAFTER:`n$after" }
    $health = (curl.exe -sk "$base/api/healthz" | Out-String)
    Check ($health -match '"status":"ok"') 'the restored stack serves requests'

    # A second restore refuses to overwrite a stack that has users unless told to.
    Harness @("bash deploy/restore.sh /backups/$($backup.Name) /work/key.txt")
    Check ($LASTEXITCODE -ne 0) 'restore.sh refuses to overwrite a stack that has users without --force'
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)"
    $failures.Add($_.Exception.Message)
}
finally {
    if (-not $KeepRunning) {
        Compose down -v 2>&1 | Out-Null
    }
    Remove-Item $envFile -ErrorAction SilentlyContinue
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ("`n{0} problem(s), {1:N0} s" -f $failures.Count, ((Get-Date) - $started).TotalSeconds)
if ($failures.Count -gt 0) { $failures | ForEach-Object { Write-Host "  - $_" }; exit 1 }
Write-Host 'DEPLOY TEST PASSED'
