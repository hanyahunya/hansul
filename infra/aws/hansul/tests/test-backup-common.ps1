Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$directory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $directory 'deployment-common.ps1')
. (Join-Path $directory 'backup-common.ps1')

$workspace = New-DeploymentWorkspace
$snapshotId = '20260929T000000Z-1234abcd'
$prefix = "retained-backups/snapshots/$snapshotId"
$script:mockKeys = @()
$script:mockBucketDirectory = Join-Path $workspace 'mock-bucket'

function aws {
    $arguments = @($args)
    $global:LASTEXITCODE = 0
    if ($arguments[0] -eq 's3api' -and $arguments[1] -eq 'list-objects-v2') {
        @{ Contents = @($script:mockKeys | ForEach-Object { @{ Key = $_ } }) } | ConvertTo-Json -Depth 5
        return
    }
    if ($arguments[0] -eq 's3' -and $arguments[1] -eq 'cp') {
        $key = [string]$arguments[2] -replace '^s3://mock-state/', ''
        Copy-Item -LiteralPath (Join-Path $script:mockBucketDirectory $key) -Destination $arguments[3] -Force
        return
    }
    throw "Unexpected mocked AWS command: $($arguments[0..1] -join ' ')"
}

try {
    $config = @{ Profile = 'mock'; Region = 'ap-northeast-2' }
    $none = Get-LatestRestorableSnapshot -Config $config -Account '967848862788' -StateBucket 'mock-state' -Workspace $workspace
    if ($null -ne $none) { throw 'Empty bucket must return no snapshot.' }

    $snapshotDirectory = Join-Path $script:mockBucketDirectory $prefix
    [IO.Directory]::CreateDirectory($snapshotDirectory) | Out-Null
    $entries = @{}
    foreach ($database in @('hansul','keycloak')) {
        $path = Join-Path $snapshotDirectory "$database.dump"
        [IO.File]::WriteAllText($path, "synthetic-$database")
        $entries[$database] = @{
            key = "$prefix/$database.dump"
            sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            size = (Get-Item -LiteralPath $path).Length
        }
    }
    $manifest = @{ schemaVersion = 1; snapshotId = $snapshotId; accountId = '967848862788'; region = 'ap-northeast-2'; databases = $entries }
    [IO.File]::WriteAllText((Join-Path $snapshotDirectory 'manifest.json'), (ConvertTo-Json $manifest -Depth 6))
    $script:mockKeys = @("$prefix/hansul.dump", "$prefix/keycloak.dump", "$prefix/manifest.json")

    $result = Get-LatestRestorableSnapshot -Config $config -Account '967848862788' -StateBucket 'mock-state' -Workspace $workspace
    if ($result.Id -cne $snapshotId -or $result.Hashes['hansul'] -cne $entries['hansul'].sha256) {
        throw 'Valid snapshot was not selected and verified.'
    }
    [IO.File]::WriteAllText((Join-Path $snapshotDirectory 'hansul.dump'), 'corrupted')
    $rejected = $false
    try { Get-LatestRestorableSnapshot -Config $config -Account '967848862788' -StateBucket 'mock-state' -Workspace $workspace | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Corrupt backup was accepted.' }
    Write-Host 'BACKUP_COMMON_TEST_OK'
}
finally {
    Remove-Item -LiteralPath $workspace -Recurse -Force
}
