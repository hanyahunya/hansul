Set-StrictMode -Version Latest

function Get-LatestRestorableSnapshot {
    param([hashtable]$Config, [string]$Account, [string]$StateBucket, [string]$Workspace)
    $raw = & aws s3api list-objects-v2 --bucket $StateBucket --prefix 'retained-backups/' --output json --profile $Config.Profile --region $Config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect retained backups; refusing fresh database creation.' }
    $listing = $raw | Out-String | ConvertFrom-Json
    $keys = @()
    if (@($listing.PSObject.Properties | ForEach-Object { $_.Name }) -contains 'Contents') {
        $keys = @($listing.Contents | ForEach-Object { $_.Key })
    }
    $manifests = @($keys | Where-Object { $_ -match '^retained-backups/snapshots/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}/manifest\.json$' } | Sort-Object -Descending)
    if ($manifests.Count -eq 0) {
        if ($keys.Count -gt 0) { throw 'Retained backups exist but no complete snapshot manifest was found. Restore manually or explicitly use -Fresh.' }
        return $null
    }

    $manifestKey = $manifests[0]
    $snapshotId = ($manifestKey -split '/')[2]
    $restoreDirectory = Join-Path $Workspace 'restore'
    [IO.Directory]::CreateDirectory($restoreDirectory) | Out-Null
    $manifestPath = Join-Path $restoreDirectory 'manifest.json'
    Invoke-Checked aws @('s3','cp',"s3://$StateBucket/$manifestKey",$manifestPath,'--profile',$Config.Profile,'--region',$Config.Region,'--only-show-errors') 'Could not download backup manifest.'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.snapshotId -cne $snapshotId -or
        $manifest.accountId -cne $Account -or $manifest.region -cne $Config.Region) {
        throw 'Latest backup manifest does not match this Hansul deployment.'
    }
    $hashes = @{}
    foreach ($database in @('hansul','keycloak')) {
        $entry = $manifest.databases.$database
        $expectedKey = "retained-backups/snapshots/$snapshotId/$database.dump"
        if ($entry.key -cne $expectedKey -or $entry.sha256 -cnotmatch '^[0-9a-f]{64}$' -or [long]$entry.size -le 0) {
            throw "Invalid $database backup manifest entry."
        }
        $localPath = Join-Path $restoreDirectory "$database.dump"
        Invoke-Checked aws @('s3','cp',"s3://$StateBucket/$expectedKey",$localPath,'--profile',$Config.Profile,'--region',$Config.Region,'--only-show-errors') "Could not download $database backup."
        $actualHash = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $actualSize = (Get-Item -LiteralPath $localPath).Length
        if ($actualHash -cne $entry.sha256 -or $actualSize -ne [long]$entry.size) {
            throw "$database retained backup does not match its manifest; refusing deployment."
        }
        $hashes[$database] = $actualHash
    }
    return @{ Id = $snapshotId; Directory = $restoreDirectory; Hashes = $hashes }
}
