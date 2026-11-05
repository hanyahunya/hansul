param([switch]$PlanOnly, [switch]$IncludeAi, [switch]$FinalizeOnly)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'deployment-common.ps1')
. (Join-Path $PSScriptRoot 'backup-common.ps1')
. (Join-Path $PSScriptRoot 'full-stack-common.ps1')

function Get-OwnedDnsRecord {
    param([hashtable]$Config, [string]$ExpectedIp)
    $environmentPath = Join-Path $Config.DeployKit 'cloudflare.env'
    if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) { throw 'Cloudflare environment file is missing.' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($environmentPath)) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*)\s*$') { $values[$Matches[1]] = $Matches[2].Trim('"', "'") }
    }
    if ([string]::IsNullOrWhiteSpace($values['CLOUDFLARE_API_TOKEN'])) { throw 'Cloudflare token is missing.' }
    $headers = @{ Authorization = "Bearer $($values['CLOUDFLARE_API_TOKEN'])" }
    $zones = Invoke-RestMethod -Uri 'https://api.cloudflare.com/client/v4/zones?name=example.com&per_page=10' -Headers $headers
    $foundZones = @($zones.result | Where-Object { $null -ne $_ })
    if (-not $zones.success -or $foundZones.Count -ne 1) { throw 'Cloudflare zone preflight failed.' }
    $zoneId = [string]$foundZones[0].id
    $records = Invoke-RestMethod -Uri "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records?name=$($Config.Domain)&per_page=100" -Headers $headers
    $foundRecords = @($records.result | Where-Object { $null -ne $_ })
    if (-not $records.success -or $foundRecords.Count -gt 1) { throw 'Cloudflare DNS preflight failed.' }
    $record = if ($foundRecords.Count -eq 1) { $foundRecords[0] } else { $null }
    if ($null -ne $record -and ($record.type -ne 'A' -or $record.content -ne $ExpectedIp -or $record.comment -ne 'Managed by hansul deploy.ps1')) {
        throw 'Hansul DNS record does not match the deployed origin; refusing to destroy.'
    }
    return @{ Headers = $headers; ZoneId = $zoneId; Record = $record }
}

$config = Get-DeploymentConfig
Assert-Command aws 'Install AWS CLI v2.'
Assert-Command terraform 'Install Terraform.'
$account = Get-AwsIdentity -Config $config
$stateBucket = "hansul-tfstate-$account-$($config.Region)"
$aiDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\ai-bridge'))
$workspace = New-DeploymentWorkspace
$instanceId = $null
$writersStopped = $false
$aiInstanceId = $null
$aiRootVolumeId = $null
$aiRootPersists = $false
if ($FinalizeOnly -and -not $IncludeAi) { throw '-FinalizeOnly requires -IncludeAi.' }
# Every Parameter Store entry a Hansul deployment reads; the video keys are optional and created by hand.
$hansulParameters = @('/hansul/ai/token','/hansul/app/db-password','/hansul/app/kc-admin-password',
    '/hansul/app/cloudflare-crt','/hansul/app/cloudflare-key','/hansul/app/gemini-api-key',
    '/hansul/app/youtube-api-key','/hansul/app/gemini-paid-tier')

try {
    if ($FinalizeOnly) {
        # Resume only the final state-bucket cleanup after both Terraform
        # stacks have already been destroyed. Never use this to tear down a
        # partially running deployment.
        $localRestore = Get-LatestLocalRestorableSnapshot -BackupRoot (Join-Path $PSScriptRoot '.local\aws-backups') -Account $account -Region $config.Region
        if ($null -eq $localRestore) { throw 'No verified local DB snapshot; refusing to remove the last AWS backup/state bucket.' }
        $dns = Get-OwnedDnsRecord -Config $config -ExpectedIp ''
        if ($null -ne $dns.Record) { throw 'Hansul DNS still exists; full teardown is incomplete.' }

        $instancesRaw = & aws ec2 describe-instances --filters 'Name=tag:Project,Values=hansul' 'Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down' --query 'Reservations[].Instances[].InstanceId' --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul EC2 instances.' }
        $instances = ConvertFrom-Json -InputObject ($instancesRaw | Out-String)
        if (@($instances).Count -gt 0) { throw 'A Hansul EC2 instance is still active; refusing finalization.' }
        $volumesRaw = & aws ec2 describe-volumes --filters 'Name=tag:Project,Values=hansul' --query 'Volumes[].VolumeId' --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul EBS volumes.' }
        $volumes = ConvertFrom-Json -InputObject ($volumesRaw | Out-String)
        if (@($volumes).Count -gt 0) { throw 'A Hansul EBS volume remains; refusing finalization.' }
        $snapshotsRaw = & aws ec2 describe-snapshots --owner-ids self --filters 'Name=tag:Project,Values=hansul' --query 'Snapshots[].SnapshotId' --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul EBS snapshots.' }
        $snapshots = ConvertFrom-Json -InputObject ($snapshotsRaw | Out-String)
        if (@($snapshots).Count -gt 0) { throw 'A Hansul EBS snapshot remains; refusing finalization.' }
        $vpcsRaw = & aws ec2 describe-vpcs --filters 'Name=tag:Project,Values=hansul' --query 'Vpcs[].VpcId' --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul VPCs.' }
        $vpcs = ConvertFrom-Json -InputObject ($vpcsRaw | Out-String)
        if (@($vpcs).Count -gt 0) { throw 'A Hansul VPC remains; refusing finalization.' }
        $rolesRaw = & aws iam list-roles --query "Roles[?starts_with(RoleName, 'hansul-')].RoleName" --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul IAM roles.' }
        $roles = ConvertFrom-Json -InputObject ($rolesRaw | Out-String)
        if (@($roles).Count -gt 0) { throw 'A Hansul IAM role remains; refusing finalization.' }
        $parametersRaw = & aws ssm describe-parameters --parameter-filters 'Key=Name,Option=BeginsWith,Values=/hansul/' --query 'Parameters[].Name' --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul SSM parameters.' }
        $parameters = ConvertFrom-Json -InputObject ($parametersRaw | Out-String)
        if (@($parameters).Count -gt 0) { throw 'A Hansul SSM parameter remains; refusing finalization.' }
        if (Test-StateBucketExists -Config $config -Bucket "hansul-deploy-$account-$($config.Region)") {
            throw 'Hansul deployment bucket remains; refusing finalization.'
        }

        if (Test-StateBucketExists -Config $config -Bucket $stateBucket) {
            $versions = @(Get-OwnedStateBucketVersions -Config $config -Account $account -Bucket $stateBucket)
            foreach ($version in $versions) {
                if ($version.Key -match '^retained-backups/snapshots/([^/]+)/' -and $Matches[1] -gt $localRestore.Id) {
                    throw 'A retained S3 backup is newer than the verified local backup; refusing finalization.'
                }
            }
            foreach ($stack in @('app','ai-bridge')) {
                if (Test-StateObjectExists -Config $config -Bucket $stateBucket -Stack $stack) {
                    $statePath = Join-Path $workspace "$stack.tfstate"
                    Invoke-Checked aws @('s3','cp',"s3://$stateBucket/$stack/terraform.tfstate",$statePath,'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') "Could not inspect $stack Terraform state."
                    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
                    if ($null -eq $state -or @($state.resources).Count -gt 0) {
                        throw "$stack Terraform state still tracks resources; refusing finalization."
                    }
                }
            }
            if ($PlanOnly) {
                Write-Host "PLAN_ONLY: both stacks are gone, local DB snapshot $($localRestore.Id) is verified, and only $($versions.Count) owned versions in the Hansul state bucket remain. No bucket deleted."
                return
            }
            Remove-OwnedStateBucket -Config $config -Account $account -Bucket $stateBucket -Workspace $workspace
        }
        if (Test-StateBucketExists -Config $config -Bucket $stateBucket) { throw 'Hansul state bucket still exists after finalization.' }
        if ($PlanOnly) {
            Write-Host "PLAN_ONLY: Hansul AWS deployment is already fully destroyed. Verified local DB snapshot: $($localRestore.Id)"
            return
        }
        Write-Host "HANSUL_FULLY_DESTROYED. No Hansul AWS deployment resources remain. Verified local DB snapshot: $($localRestore.Directory)"
        return
    }

    Initialize-TerraformStack -Directory $aiDirectory -Stack 'ai-bridge' -Bucket $stateBucket -Config $config
    Initialize-TerraformStack -Directory $PSScriptRoot -Stack 'app' -Bucket $stateBucket -Config $config
    if ($IncludeAi) {
        $stateVersions = @(Get-OwnedStateBucketVersions -Config $config -Account $account -Bucket $stateBucket)
        Write-Host "Full-teardown preflight: $($stateVersions.Count) owned state-bucket versions; no unexpected keys."
        $aiInstanceId = Get-TerraformOutput -Directory $aiDirectory -Name 'instance_id'
        $aiRaw = & aws ec2 describe-instances --instance-ids $aiInstanceId --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect AI instance before full teardown.' }
        $ai = @((($aiRaw | Out-String | ConvertFrom-Json).Reservations)[0].Instances)[0]
        $aiName = @($ai.Tags | Where-Object { $_.Key -eq 'Name' } | ForEach-Object { $_.Value })
        if ($aiName.Count -ne 1 -or $aiName[0] -cne 'hansul-ai' -or $ai.State.Name -ne 'running') {
            throw 'AI instance identity or running-state check failed.'
        }
        $rootMappings = @($ai.BlockDeviceMappings | Where-Object { $_.DeviceName -eq $ai.RootDeviceName })
        if ($rootMappings.Count -ne 1 -or $rootMappings[0].Ebs.VolumeId -notmatch '^vol-[0-9a-f]+$') {
            throw 'Could not identify the exact AI root EBS volume.'
        }
        $aiRootVolumeId = [string]$rootMappings[0].Ebs.VolumeId
        $aiRootPersists = $rootMappings[0].Ebs.DeleteOnTermination -eq $false
    }
    $instanceId = Get-TerraformOutput -Directory $PSScriptRoot -Name 'instance_id'
    $publicIp = Get-TerraformOutput -Directory $PSScriptRoot -Name 'public_ip'
    $deployBucket = Get-TerraformOutput -Directory $PSScriptRoot -Name 'deployment_bucket'
    $tag = & aws ec2 describe-instances --instance-ids $instanceId --query 'Reservations[0].Instances[0].Tags[?Key==`Name`]|[0].Value' --output text --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0 -or $tag -ne 'hansul-app') { throw 'App instance identity check failed.' }
    $dns = Get-OwnedDnsRecord -Config $config -ExpectedIp $publicIp
    Wait-SsmOnline -Config $config -InstanceId $instanceId

    $variables = @(
        "-var=region=$($config.Region)",
        "-var=profile=$($config.Profile)",
        "-var=account_id=$account",
        "-var=app_instance_type=$($config.AppInstanceType)",
        "-var=ai_vpc_id=$(Get-TerraformOutput -Directory $aiDirectory -Name 'vpc_id')",
        "-var=ai_vpc_cidr=$(Get-TerraformOutput -Directory $aiDirectory -Name 'vpc_cidr')",
        "-var=ai_route_table_id=$(Get-TerraformOutput -Directory $aiDirectory -Name 'route_table_id')",
        "-var=ai_security_group_id=$(Get-TerraformOutput -Directory $aiDirectory -Name 'security_group_id')"
    )
    $plan = Join-Path $workspace 'destroy.tfplan'
    Push-Location -LiteralPath $PSScriptRoot
    try {
        Invoke-Checked terraform (@('plan','-destroy','-input=false','-out',$plan) + $variables) 'Could not plan app destruction.'
        $planJson = & terraform show -json $plan | Out-String | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect destroy plan.' }
        $deleted = @($planJson.resource_changes | Where-Object { $_.mode -eq 'managed' -and @($_.change.actions) -contains 'delete' } | ForEach-Object { $_.address })
        foreach ($required in @('aws_instance.app','aws_ebs_volume.data','aws_s3_bucket.deploy','aws_vpc.app','aws_vpc_peering_connection.ai')) {
            if ($deleted -notcontains $required) { throw "Destroy plan does not delete required app resource $required." }
        }
        foreach ($change in @($planJson.resource_changes | Where-Object { $_.mode -eq 'managed' })) {
            if ($change.address -notmatch '^aws_' -or @($change.change.actions) -notcontains 'delete') {
                throw "Unexpected resource in destroy plan: $($change.address)"
            }
            if (($change.type -eq 'aws_instance' -and $change.address -ne 'aws_instance.app') -or
                ($change.type -eq 'aws_vpc' -and $change.address -ne 'aws_vpc.app') -or
                ($change.type -eq 'aws_ebs_volume' -and $change.address -ne 'aws_ebs_volume.data') -or
                ($change.type -eq 'aws_s3_bucket' -and $change.address -ne 'aws_s3_bucket.deploy')) {
                throw "Destroy plan includes a resource outside the Hansul app stack: $($change.address)"
            }
        }
    }
    finally { Pop-Location }
    $aiPlan = $null
    $aiDeleted = @()
    if ($IncludeAi) {
        $aiPlan = Join-Path $workspace 'ai-destroy.tfplan'
        Push-Location -LiteralPath $aiDirectory
        try {
            Invoke-Checked terraform @('plan','-destroy','-input=false','-out',$aiPlan,
                "-var=region=$($config.Region)","-var=profile=$($config.Profile)","-var=account_id=$account",
                "-var=instance_type=$($config.AiInstanceType)") 'Could not plan AI destruction.'
            $aiPlanJson = & terraform show -json $aiPlan | Out-String | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0) { throw 'Could not inspect AI destroy plan.' }
            $aiDeleted = @($aiPlanJson.resource_changes | Where-Object { $_.mode -eq 'managed' -and @($_.change.actions) -contains 'delete' } | ForEach-Object { $_.address })
            foreach ($required in @('aws_instance.ai','aws_vpc.ai')) {
                if ($aiDeleted -notcontains $required) { throw "AI destroy plan does not delete $required." }
            }
            foreach ($change in @($aiPlanJson.resource_changes | Where-Object { $_.mode -eq 'managed' })) {
                if ($change.address -notmatch '^aws_' -or @($change.change.actions) -notcontains 'delete' -or
                    ($change.type -eq 'aws_instance' -and $change.address -ne 'aws_instance.ai') -or
                    ($change.type -eq 'aws_vpc' -and $change.address -ne 'aws_vpc.ai')) {
                    throw "Unexpected resource in AI destroy plan: $($change.address)"
                }
            }
        }
        finally { Pop-Location }
    }
    if ($PlanOnly) {
        if ($IncludeAi) {
            Write-Host "PLAN_ONLY: $($deleted.Count) app and $($aiDeleted.Count) AI resources, AI root EBS $aiRootVolumeId, the Hansul SSM parameters ($($hansulParameters -join ', ')) and the Hansul state S3 bucket would be removed after a verified local DB backup. No infrastructure was destroyed or DNS changed."
        }
        else { Write-Host "PLAN_ONLY: $($deleted.Count) app resources would be destroyed. No backup, container stop, Terraform apply or DNS change occurred." }
        return
    }

    # Quiesce both writers before taking the final two-database snapshot.
    # If anything fails before Terraform removes the instance, restart them.
    $writersStopped = $true
    Invoke-SsmShell -Config $config -InstanceId $instanceId -Commands @(
        'set -euo pipefail',
        'docker stop hansul-origin-1 hansul-api-1 hansul-keycloak-1 >/dev/null'
    ) -Workspace $workspace -TimeoutSeconds 180 | Out-Null

    # Publish a complete, verifiable snapshot outside the app Terraform stack.
    # The manifest is written last, so deploy ignores incomplete snapshots.
    $snapshotId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $snapshotDirectory = Join-Path $PSScriptRoot ".local\aws-backups\$snapshotId"
    [IO.Directory]::CreateDirectory($snapshotDirectory) | Out-Null
    $backupCommands = @(
        'set -euo pipefail',
        'install -d -m 700 /srv/hansul/backups',
        "docker exec hansul-postgres pg_dump -U hansul -d hansul -Fc > /srv/hansul/backups/hansul-$snapshotId.dump",
        "docker exec hansul-postgres pg_dump -U hansul -d keycloak -Fc > /srv/hansul/backups/keycloak-$snapshotId.dump",
        "docker exec -i hansul-postgres pg_restore --list < /srv/hansul/backups/hansul-$snapshotId.dump >/dev/null",
        "docker exec -i hansul-postgres pg_restore --list < /srv/hansul/backups/keycloak-$snapshotId.dump >/dev/null",
        "aws s3 cp /srv/hansul/backups/hansul-$snapshotId.dump s3://$deployBucket/backups/hansul-$snapshotId.dump --region $($config.Region) --only-show-errors",
        "aws s3 cp /srv/hansul/backups/keycloak-$snapshotId.dump s3://$deployBucket/backups/keycloak-$snapshotId.dump --region $($config.Region) --only-show-errors",
        "echo HANSUL_SHA256=`$(sha256sum /srv/hansul/backups/hansul-$snapshotId.dump | cut -d' ' -f1)",
        "echo KEYCLOAK_SHA256=`$(sha256sum /srv/hansul/backups/keycloak-$snapshotId.dump | cut -d' ' -f1)"
    )
    $commandId = Invoke-SsmShell -Config $config -InstanceId $instanceId -Commands $backupCommands -Workspace $workspace -TimeoutSeconds 900
    $backupOutput = & aws ssm get-command-invocation --command-id $commandId --instance-id $instanceId --query StandardOutputContent --output text --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify remote database hashes.' }
    $backupText = $backupOutput | Out-String
    $hashes = @{}
    foreach ($database in @('hansul','keycloak')) {
        $label = $database.ToUpperInvariant()
        if ($backupText -notmatch "(?m)^${label}_SHA256=([0-9a-f]{64})\s*$") { throw "Missing remote $database backup hash." }
        $hashes[$database] = $Matches[1]
    }

    $snapshotPrefix = "retained-backups/snapshots/$snapshotId"
    $manifestDatabases = @{}
    foreach ($database in @('hansul','keycloak')) {
        $filename = "$database.dump"
        $source = "s3://$deployBucket/backups/$database-$snapshotId.dump"
        $localPath = Join-Path $snapshotDirectory $filename
        Invoke-Checked aws @('s3','cp',$source,$localPath,'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') "Could not download $database backup."
        $file = Get-Item -LiteralPath $localPath
        $localHash = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($file.Length -le 0 -or $localHash -cne $hashes[$database]) { throw "$database backup hash or size mismatch; refusing destroy." }
        $key = "$snapshotPrefix/$filename"
        Invoke-Checked aws @('s3','cp',$localPath,"s3://$stateBucket/$key",'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') "Could not retain $database backup outside app stack."
        $manifestDatabases[$database] = @{ key = $key; sha256 = $localHash; size = $file.Length }
    }
    $manifest = @{
        schemaVersion = 1
        snapshotId = $snapshotId
        accountId = $account
        region = $config.Region
        createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        databases = $manifestDatabases
    }
    $manifestPath = Join-Path $snapshotDirectory 'manifest.json'
    [IO.File]::WriteAllText($manifestPath, (ConvertTo-Json $manifest -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    Invoke-Checked aws @('s3','cp',$manifestPath,"s3://$stateBucket/$snapshotPrefix/manifest.json",'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') 'Could not publish complete backup manifest.'
    $verified = Get-LatestRestorableSnapshot -Config $config -Account $account -StateBucket $stateBucket -Workspace $workspace
    if ($null -eq $verified -or $verified.Id -cne $snapshotId) { throw 'Uploaded backup is not the latest verified restorable snapshot.' }
    Write-Host "Verified both DB backups locally and in s3://$stateBucket/$snapshotPrefix/."

    Push-Location -LiteralPath $PSScriptRoot
    try {
        Invoke-Checked terraform @('apply','-input=false','-auto-approve',$plan) 'App destruction failed; inspect Terraform state before retrying.'
        $writersStopped = $false
    }
    finally { Pop-Location }

    $dnsAfter = Get-OwnedDnsRecord -Config $config -ExpectedIp $publicIp
    if ($null -ne $dnsAfter.Record) {
        $result = Invoke-RestMethod -Method Delete -Uri "https://api.cloudflare.com/client/v4/zones/$($dnsAfter.ZoneId)/dns_records/$($dnsAfter.Record.id)" -Headers $dnsAfter.Headers
        if (-not $result.success) { throw 'App destroyed but Cloudflare DNS removal failed.' }
    }
    if (-not $IncludeAi) {
        Write-Host "HANSUL_APP_DESTROYED. AI server retained. Latest restorable snapshot: $snapshotId"
        return
    }

    # Only the verified local copy survives the zero-residual-cost teardown.
    $localRestore = Get-LatestLocalRestorableSnapshot -BackupRoot (Join-Path $PSScriptRoot '.local\aws-backups') -Account $account -Region $config.Region
    if ($null -eq $localRestore -or $localRestore.Id -cne $snapshotId) {
        throw 'App destroyed, but latest local backup verification failed. AI and state bucket were retained.'
    }
    Push-Location -LiteralPath $aiDirectory
    try { Invoke-Checked terraform @('apply','-input=false','-auto-approve',$aiPlan) 'App is destroyed, but AI Terraform destruction failed.' }
    finally { Pop-Location }
    if ($aiRootPersists) {
        Invoke-Checked aws @('ec2','wait','volume-available','--volume-ids',$aiRootVolumeId,'--profile',$config.Profile,'--region',$config.Region) "AI was destroyed, but root EBS $aiRootVolumeId did not become available."
        $volumeRaw = & aws ec2 describe-volumes --volume-ids $aiRootVolumeId --output json --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw "Could not verify orphaned AI root EBS $aiRootVolumeId." }
        $volume = @((($volumeRaw | Out-String | ConvertFrom-Json).Volumes))[0]
        if ($volume.VolumeId -cne $aiRootVolumeId -or $volume.State -ne 'available' -or @($volume.Attachments).Count -ne 0) {
            throw "AI root EBS $aiRootVolumeId is not safe to delete."
        }
        Invoke-Checked aws @('ec2','delete-volume','--volume-id',$aiRootVolumeId,'--profile',$config.Profile,'--region',$config.Region) "Could not delete orphaned AI root EBS $aiRootVolumeId."
        Invoke-Checked aws @('ec2','wait','volume-deleted','--volume-ids',$aiRootVolumeId,'--profile',$config.Profile,'--region',$config.Region) "AI root EBS $aiRootVolumeId deletion was not confirmed."
    }
    if (@(Get-TerraformManagedAddresses -Directory $PSScriptRoot).Count -gt 0 -or
        @(Get-TerraformManagedAddresses -Directory $aiDirectory).Count -gt 0) {
        throw 'Terraform still tracks Hansul resources; state bucket must not be removed.'
    }
    foreach ($name in $hansulParameters) {
        $existing = & aws ssm describe-parameters --parameter-filters "Key=Name,Values=$name" --query 'Parameters[0].Name' --output text --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect SSM parameter $name." }
        if ($existing -eq $name) {
            Invoke-Checked aws @('ssm','delete-parameter','--name',$name,'--profile',$config.Profile,'--region',$config.Region) "Could not delete SSM parameter $name."
        }
    }
    Remove-OwnedStateBucket -Config $config -Account $account -Bucket $stateBucket -Workspace $workspace
    $remainingVolumesRaw = & aws ec2 describe-volumes --filters 'Name=tag:Project,Values=hansul' --query 'Volumes[].VolumeId' --output json --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul EBS volumes after teardown.' }
    $remainingVolumes = ConvertFrom-Json -InputObject ($remainingVolumesRaw | Out-String)
    if (@($remainingVolumes).Count -gt 0) { throw "Hansul EBS volumes remain after teardown: $($remainingVolumes -join ', ')." }
    $remainingInstancesRaw = & aws ec2 describe-instances --filters 'Name=tag:Project,Values=hansul' 'Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down' --query 'Reservations[].Instances[].InstanceId' --output json --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul EC2 instances after teardown.' }
    $remainingInstances = ConvertFrom-Json -InputObject ($remainingInstancesRaw | Out-String)
    if (@($remainingInstances).Count -gt 0) { throw "Hansul EC2 instances remain after teardown: $($remainingInstances -join ', ')." }
    $remainingSnapshotsRaw = & aws ec2 describe-snapshots --owner-ids self --filters 'Name=tag:Project,Values=hansul' --query 'Snapshots[].SnapshotId' --output json --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not audit Hansul EBS snapshots after teardown.' }
    $remainingSnapshots = ConvertFrom-Json -InputObject ($remainingSnapshotsRaw | Out-String)
    if (@($remainingSnapshots).Count -gt 0) { throw "Hansul EBS snapshots remain after teardown: $($remainingSnapshots -join ', ')." }
    if ((Test-StateBucketExists -Config $config -Bucket $stateBucket) -or
        (Test-StateBucketExists -Config $config -Bucket $deployBucket)) {
        throw 'A Hansul S3 bucket still exists after teardown.'
    }
    Write-Host "HANSUL_FULLY_DESTROYED. App, AI, AI root EBS, named parameters and state bucket removed. Local DB snapshot: $($localRestore.Directory)"
}
finally {
    if ($writersStopped -and $null -ne $instanceId) {
        try {
            Invoke-SsmShell -Config $config -InstanceId $instanceId -Commands @(
                'docker start hansul-keycloak-1 hansul-api-1 hansul-origin-1 >/dev/null'
            ) -Workspace $workspace -TimeoutSeconds 180 | Out-Null
            Write-Warning 'Destroy did not complete; application writers were restarted.'
        }
        catch { Write-Warning 'Destroy did not complete and application restart failed. Inspect the app EC2 immediately.' }
    }
    if (Test-Path -LiteralPath $workspace) { Remove-Item -LiteralPath $workspace -Recurse -Force }
}
