<#
.SYNOPSIS
    Copies the newest backup from the server to this Windows machine over SSH (PHASE6_SPEC.md section 10A.5).
    Run it weekly at least. The backup is encrypted for your age key, so the copy is safe to store anywhere.

.EXAMPLE
    .\deploy\pull-backup.ps1 -Server variant@myname.duckdns.org -Dest D:\variant-backups
    .\deploy\pull-backup.ps1 -Server variant@203.0.113.7 -KeyFile $HOME\.ssh\variant_ed25519 -Dest D:\variant-backups
#>
param(
    [Parameter(Mandatory = $true)][string]$Server,
    [string]$Dest = (Join-Path $HOME 'variant-backups'),
    [string]$KeyFile = '',
    [string]$RemoteDir = '/var/backups/variant',
    # How many copies to keep on this machine.
    [int]$Keep = 12
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

$sshArgs = @('-o', 'BatchMode=yes')
if ($KeyFile) { $sshArgs += @('-i', $KeyFile) }

$remoteFile = (& ssh @sshArgs $Server "ls -1t $RemoteDir/variant-backup-*.tar.age 2>/dev/null | head -n 1") | Out-String
$remoteFile = $remoteFile.Trim()
if (-not $remoteFile) { throw "no backup found in $RemoteDir on $Server (has backup.sh run?)" }
$name = Split-Path -Leaf $remoteFile
$target = Join-Path $Dest $name

if (Test-Path $target) {
    Write-Host "already have $name"
} else {
    $expected = ((& ssh @sshArgs $Server "sha256sum '$remoteFile' | cut -d' ' -f1") | Out-String).Trim()
    & scp @sshArgs "${Server}:$remoteFile" "$target.part"
    if ($LASTEXITCODE -ne 0) { Remove-Item "$target.part" -ErrorAction SilentlyContinue; throw 'scp failed' }
    $actual = (Get-FileHash -Algorithm SHA256 "$target.part").Hash.ToLower()
    if ($actual -ne $expected.ToLower()) { Remove-Item "$target.part"; throw "checksum mismatch: the copy is damaged ($actual vs $expected)" }
    Move-Item "$target.part" $target
    Write-Host "copied $name ($([Math]::Round((Get-Item $target).Length / 1MB, 1)) MB), checksum verified"
}

# Warn when the newest backup on the server is old: the nightly cron may have stopped.
$stamp = [regex]::Match($name, 'variant-backup-(\d{8}T\d{6}Z)').Groups[1].Value
if ($stamp) {
    $when = [datetime]::ParseExact($stamp, 'yyyyMMddTHHmmssZ', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal)
    $age = (Get-Date).ToUniversalTime() - $when
    if ($age.TotalHours -gt 36) { Write-Warning ("The newest backup on the server is {0:N0} hours old: check the nightly cron (journalctl -t variant-backup)." -f $age.TotalHours) }
}

Get-ChildItem $Dest -Filter 'variant-backup-*.tar.age' | Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep | Remove-Item -Force
Write-Host "backups in ${Dest}: $((Get-ChildItem $Dest -Filter 'variant-backup-*.tar.age').Count)"
