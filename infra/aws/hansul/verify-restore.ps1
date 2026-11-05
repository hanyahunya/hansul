param([string]$LegacyStamp)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'deployment-common.ps1')
. (Join-Path $PSScriptRoot 'backup-common.ps1')
. (Join-Path $PSScriptRoot 'full-stack-common.ps1')

$config = Get-DeploymentConfig
$account = Get-AwsIdentity -Config $config
Assert-Command docker 'Install and start Docker Desktop for isolated restore verification.'
$workspace = New-DeploymentWorkspace
$container = 'hansul-restore-check-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$containerCreated = $false

try {
    if ([string]::IsNullOrWhiteSpace($LegacyStamp)) {
        $stateBucket = "hansul-tfstate-$account-$($config.Region)"
        $snapshot = $null
        if (Test-StateBucketExists -Config $config -Bucket $stateBucket) {
            $snapshot = Get-LatestRestorableSnapshot -Config $config -Account $account -StateBucket $stateBucket -Workspace $workspace
        }
        if ($null -eq $snapshot) {
            $snapshot = Get-LatestLocalRestorableSnapshot -BackupRoot (Join-Path $PSScriptRoot '.local\aws-backups') -Account $account -Region $config.Region
        }
        if ($null -eq $snapshot) { throw 'No restorable snapshot is available.' }
        $sourceDirectory = $snapshot.Directory
    }
    else {
        if ($LegacyStamp -notmatch '^[0-9]{8}T[0-9]{6}Z$') { throw 'Invalid legacy backup stamp.' }
        $sourceDirectory = Join-Path $workspace 'legacy'
        [IO.Directory]::CreateDirectory($sourceDirectory) | Out-Null
        $bucket = "hansul-deploy-$account-$($config.Region)"
        foreach ($database in @('hansul','keycloak')) {
            Invoke-Checked aws @('s3','cp',"s3://$bucket/backups/$database-$LegacyStamp.dump",(Join-Path $sourceDirectory "$database.dump"),'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') "Could not download legacy $database dump."
        }
    }

    Invoke-Checked docker @('run','-d','--name',$container,'--network','none','-e','POSTGRES_PASSWORD=local-restore-check','-e','POSTGRES_USER=hansul','-e','POSTGRES_DB=hansul','postgres:18.6') 'Could not start isolated PostgreSQL.' | Out-Null
    $containerCreated = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $container pg_isready -U hansul -d hansul 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Isolated PostgreSQL did not become ready.' }
    Invoke-Checked docker @('exec',$container,'psql','-U','hansul','-d','postgres','-c','CREATE DATABASE keycloak') 'Could not create isolated Keycloak DB.' | Out-Null

    foreach ($database in @('hansul','keycloak')) {
        Invoke-Checked docker @('cp',(Join-Path $sourceDirectory "$database.dump"),"${container}:/tmp/$database.dump") "Could not stage $database dump."
        Invoke-Checked docker @('exec',$container,'pg_restore','--list',"/tmp/$database.dump") "Invalid $database dump." | Out-Null
        Invoke-Checked docker @('exec',$container,'pg_restore','--no-owner','--no-privileges','--exit-on-error','-U','hansul','-d',$database,"/tmp/$database.dump") "Could not restore $database DB."
    }
    $realms = & docker exec $container psql -U hansul -d keycloak -Atc 'select count(*) from realm'
    if ($LASTEXITCODE -ne 0 -or $realms -notmatch '^\d+$') { throw 'Restored Keycloak DB query failed.' }
    $migrations = & docker exec $container psql -U hansul -d hansul -Atc 'select count(*) from flyway_schema_history'
    if ($LASTEXITCODE -ne 0 -or $migrations -notmatch '^\d+$') { throw 'Restored Hansul DB query failed.' }
    Write-Host "RESTORE_TEST_OK: realms=$realms, migrations=$migrations. Live AWS was not changed."
}
finally {
    if ($containerCreated) { & docker rm -f $container 2>$null | Out-Null }
    if (Test-Path -LiteralPath $workspace) { Remove-Item -LiteralPath $workspace -Recurse -Force }
}
