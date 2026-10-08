<#
.SYNOPSIS
  End-to-end smoke test of the whole user journey over HTTP (PHASE6_SPEC.md section 1): sign in, upload,
  onboarding preview, intake, generation, a posting, resume #1 and its PDF, an alternative, an edit,
  a repeated posting (cache hit), sign out and account deletion. Works against the local docker compose
  stack and against a deployed environment.

.PARAMETER BaseUrl
  The api's origin, e.g. http://localhost:8080 or https://dev-api.example.com.

.PARAMETER MailFrom
  Where to read the sign-in link from. 'compose' (the default) reads the api's log in the local stack,
  where APP_MAIL_MODE=log prints it. 'cloudwatch' reads the dev environment's log group with the AWS CLI
  (dev runs with mail mode 'log' so a script can sign in; production uses SES and has no such path).

.PARAMETER LogGroup
  The api log group, for -MailFrom cloudwatch.

.PARAMETER Latency
  Also time the three fixture postings (cache misses) against the PHASE6_SPEC.md section 5 targets.

.PARAMETER KeepAccount
  Don't delete the account at the end.

.EXAMPLE
  scripts/smoke-test.ps1
  scripts/smoke-test.ps1 -BaseUrl https://dev-api.example.com -MailFrom cloudwatch -LogGroup /tailor/dev/api
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateSet('compose', 'cloudwatch')][string]$MailFrom = 'compose',
    [string]$LogGroup = '',
    [switch]$Latency,
    [switch]$KeepAccount,
    [string]$Resume = (Join-Path $PSScriptRoot '..\fixtures\phase2\ok_synthetic.docx'),
    [string]$Intake = (Join-Path $PSScriptRoot '..\fixtures\phase4\intake_jane_doe.json'),
    [string]$Postings = (Join-Path $PSScriptRoot '..\fixtures\phase5\jds')
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$jar = Join-Path ([IO.Path]::GetTempPath()) ("smoke-" + [guid]::NewGuid() + ".jar")
$results = New-Object System.Collections.Generic.List[object]
$started = Get-Date

function Step([string]$name, [scriptblock]$body) {
    $t = Get-Date
    try {
        $detail = & $body
        $ms = [int]((Get-Date) - $t).TotalMilliseconds
        $results.Add([pscustomobject]@{ Step = $name; Ok = $true; Ms = $ms; Detail = $detail })
        Write-Host ("ok    {0,-46} {1,6} ms  {2}" -f $name, $ms, $detail)
    } catch {
        $ms = [int]((Get-Date) - $t).TotalMilliseconds
        $results.Add([pscustomobject]@{ Step = $name; Ok = $false; Ms = $ms; Detail = $_.Exception.Message })
        Write-Host ("FAIL  {0,-46} {1,6} ms  {2}" -f $name, $ms, $_.Exception.Message)
        throw
    }
}

# curl.exe with a cookie jar; returns status, body and the Location header. CSRF: the XSRF-TOKEN cookie is copied into
# X-XSRF-TOKEN on every request that changes state, as the SPA does.
function Call([string]$method, [string]$path, [string]$json = $null, [string]$file = $null, [hashtable]$headers = @{}) {
    $url = if ($path.StartsWith('http')) { $path } else { "$BaseUrl$path" }
    $out = [IO.Path]::GetTempFileName()
    $hdr = [IO.Path]::GetTempFileName()
    $curlArgs = @('-s', '-o', $out, '-D', $hdr, '-w', '%{http_code}', '-b', $jar, '-c', $jar, '-X', $method)
    if ($method -ne 'GET') {
        $token = (Select-String -Path $jar -Pattern 'XSRF-TOKEN\s+(\S+)' -ErrorAction SilentlyContinue | Select-Object -Last 1)
        if ($token) { $curlArgs += @('-H', "X-XSRF-TOKEN: $($token.Matches[0].Groups[1].Value)") }
    }
    foreach ($k in $headers.Keys) { $curlArgs += @('-H', "${k}: $($headers[$k])") }
    $bodyFile = $null
    if ($file) {
        $curlArgs += @('-F', "file=@$file")
    } elseif ($null -ne $json) {
        $bodyFile = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($bodyFile, $json, (New-Object Text.UTF8Encoding($false)))
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile")
    }
    $curlArgs += $url
    $status = [int](& curl.exe @curlArgs)
    $body = [IO.File]::ReadAllText($out)
    $location = (Select-String -Path $hdr -Pattern '^[Ll]ocation:\s*(.+)$' | Select-Object -First 1)
    Remove-Item $out, $hdr -ErrorAction SilentlyContinue
    if ($bodyFile) { Remove-Item $bodyFile -ErrorAction SilentlyContinue }
    [pscustomobject]@{
        Status = $status
        Body = $body
        Json = $(if ($body.TrimStart().StartsWith('{') -or $body.TrimStart().StartsWith('[')) { $body | ConvertFrom-Json } else { $null })
        Location = $(if ($location) { $location.Matches[0].Groups[1].Value.Trim() } else { $null })
    }
}

function Expect($r, [int]$status, [string]$what) {
    if ($r.Status -ne $status) { throw "$what answered $($r.Status), expected $status. $($r.Body.Substring(0, [Math]::Min(300, $r.Body.Length)))" }
}

function WaitJob([string]$jobId, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $last = ''
    while ((Get-Date) -lt $deadline) {
        $j = Call 'GET' "/jobs/$jobId"
        Expect $j 200 'GET /jobs'
        if ($j.Json.progress.stage -and $j.Json.progress.stage -ne $last) { $last = $j.Json.progress.stage }
        if ($j.Json.status -eq 'succeeded') { return $j.Json }
        if ($j.Json.status -eq 'failed') { throw "job failed: $($j.Json.error_code) $($j.Json.result | ConvertTo-Json -Compress -Depth 5)" }
        Start-Sleep -Milliseconds 400
    }
    throw "job $jobId did not finish in $timeoutSec s (last stage '$last')"
}

function SignInLink([string]$email) {
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        $text = if ($MailFrom -eq 'compose') {
            (docker compose logs api --since 2m 2>&1 | Out-String)
        } else {
            (aws logs filter-log-events --log-group-name $LogGroup --filter-pattern "`"$email`"" --start-time ([DateTimeOffset]::UtcNow.AddMinutes(-2).ToUnixTimeMilliseconds()) --query 'events[].message' --output text | Out-String)
        }
        $m = [regex]::Matches($text, [regex]::Escape($email) + ':\s*(\S+/auth/email/verify\?token=[A-Za-z0-9_-]+)')
        if ($m.Count -gt 0) { return $m[$m.Count - 1].Groups[1].Value }
        Start-Sleep -Seconds 2
    }
    throw "no sign-in link for $email in the $MailFrom log"
}

function Download([string]$url, [string]$what) {
    $tmp = [IO.Path]::GetTempFileName()
    & curl.exe -s -L -o $tmp $url
    $head = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($tmp), 0, 5)
    $size = (Get-Item $tmp).Length
    Remove-Item $tmp
    if ($head -ne '%PDF-') { throw "$what is not a PDF" }
    return $size
}

$email = "smoke+" + ([guid]::NewGuid().ToString('N').Substring(0, 10)) + "@example.com"
$ctx = @{}
try {
    Step 'health' { $r = Call 'GET' '/healthz'; Expect $r 200 'GET /healthz'; "role=$($r.Json.role)" }

    Step 'sign in with an email link' {
        $r = Call 'GET' '/auth/csrf'; Expect $r 204 'GET /auth/csrf'
        $r = Call 'POST' '/auth/email' (@{ email = $email } | ConvertTo-Json -Compress); Expect $r 202 'POST /auth/email'
        $link = SignInLink $email
        $r = Call 'GET' $link; Expect $r 302 'GET /auth/email/verify'
        $me = Call 'GET' '/me'; Expect $me 200 'GET /me'
        $ctx.user = $me.Json.id
        "user $($me.Json.id)"
    }

    Step 'upload the resume (gate runs now)' {
        $r = Call 'POST' '/resumes' -file (Resolve-Path $Resume).Path; Expect $r 202 'POST /resumes'
        $ctx.resume = $r.Json.resume_id; $ctx.onboard = $r.Json.job_id
        "resume $($ctx.resume)"
    }

    Step 'onboarding finishes' {
        $j = WaitJob $ctx.onboard 180
        $v = Call 'GET' "/resumes/$($ctx.resume)"; Expect $v 200 'GET /resumes'
        if ($v.Json.status -ne 'ready') { throw "resume is $($v.Json.status)" }
        "$($v.Json.onboarding.pages) page(s), $($v.Json.onboarding.editableCount) editable bullets, $($v.Json.sections.Count) sections"
    }

    Step 'preview PDF downloads' {
        $p = Call 'GET' "/resumes/$($ctx.resume)/preview"; Expect $p 200 'GET preview'
        "$(Download $p.Json.url 'the preview') bytes"
    }

    Step 'accept the preview' {
        $r = Call 'POST' "/resumes/$($ctx.resume)/accept" '{}'; Expect $r 200 'POST accept'
        $me = Call 'GET' '/me'
        if ($me.Json.active_resume_id -ne $ctx.resume) { throw 'the resume is not active' }
        'active'
    }

    Step 'fill in the intake' {
        $form = Call 'GET' "/resumes/$($ctx.resume)/intake"; Expect $form 200 'GET intake'
        $answers = Get-Content -Raw -Encoding UTF8 $Intake | ConvertFrom-Json
        foreach ($s in $answers.sections) {
            $body = @{ mode = $s.mode; notes = [string]$s.raw_text; fields = $s.fields } | ConvertTo-Json -Compress -Depth 6
            $r = Call 'PUT' "/resumes/$($ctx.resume)/intake/sections/$($s.id)" $body; Expect $r 200 "PUT intake $($s.id)"
        }
        "$($answers.sections.Count) sections saved"
    }

    Step 'generate the library' {
        $r = Call 'POST' "/resumes/$($ctx.resume)/generate" '{}' -headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }; Expect $r 202 'POST generate'
        $j = WaitJob $r.Json.job_id 900
        $lib = Call 'GET' "/resumes/$($ctx.resume)/library"; Expect $lib 200 'GET library'
        "library version $($lib.Json.version), cost `$$($j.result.costUsd)"
    }

    # [string] matters: Windows PowerShell 5.1 would serialize Get-Content's decorated string as an object.
    $jd = [string](Get-Content -Raw -Encoding UTF8 (Join-Path $Postings 'platform.txt'))
    Step 'add a posting; resume #1 is delivered' {
        $t = Get-Date
        $r = Call 'POST' '/postings' (@{ text = $jd } | ConvertTo-Json -Compress); Expect $r 202 'POST postings'
        $ctx.posting = $r.Json.posting_id
        WaitJob $r.Json.job_id 120 | Out-Null
        $ctx.firstMatchSeconds = [Math]::Round(((Get-Date) - $t).TotalSeconds, 1)
        $m = Call 'GET' "/postings/$($ctx.posting)/match"; Expect $m 200 'GET match'
        $ctx.snapshot = $m.Json.resume.id
        $ctx.alt = if ($m.Json.alternatives.Count -gt 0) { $m.Json.alternatives[0].id } else { $null }
        "$($ctx.firstMatchSeconds) s, $($m.Json.alternatives.Count) alternative(s), missing: $($m.Json.missing_skills.Count)"
    }

    Step "resume #1's PDF downloads" {
        $p = Call 'GET' "/snapshots/$($ctx.snapshot)/pdf"; Expect $p 200 'GET snapshot pdf'
        "$(Download $p.Json.url 'resume #1') bytes"
    }

    if ($ctx.alt) {
        Step 'an alternative is rendered on request' {
            $r = Call 'POST' "/snapshots/$($ctx.alt)/render" '{}'
            if ($r.Status -eq 202) { WaitJob $r.Json.job_id 120 | Out-Null } else { Expect $r 200 'POST render' }
            $p = Call 'GET' "/snapshots/$($ctx.alt)/pdf"; Expect $p 200 'GET alternative pdf'
            "$(Download $p.Json.url 'the alternative') bytes"
        }
    }

    Step 'check an edit, then save it as a revision' {
        $snap = Call 'GET' "/snapshots/$($ctx.snapshot)"; Expect $snap 200 'GET snapshot'
        $slot = $snap.Json.slots | Where-Object { $_.editable } | Select-Object -First 1
        if (-not $slot) { throw 'no editable bullet' }
        $words = ([string]$slot.text).Split(' ') | Select-Object -First 4
        $text = ($words -join ' ') + ' with a result of my own'
        $c = Call 'POST' "/snapshots/$($ctx.snapshot)/slots/$($slot.slot)/check" (@{ text = $text } | ConvertTo-Json -Compress); Expect $c 200 'POST check'
        $r = Call 'POST' "/snapshots/$($ctx.snapshot)/revisions" (@{ edits = @(@{ slot = $slot.slot; text = $text }) } | ConvertTo-Json -Compress -Depth 5)
        Expect $r 202 'POST revisions'
        WaitJob $r.Json.job_id 120 | Out-Null
        $rev = Call 'GET' "/snapshots/$($r.Json.snapshot_id)"; Expect $rev 200 'GET revision'
        $parent = Call 'GET' "/snapshots/$($ctx.snapshot)"
        if ($parent.Json.latest_revision_id -ne $r.Json.snapshot_id) { throw 'the revision is not the default download' }
        $p = Call 'GET' "/snapshots/$($r.Json.snapshot_id)/pdf"; Expect $p 200 'GET revision pdf'
        "verdict $($c.Json.verdict); revision $(Download $p.Json.url 'the revision') bytes"
    }

    Step 'the same posting again is a cache hit' {
        $r = Call 'POST' '/postings' (@{ text = $jd } | ConvertTo-Json -Compress); Expect $r 200 'POST postings (repeat)'
        if (-not $r.Json.cache_hit) { throw 'not marked as a cache hit' }
        'answered at once'
    }

    if ($Latency) {
        Step 'latency of cache-miss postings' {
            $times = @()
            foreach ($name in @('frontend', 'data')) {
                $text = [string](Get-Content -Raw -Encoding UTF8 (Join-Path $Postings "$name.txt"))
                $t = Get-Date
                $r = Call 'POST' '/postings' (@{ text = $text } | ConvertTo-Json -Compress); Expect $r 202 "POST postings ($name)"
                WaitJob $r.Json.job_id 120 | Out-Null
                $times += [Math]::Round(((Get-Date) - $t).TotalSeconds, 1)
            }
            $times += $ctx.firstMatchSeconds
            "seconds: $($times -join ', ') (targets: p50 <= 8, p95 <= 15, warm services)"
        }
    }

    Step 'sign out' {
        $r = Call 'POST' '/auth/logout' '{}'; Expect $r 204 'POST logout'
        $me = Call 'GET' '/me'; Expect $me 401 'GET /me after logout'
        'signed out'
    }

    if (-not $KeepAccount) {
        Step 'delete the account' {
            # sign in again to delete (the link flow is the same)
            $r = Call 'GET' '/auth/csrf'
            Call 'POST' '/auth/email' (@{ email = $email } | ConvertTo-Json -Compress) | Out-Null
            $link = SignInLink $email
            Call 'GET' $link | Out-Null
            $d = Call 'DELETE' '/me'; Expect $d 204 'DELETE /me'
            $me = Call 'GET' '/me'; Expect $me 401 'GET /me after delete'
            'account and data removed (stored files follow within 24 hours)'
        }
    }
} catch {
    Write-Host "`nSMOKE TEST FAILED: $($_.Exception.Message)"
    exit 1
} finally {
    Remove-Item $jar -ErrorAction SilentlyContinue
}

$failed = @($results | Where-Object { -not $_.Ok }).Count
Write-Host ("`n{0} steps, {1} failed, {2:N0} s" -f $results.Count, $failed, ((Get-Date) - $started).TotalSeconds)
if ($failed -gt 0) { exit 1 }
Write-Host 'SMOKE TEST PASSED'
