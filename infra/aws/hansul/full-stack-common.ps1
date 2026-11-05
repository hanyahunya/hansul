Set-StrictMode -Version Latest

function Get-TerraformManagedAddresses {
    param([string]$Directory)
    Push-Location -LiteralPath $Directory
    try {
        $addresses = @(& terraform state list)
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect Terraform state in $Directory." }
        return @($addresses | Where-Object { $_ -and $_ -notmatch '^data\.' })
    }
    finally { Pop-Location }
}

function Get-LatestLocalRestorableSnapshot {
    param([string]$BackupRoot, [string]$Account, [string]$Region)
    if (-not (Test-Path -LiteralPath $BackupRoot -PathType Container)) { return $null }
    $directories = @(Get-ChildItem -LiteralPath $BackupRoot -Directory |
        Where-Object { $_.Name -match '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$' } |
        Sort-Object Name -Descending)
    if ($directories.Count -eq 0) { return $null }
    $directory = $directories[0]
    $manifestPath = Join-Path $directory.FullName 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Latest local snapshot $($directory.Name) has no manifest. Refusing to use an older snapshot."
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.snapshotId -cne $directory.Name -or
        $manifest.accountId -cne $Account -or $manifest.region -cne $Region) {
        throw 'Latest local snapshot manifest does not match this Hansul deployment.'
    }
    $hashes = @{}
    foreach ($database in @('hansul','keycloak')) {
        $entry = $manifest.databases.$database
        $expectedKey = "retained-backups/snapshots/$($directory.Name)/$database.dump"
        $path = Join-Path $directory.FullName "$database.dump"
        if ($null -eq $entry -or $entry.key -cne $expectedKey -or
            $entry.sha256 -cnotmatch '^[0-9a-f]{64}$' -or [long]$entry.size -le 0 -or
            -not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Latest local $database backup or manifest entry is invalid."
        }
        $file = Get-Item -LiteralPath $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($file.Length -ne [long]$entry.size -or $hash -cne $entry.sha256) {
            throw "Latest local $database backup failed size/SHA-256 verification."
        }
        $hashes[$database] = $hash
    }
    return @{ Id = $directory.Name; Directory = $directory.FullName; Hashes = $hashes }
}

function Test-StateBucketExists {
    param([hashtable]$Config, [string]$Bucket)
    $found = & aws s3api list-buckets --query "Buckets[?Name=='$Bucket'].Name" --output text --profile $Config.Profile --region $Config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Hansul Terraform state bucket.' }
    return $found -eq $Bucket
}

function Test-StateObjectExists {
    param([hashtable]$Config, [string]$Bucket, [ValidateSet('app','ai-bridge')][string]$Stack)
    $key = "$Stack/terraform.tfstate"
    $found = & aws s3api list-objects-v2 --bucket $Bucket --prefix $key --query "Contents[?Key=='$key'].Key" --output text --profile $Config.Profile --region $Config.Region
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect $Stack Terraform state object." }
    return $found -eq $key
}

function Get-OwnedStateBucketVersions {
    param([hashtable]$Config, [string]$Account, [string]$Bucket)
    $expected = "hansul-tfstate-$Account-$($Config.Region)"
    if ($Bucket -cne $expected) { throw 'Refusing to delete a bucket outside the exact Hansul state bucket.' }
    $raw = & aws s3api list-object-versions --bucket $Bucket --output json --profile $Config.Profile --region $Config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not inventory every state-bucket object version.' }
    $listing = $raw | Out-String | ConvertFrom-Json
    $objects = @()
    $listingProperties = @($listing.PSObject.Properties | ForEach-Object { $_.Name })
    foreach ($property in @('Versions','DeleteMarkers')) {
        if ($listingProperties -contains $property) {
            foreach ($item in @($listing.$property)) {
                if ($item.Key -cnotmatch '^(app|ai-bridge)/terraform\.tfstate(\.tflock)?$' -and
                    $item.Key -cnotmatch '^retained-backups/snapshots/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}/(hansul\.dump|keycloak\.dump|manifest\.json)$') {
                    throw "Unexpected key in Hansul state bucket: $($item.Key). Refusing bucket deletion."
                }
                $objects += @{ Key = [string]$item.Key; VersionId = [string]$item.VersionId }
            }
        }
    }
    return @($objects)
}

function Remove-OwnedStateBucket {
    param([hashtable]$Config, [string]$Account, [string]$Bucket, [string]$Workspace)
    $objects = @(Get-OwnedStateBucketVersions -Config $Config -Account $Account -Bucket $Bucket)
    for ($offset = 0; $offset -lt $objects.Count; $offset += 1000) {
        $end = [math]::Min($offset + 999, $objects.Count - 1)
        $batch = @($objects[$offset..$end])
        $requestPath = Join-Path $Workspace ("delete-versions-$offset.json")
        $request = @{ Objects = $batch; Quiet = $true } | ConvertTo-Json -Depth 5 -Compress
        [IO.File]::WriteAllText($requestPath, $request, (New-Object System.Text.UTF8Encoding($false)))
        $responseRaw = & aws s3api delete-objects --bucket $Bucket --delete "file://$requestPath" --output json --profile $Config.Profile --region $Config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Failed deleting state-bucket object versions.' }
        # In S3 Quiet mode a successful DeleteObjects call can produce no CLI
        # output at all. The subsequent list-object-versions check is the
        # authoritative success check; never dereference a null response.
        $responseText = $responseRaw | Out-String
        if (-not [string]::IsNullOrWhiteSpace($responseText)) {
            $response = $responseText | ConvertFrom-Json
            if ($null -ne $response) {
                $responseProperties = @($response.PSObject.Properties | ForEach-Object { $_.Name })
                if ($responseProperties -contains 'Errors' -and @($response.Errors).Count -gt 0) {
                    throw 'S3 reported object-version deletion errors; state bucket was retained.'
                }
            }
        }
    }
    $remainingRaw = & aws s3api list-object-versions --bucket $Bucket --output json --profile $Config.Profile --region $Config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify empty state bucket.' }
    $remaining = $remainingRaw | Out-String | ConvertFrom-Json
    $remainingProperties = @($remaining.PSObject.Properties | ForEach-Object { $_.Name })
    if (($remainingProperties -contains 'Versions' -and @($remaining.Versions).Count -gt 0) -or
        ($remainingProperties -contains 'DeleteMarkers' -and @($remaining.DeleteMarkers).Count -gt 0)) {
        throw 'State bucket still contains object versions; refusing to report zero residual storage.'
    }
    Invoke-Checked aws @('s3api','delete-bucket','--bucket',$Bucket,'--profile',$Config.Profile,'--region',$Config.Region) 'Could not remove the now-empty Hansul state bucket.'
}
