$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '..\full-stack-common.ps1')

$root = Join-Path ([IO.Path]::GetTempPath()) ('hansul-full-stack-test-' + [guid]::NewGuid().ToString('N'))
$id = '20260929T000000Z-abcdef12'
$directory = Join-Path $root $id
$account = '967848862788'
$region = 'ap-northeast-2'
try {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $entries = @{}
    foreach ($database in @('hansul','keycloak')) {
        $path = Join-Path $directory "$database.dump"
        [IO.File]::WriteAllBytes($path, [Text.Encoding]::UTF8.GetBytes("test-$database"))
        $entries[$database] = @{
            key = "retained-backups/snapshots/$id/$database.dump"
            sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            size = (Get-Item -LiteralPath $path).Length
        }
    }
    $manifest = @{ schemaVersion = 1; snapshotId = $id; accountId = $account; region = $region; databases = $entries }
    [IO.File]::WriteAllText((Join-Path $directory 'manifest.json'), (ConvertTo-Json $manifest -Depth 5))
    $found = Get-LatestLocalRestorableSnapshot -BackupRoot $root -Account $account -Region $region
    if ($found.Id -cne $id -or $found.Hashes.Count -ne 2) { throw 'Valid local backup was not found.' }
    [IO.File]::AppendAllText((Join-Path $directory 'hansul.dump'), 'tampered')
    $rejected = $false
    try { Get-LatestLocalRestorableSnapshot -BackupRoot $root -Account $account -Region $region | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Tampered local backup was accepted.' }
    Write-Host 'PASS: valid local snapshot accepted; tampered snapshot rejected.'

    function aws {
        $global:LASTEXITCODE = 0
        if (@($args)[0] -eq 's3api' -and @($args)[1] -eq 'list-object-versions') {
            if ($script:unsafeKey) { return '{"Versions":[{"Key":"other-project/data","VersionId":"v1"}]}' }
            $script:listCalls++
            if ($script:listCalls -eq 1 -or $script:keepVersions) { return '{"Versions":[{"Key":"app/terraform.tfstate","VersionId":"v1"}]}' }
            return '{}'
        }
        if (@($args)[0] -eq 's3api' -and @($args)[1] -eq 'delete-objects') {
            $script:deleteCalls++
            return
        }
        if (@($args)[0] -eq 's3api' -and @($args)[1] -eq 'list-objects-v2') {
            if ($script:stateObjectExists) { return 'ai-bridge/terraform.tfstate' }
            return 'None'
        }
        throw "Unexpected mocked AWS command: $($args -join ' ')"
    }
    function Invoke-Checked {
        param([string]$Program, [string[]]$Arguments, [string]$Failure)
        if ($Program -ne 'aws' -or $Arguments[0] -ne 's3api' -or $Arguments[1] -ne 'delete-bucket') {
            throw 'Unexpected bucket-deletion command.'
        }
        $script:bucketDeletes++
    }
    $config = @{ Profile = 'test'; Region = $region }
    $bucket = "hansul-tfstate-$account-$region"
    $script:unsafeKey = $true
    $script:keepVersions = $false
    $script:stateObjectExists = $false
    $script:listCalls = 0
    $script:deleteCalls = 0
    $script:bucketDeletes = 0
    $rejected = $false
    try { Remove-OwnedStateBucket -Config $config -Account $account -Bucket $bucket -Workspace $root }
    catch { $rejected = $true }
    if (-not $rejected -or $script:deleteCalls -ne 0 -or $script:bucketDeletes -ne 0) {
        throw 'Unexpected S3 key did not prevent bucket deletion.'
    }
    $script:unsafeKey = $false
    $script:keepVersions = $true
    $script:listCalls = 0
    $rejected = $false
    try { Remove-OwnedStateBucket -Config $config -Account $account -Bucket $bucket -Workspace $root }
    catch { $rejected = $true }
    if (-not $rejected -or $script:bucketDeletes -ne 0) {
        throw 'Bucket deletion was allowed while object versions remained.'
    }
    $script:keepVersions = $false
    $script:listCalls = 0
    $script:deleteCalls = 0
    Remove-OwnedStateBucket -Config $config -Account $account -Bucket $bucket -Workspace $root
    if ($script:deleteCalls -ne 1 -or $script:bucketDeletes -ne 1) {
        throw 'Owned S3 object version and bucket were not removed.'
    }
    if (Test-StateObjectExists -Config $config -Bucket $bucket -Stack 'ai-bridge') {
        throw 'Missing Terraform state object was reported as present.'
    }
    $script:stateObjectExists = $true
    if (-not (Test-StateObjectExists -Config $config -Bucket $bucket -Stack 'ai-bridge')) {
        throw 'Existing Terraform state object was not reported as present.'
    }
    Write-Host 'PASS: foreign S3 key blocked; owned versioned state bucket cleanup accepted.'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
