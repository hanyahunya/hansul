Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Command {
    param([string]$Name, [string]$Hint)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing $Name. $Hint"
    }
}

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Failure)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw $Failure }
}

function Get-DeploymentConfig {
    $path = Join-Path $PSScriptRoot 'deploy.config.psd1'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Create $path from deploy.config.example.psd1 (AWS profile, region and root-account opt-in)."
    }
    $config = Import-PowerShellDataFile -LiteralPath $path
    if ($config.Region -ne 'ap-northeast-2' -or $config.Domain -ne 'hansul.example.com') {
        throw 'Only approved Seoul region and hansul.example.com are supported by this deployment.'
    }
    if ($config.AppInstanceType -ne 'm7i-flex.large' -or $config.AiInstanceType -ne 't3.small') {
        throw 'Review and update the instance-size guard before changing the approved sizing.'
    }
    if ([string]::IsNullOrWhiteSpace($config.Profile)) { throw 'AWS profile is required.' }
    return $config
}

function Get-AwsIdentity {
    param([hashtable]$Config)
    $raw = & aws sts get-caller-identity --profile $Config.Profile --region $Config.Region --output json 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "AWS session unavailable. Run aws login --profile $($Config.Profile) or aws sso login --profile $($Config.Profile), then retry."
    }
    $identity = ($raw | Out-String | ConvertFrom-Json)
    if ($identity.Arn -match ':root$' -and $Config.AllowRoot -ne $true) {
        throw 'Root account detected. Use a least-privilege deployment profile, or explicitly set AllowRoot = $true in the ignored local config.'
    }
    if ($identity.Account -notmatch '^[0-9]{12}$') { throw 'Invalid AWS account identity.' }
    Write-Host "AWS account $($identity.Account), region $($Config.Region), profile $($Config.Profile)"
    return [string]$identity.Account
}

function New-DeploymentWorkspace {
    $path = Join-Path ([System.IO.Path]::GetTempPath()) ("hansul-deploy-" + [guid]::NewGuid().ToString('N'))
    [System.IO.Directory]::CreateDirectory($path) | Out-Null
    return $path
}

function Initialize-StateBucket {
    param([hashtable]$Config, [string]$Account)
    $bucket = "hansul-tfstate-$Account-$($Config.Region)"
    $found = & aws s3api list-buckets --query "Buckets[?Name=='$bucket'].Name" --output text --profile $Config.Profile --region $Config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not list AWS state buckets.' }
    if ($found -ne $bucket) {
        Invoke-Checked aws @('s3api','create-bucket','--bucket',$bucket,'--create-bucket-configuration',"LocationConstraint=$($Config.Region)",'--profile',$Config.Profile,'--region',$Config.Region) 'Could not create Terraform state bucket.' | Out-Null
    }
    Invoke-Checked aws @('s3api','put-public-access-block','--bucket',$bucket,'--public-access-block-configuration','BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true','--profile',$Config.Profile,'--region',$Config.Region) 'Could not block state bucket public access.' | Out-Null
    Invoke-Checked aws @('s3api','put-bucket-versioning','--bucket',$bucket,'--versioning-configuration','Status=Enabled','--profile',$Config.Profile,'--region',$Config.Region) 'Could not enable state versioning.' | Out-Null
    $encryptionPath = [IO.Path]::GetTempFileName()
    try {
        $encryptionJson = @{ Rules = @(@{ ApplyServerSideEncryptionByDefault = @{ SSEAlgorithm = 'AES256' } }) } | ConvertTo-Json -Depth 5 -Compress
        [IO.File]::WriteAllText($encryptionPath, $encryptionJson, (New-Object System.Text.UTF8Encoding($false)))
        Invoke-Checked aws @('s3api','put-bucket-encryption','--bucket',$bucket,'--server-side-encryption-configuration',"file://$encryptionPath",'--profile',$Config.Profile,'--region',$Config.Region) 'Could not enable state encryption.' | Out-Null
    }
    finally { Remove-Item -LiteralPath $encryptionPath -Force -ErrorAction SilentlyContinue }
    return $bucket
}

function Initialize-TerraformStack {
    param([string]$Directory, [string]$Stack, [string]$Bucket, [hashtable]$Config)
    Push-Location -LiteralPath $Directory
    try {
        Invoke-Checked terraform @('init','-input=false','-reconfigure',"-backend-config=bucket=$Bucket","-backend-config=key=$Stack/terraform.tfstate","-backend-config=region=$($Config.Region)","-backend-config=profile=$($Config.Profile)",'-backend-config=use_lockfile=true') "Terraform init failed for $Stack."
        Invoke-Checked terraform @('validate','-no-color') "Terraform validation failed for $Stack."
    }
    finally { Pop-Location }
}

function Invoke-TerraformApply {
    param([string]$Directory, [string[]]$Variables, [string]$Workspace)
    $plan = Join-Path $Workspace ([guid]::NewGuid().ToString('N') + '.tfplan')
    Push-Location -LiteralPath $Directory
    try {
        Invoke-Checked terraform (@('plan','-input=false','-out',$plan) + $Variables) 'Terraform plan failed.'
        $planJson = & terraform show -json $plan | Out-String | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Terraform plan.' }
        foreach ($change in @($planJson.resource_changes | Where-Object { $null -ne $_ })) {
            if (@($change.change.actions) -contains 'delete') {
                throw "Terraform would delete or replace $($change.address). Review the plan and back up data before an explicitly approved migration."
            }
        }
        Invoke-Checked terraform @('apply','-input=false','-auto-approve',$plan) 'Terraform apply failed.'
    }
    finally { Pop-Location }
}

function Get-TerraformOutput {
    param([string]$Directory, [string]$Name)
    Push-Location -LiteralPath $Directory
    try {
        $value = & terraform output -raw $Name
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) { throw "Missing Terraform output $Name." }
        return [string]$value
    }
    finally { Pop-Location }
}

function Wait-SsmOnline {
    param([hashtable]$Config, [string]$InstanceId)
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $status = & aws ssm describe-instance-information --profile $Config.Profile --region $Config.Region --filters "Key=InstanceIds,Values=$InstanceId" --query 'InstanceInformationList[0].PingStatus' --output text 2>$null
        if ($LASTEXITCODE -eq 0 -and $status -eq 'Online') { return }
        Start-Sleep -Seconds 10
    }
    throw "Instance $InstanceId did not become SSM Online. Inspect EC2 user-data and instance role."
}

function Invoke-SsmShell {
    param([hashtable]$Config, [string]$InstanceId, [string[]]$Commands, [string]$Workspace, [int]$TimeoutSeconds = 1200)
    $parameterPath = Join-Path $Workspace ([guid]::NewGuid().ToString('N') + '.json')
    $json = @{ commands = $Commands } | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($parameterPath, $json, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $id = & aws ssm send-command --instance-ids $InstanceId --document-name AWS-RunShellScript --parameters "file://$parameterPath" --timeout-seconds $TimeoutSeconds --query Command.CommandId --output text --profile $Config.Profile --region $Config.Region
        if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[0-9a-f-]{36}$') { throw 'SSM command dispatch failed.' }
        for ($attempt = 0; $attempt -lt [math]::Ceiling($TimeoutSeconds / 5); $attempt++) {
            Start-Sleep -Seconds 5
            $status = & aws ssm get-command-invocation --command-id $id --instance-id $InstanceId --query Status --output text --profile $Config.Profile --region $Config.Region 2>$null
            if ($LASTEXITCODE -ne 0) { continue }
            if ($status -eq 'Success') { return $id }
            if ($status -in @('Failed','Cancelled','TimedOut','Cancelling')) {
                throw "SSM command $id ended in $status. Inspect that command's output in AWS Systems Manager (do not paste secrets)."
            }
        }
        throw "SSM command $id timed out. Inspect Systems Manager before retrying."
    }
    finally { Remove-Item -LiteralPath $parameterPath -Force -ErrorAction SilentlyContinue }
}

function Set-SecureParameter {
    param([hashtable]$Config, [string]$Name, [string]$Value, [string]$Workspace, [switch]$OnlyIfMissing)
    if ($OnlyIfMissing) {
        $existing = & aws ssm describe-parameters --parameter-filters "Key=Name,Values=$Name" --query 'Parameters[0].Name' --output text --profile $Config.Profile --region $Config.Region
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect encrypted parameter $Name." }
        if ($existing -eq $Name) { return }
    }
    $path = Join-Path $Workspace ([guid]::NewGuid().ToString('N') + '.secret.json')
    try {
        $json = @{ Name = $Name; Value = $Value; Type = 'SecureString'; Overwrite = $true } | ConvertTo-Json -Compress
        [IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
        & aws ssm put-parameter --cli-input-json "file://$path" --profile $Config.Profile --region $Config.Region 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not save encrypted parameter $Name." }
    }
    finally { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
}

function New-Secret {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) }
    finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
