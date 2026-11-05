$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$deploy = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '..\remote-deploy.sh'))
$destroy = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '..\destroy.ps1'))
$compose = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '..\compose.production.yaml'))

# A full teardown has to delete every Parameter Store entry a deployment reads, or finalization refuses.
$read = @([regex]::Matches($deploy, '--name (/hansul/[a-z0-9/-]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$deleted = @([regex]::Matches($destroy, "'(/hansul/[a-z0-9/-]+)'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
if ($read.Count -lt 5) { throw 'The parameters remote-deploy.sh reads were not found.' }
$left = @($read | Where-Object { $deleted -notcontains $_ })
if ($left.Count -gt 0) { throw "destroy.ps1 leaves these parameters behind: $($left -join ', ')" }
Write-Host "PASS: full teardown deletes all $($read.Count) parameters the deployment reads."

# Phone-video uploads have to outlive the API container, without filling the PostgreSQL data disk.
if ($compose -notmatch 'HANSUL_VIDEO_UPLOAD_DIR: (/\S+)') { throw 'The API has no video upload directory.' }
$uploadDirectory = $Matches[1]
if ($compose -notmatch "-\s+(/\S+):$([regex]::Escape($uploadDirectory))\s") {
    throw 'The video upload directory is not mounted from the host.'
}
$hostDirectory = $Matches[1]
if ($hostDirectory -like '/srv/hansul/*') { throw 'Video uploads must not live on the PostgreSQL data disk.' }
if ($deploy -notmatch "install -d [^\n]*-o 10001 -g 10001 $([regex]::Escape($hostDirectory))\s") {
    throw 'remote-deploy.sh does not create the upload directory for the API user 10001.'
}
Write-Host "PASS: video uploads live in $hostDirectory on the host, owned by the API user."
