param([switch]$SkipBuild, [switch]$PreflightOnly, [switch]$Fresh, [switch]$IncludeAi)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'deployment-common.ps1')
. (Join-Path $PSScriptRoot 'backup-common.ps1')
. (Join-Path $PSScriptRoot 'full-stack-common.ps1')

function Read-CloudflareEnvironment {
    param([string]$Path)
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*)\s*$') {
            $values[$Matches[1]] = $Matches[2].Trim('"', "'")
        }
    }
    if ([string]::IsNullOrWhiteSpace($values['CLOUDFLARE_API_TOKEN'])) {
        throw 'Cloudflare token missing from deploy kit.'
    }
    return $values
}

function Invoke-Cloudflare {
    param([string]$Token, [string]$Method, [string]$Path, [object]$Body = $null)
    $args = @{ Uri = "https://api.cloudflare.com/client/v4$Path"; Method = $Method; Headers = @{ Authorization = "Bearer $Token" }; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $args['ContentType'] = 'application/json'
        $args['Body'] = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
    }
    try { $response = Invoke-RestMethod @args }
    catch { throw "Cloudflare $Method request failed. Check token permissions and connectivity." }
    if (-not $response.success) { throw "Cloudflare $Method request was rejected." }
    return $response
}

function Get-DnsContext {
    param([string]$Token, [string]$Domain)
    $zones = Invoke-Cloudflare -Token $Token -Method Get -Path '/zones?name=example.com&per_page=10'
    if (@($zones.result).Count -ne 1) { throw 'Expected one example.com Cloudflare zone.' }
    $zoneId = [string](@($zones.result)[0].id)
    $query = [uri]::EscapeDataString($Domain)
    $records = Invoke-Cloudflare -Token $Token -Method Get -Path "/zones/$zoneId/dns_records?name=$query&per_page=100"
    $found = @($records.result)
    if ($found.Count -gt 1) { throw 'Multiple DNS records exist for this host; refusing to modify them.' }
    if ($found.Count -eq 1) {
        $record = $found[0]
        if ($record.type -ne 'A' -or $record.comment -ne 'Managed by hansul deploy.ps1') {
            throw 'An unmanaged DNS record already exists for hansul.example.com. Refusing overwrite.'
        }
        return @{ ZoneId = $zoneId; Record = $record }
    }
    return @{ ZoneId = $zoneId; Record = $null }
}

function Update-Dns {
    param([string]$Token, [hashtable]$Context, [string]$Domain, [string]$Ip)
    if ($Ip -notmatch '^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$') { throw 'Invalid EC2 public IPv4 address.' }
    $zoneId = $Context.ZoneId
    if ($null -eq $Context.Record) {
        $body = @{ type = 'A'; name = $Domain; content = $Ip; proxied = $true; ttl = 1; comment = 'Managed by hansul deploy.ps1' }
        Invoke-Cloudflare -Token $Token -Method Post -Path "/zones/$zoneId/dns_records" -Body $body | Out-Null
    }
    elseif ($Context.Record.content -ne $Ip -or -not $Context.Record.proxied) {
        $body = @{ content = $Ip; proxied = $true; ttl = 1 }
        Invoke-Cloudflare -Token $Token -Method Patch -Path "/zones/$zoneId/dns_records/$($Context.Record.id)" -Body $body | Out-Null
    }
}

function Assert-OriginCertificate {
    param([string]$Path)
    $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($Path)
    $san = $cert.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.17' } | Select-Object -First 1
    $names = if ($san) { $san.Format($false) } else { '' }
    if ($cert.NotAfter.ToUniversalTime() -lt [datetime]::UtcNow -or
        (($names -notmatch 'hansul\.example\.com') -and ($names -notmatch '\*\.example\.com'))) {
        throw 'Origin certificate is expired or does not cover hansul.example.com.'
    }
}

function Test-PublicEndpoint {
    param([string]$Url)
    for ($i = 0; $i -lt 36; $i++) {
        try {
            $body = & curl.exe --silent --show-error --fail --max-time 10 $Url 2>$null
            if ($LASTEXITCODE -eq 0) {
                $response = ($body | Out-String | ConvertFrom-Json)
                if ($Url.EndsWith('/actuator/health') -and $response.status -eq 'UP') { return }
                if ($Url.Contains('/.well-known/openid-configuration') -and
                    $response.issuer -eq 'https://hansul.example.com/realms/hansul') { return }
            }
        }
        catch { }
        Start-Sleep -Seconds 5
    }
    throw "Public HTTPS health check failed at $Url. Check DNS propagation, Cloudflare Full (strict), origin SG and Nginx."
}

function Get-GzipBase64 {
    param([string]$Path)
    $bytes = [IO.File]::ReadAllBytes($Path)
    $buffer = New-Object IO.MemoryStream
    $compressor = New-Object IO.Compression.GZipStream($buffer, [IO.Compression.CompressionMode]::Compress, $true)
    try { $compressor.Write($bytes, 0, $bytes.Length) }
    finally { $compressor.Dispose() }
    try { return [Convert]::ToBase64String($buffer.ToArray()) }
    finally { $buffer.Dispose() }
}

$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$aiDir = Join-Path $repo 'infra\aws\ai-bridge'
$config = Get-DeploymentConfig
if (-not [string]::IsNullOrWhiteSpace($config.JavaHome)) {
    if (-not (Test-Path -LiteralPath (Join-Path $config.JavaHome 'bin\java.exe') -PathType Leaf)) {
        throw 'Configured JavaHome does not contain java.exe.'
    }
    $env:JAVA_HOME = $config.JavaHome
    $env:Path = (Join-Path $config.JavaHome 'bin') + ';' + $env:Path
}
Assert-Command aws 'Install AWS CLI v2.'
Assert-Command terraform 'Install Terraform >= 1.10.'
Assert-Command tar 'Use Windows tar.exe.'
Assert-Command curl.exe 'Install curl for public HTTPS verification.'
Assert-Command java 'Install JDK 25 for the Spring Boot build.'
$kit = [System.IO.Path]::GetFullPath($config.DeployKit)
$certPath = Join-Path $kit 'domain\cloudflare.crt'
$keyPath = Join-Path $kit 'domain\cloudflare.key'
$cfPath = Join-Path $kit 'cloudflare.env'
foreach ($path in @($certPath, $keyPath, $cfPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required deploy-kit file: $([IO.Path]::GetFileName($path))" }
}
Write-Host 'Deploy-kit files: cloudflare.crt, cloudflare.key, cloudflare.env found.'
Assert-OriginCertificate -Path $certPath
$cf = Read-CloudflareEnvironment -Path $cfPath
$dns = Get-DnsContext -Token $cf['CLOUDFLARE_API_TOKEN'] -Domain $config.Domain
$account = Get-AwsIdentity -Config $config
if ($PreflightOnly) {
    Write-Host "Preflight passed for $($config.Domain): certificate valid, DNS collision absent or owned, account $account. No AWS or DNS resources changed."
    return
}
$workspace = New-DeploymentWorkspace
try {
    if (-not $SkipBuild) {
        Push-Location -LiteralPath (Join-Path $repo 'server')
        try {
            & .\gradlew.bat test bootJar
            if ($LASTEXITCODE -ne 0) { throw 'Server tests or bootJar build failed.' }
        }
        finally { Pop-Location }
    }
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo 'server\build\libs') -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
    if ($null -eq $jar) { throw 'Spring Boot executable JAR is missing.' }

    $stateBucket = "hansul-tfstate-$account-$($config.Region)"
    $stateBucketExists = Test-StateBucketExists -Config $config -Bucket $stateBucket
    if (-not $IncludeAi -and -not $stateBucketExists) {
        throw 'AI infrastructure is absent. Use deploy.ps1 -IncludeAi to recreate both stacks.'
    }
    $localRestore = $null
    if (-not $stateBucketExists -and $IncludeAi -and -not $Fresh) {
        $localRestore = Get-LatestLocalRestorableSnapshot -BackupRoot (Join-Path $PSScriptRoot '.local\aws-backups') -Account $account -Region $config.Region
        if ($null -eq $localRestore) {
            throw 'No verified local DB backup exists. To intentionally create empty databases, use deploy.ps1 -IncludeAi -Fresh.'
        }
    }
    $stateBucket = Initialize-StateBucket -Config $config -Account $account
    Initialize-TerraformStack -Directory $aiDir -Stack 'ai-bridge' -Bucket $stateBucket -Config $config
    Initialize-TerraformStack -Directory $PSScriptRoot -Stack 'app' -Bucket $stateBucket -Config $config
    $aiAddresses = @()
    if (Test-StateObjectExists -Config $config -Bucket $stateBucket -Stack 'ai-bridge') {
        $aiAddresses = @(Get-TerraformManagedAddresses -Directory $aiDir)
    }
    $aiExists = $aiAddresses -contains 'aws_instance.ai'
    if (-not $aiExists -and $aiAddresses.Count -gt 0) {
        throw 'Partial AI Terraform state exists. Resolve it before deploying.'
    }
    if (-not $aiExists -and -not $IncludeAi) {
        throw 'AI infrastructure is absent. Use deploy.ps1 -IncludeAi to recreate both stacks.'
    }
    if ($aiExists) {
        $existingAiInstance = Get-TerraformOutput -Directory $aiDir -Name 'instance_id'
        $aiState = & aws ec2 describe-instances --instance-ids $existingAiInstance --query 'Reservations[0].Instances[0].State.Name' --output text --profile $config.Profile --region $config.Region
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect existing AI instance state.' }
        if ($aiState -ne 'running') {
            throw "AI instance is $aiState, not running. The normal deploy command will not start or replace it; inspect AWS before retrying."
        }
    }
    $managedAddresses = @()
    if (Test-StateObjectExists -Config $config -Bucket $stateBucket -Stack 'app') {
        $managedAddresses = @(Get-TerraformManagedAddresses -Directory $PSScriptRoot)
    }
    $appExists = $managedAddresses -contains 'aws_instance.app'
    if ($appExists -and -not $aiExists) { throw 'App Terraform state exists while AI state is absent. Resolve this inconsistent deployment first.' }
    if ($Fresh -and $appExists) { throw '-Fresh only applies when the app stack has been fully destroyed.' }
    if (-not $appExists -and $managedAddresses.Count -gt 0) {
        throw 'Partial app Terraform state exists. Resolve it before attempting backup restore or fresh deployment.'
    }
    $restore = $null
    if (-not $appExists -and -not $Fresh) {
        $restore = Get-LatestRestorableSnapshot -Config $config -Account $account -StateBucket $stateBucket -Workspace $workspace
        if ($null -eq $restore -and $IncludeAi) {
            $restore = if ($null -ne $localRestore) { $localRestore } else {
                Get-LatestLocalRestorableSnapshot -BackupRoot (Join-Path $PSScriptRoot '.local\aws-backups') -Account $account -Region $config.Region
            }
            if ($null -ne $restore) { Write-Host "Using verified local snapshot $($restore.Id) after full AWS teardown." }
        }
    }
    if (-not $appExists -and $null -eq $restore -and -not $Fresh) {
        throw 'No verified DB backup exists. To intentionally create empty databases, use -Fresh (and -IncludeAi if AI is absent).'
    }
    if ($null -ne $restore) { Write-Host "Will automatically restore both DBs from snapshot $($restore.Id)." }
    elseif (-not $appExists) { Write-Host 'No backup selected; a fresh database will be created.' }

    $baseVars = @("-var=region=$($config.Region)", "-var=profile=$($config.Profile)", "-var=account_id=$account")
    Invoke-TerraformApply -Directory $aiDir -Variables ($baseVars + @("-var=instance_type=$($config.AiInstanceType)")) -Workspace $workspace
    $aiInstance = Get-TerraformOutput -Directory $aiDir -Name 'instance_id'
    $aiIp = Get-TerraformOutput -Directory $aiDir -Name 'private_ip'
    Wait-SsmOnline -Config $config -InstanceId $aiInstance

    Set-SecureParameter -Config $config -Name '/hansul/ai/token' -Value (New-Secret) -Workspace $workspace -OnlyIfMissing
    $bridgeSource = Get-GzipBase64 -Path (Join-Path $aiDir 'responses_bridge.py')
    $serviceSource = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $aiDir 'hansul-ai.service')))
    $aiCommands = @(
        'set -euo pipefail',
        'cloud-init status --wait >/dev/null',
        'install -d -m 700 -o ec2-user -g ec2-user /home/ec2-user/hansul-ai',
        "printf '%s' '$bridgeSource' | base64 -d | gzip -d > /home/ec2-user/hansul-ai/responses_bridge.py",
        'chown ec2-user:ec2-user /home/ec2-user/hansul-ai/responses_bridge.py',
        "printf '%s' '$serviceSource' | base64 -d > /etc/systemd/system/hansul-ai.service",
        "printf 'HANSUL_AI_BRIDGE_LISTEN=%s`nHANSUL_AI_BRIDGE_TOKEN=%s`n' '$aiIp' `"`$(aws ssm get-parameter --name /hansul/ai/token --with-decryption --query Parameter.Value --output text --region $($config.Region))`" > /etc/hansul-ai.env",
        'chmod 600 /etc/hansul-ai.env',
        'systemctl daemon-reload',
        'systemctl enable --now hansul-ai.service',
        'systemctl restart hansul-ai.service',
        'systemctl is-active --quiet hansul-ai.service'
    )
    Invoke-SsmShell -Config $config -InstanceId $aiInstance -Commands $aiCommands -Workspace $workspace -TimeoutSeconds 1200 | Out-Null

    $appVars = $baseVars + @(
        "-var=app_instance_type=$($config.AppInstanceType)",
        "-var=ai_vpc_id=$(Get-TerraformOutput -Directory $aiDir -Name 'vpc_id')",
        "-var=ai_vpc_cidr=$(Get-TerraformOutput -Directory $aiDir -Name 'vpc_cidr')",
        "-var=ai_route_table_id=$(Get-TerraformOutput -Directory $aiDir -Name 'route_table_id')",
        "-var=ai_security_group_id=$(Get-TerraformOutput -Directory $aiDir -Name 'security_group_id')"
    )
    Invoke-TerraformApply -Directory $PSScriptRoot -Variables $appVars -Workspace $workspace
    $appInstance = Get-TerraformOutput -Directory $PSScriptRoot -Name 'instance_id'
    $appIp = Get-TerraformOutput -Directory $PSScriptRoot -Name 'public_ip'
    $deployBucket = Get-TerraformOutput -Directory $PSScriptRoot -Name 'deployment_bucket'
    Wait-SsmOnline -Config $config -InstanceId $appInstance

    $restoreId = 'none'
    $hansulHash = 'none'
    $keycloakHash = 'none'
    if ($null -ne $restore) {
        $restoreId = $restore.Id
        $hansulHash = $restore.Hashes['hansul']
        $keycloakHash = $restore.Hashes['keycloak']
        foreach ($database in @('hansul','keycloak')) {
            $file = Join-Path $restore.Directory "$database.dump"
            $key = "restores/$restoreId/$database.dump"
            Invoke-Checked aws @('s3','cp',$file,"s3://$deployBucket/$key",'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') "Could not stage $database restore file."
            & aws s3api head-object --bucket $deployBucket --key $key --profile $config.Profile --region $config.Region --output json 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not verify staged $database restore file." }
        }
    }

    Set-SecureParameter -Config $config -Name '/hansul/app/db-password' -Value (New-Secret) -Workspace $workspace -OnlyIfMissing
    Set-SecureParameter -Config $config -Name '/hansul/app/kc-admin-password' -Value (New-Secret) -Workspace $workspace -OnlyIfMissing
    Set-SecureParameter -Config $config -Name '/hansul/app/cloudflare-crt' -Value ([IO.File]::ReadAllText($certPath)) -Workspace $workspace
    Set-SecureParameter -Config $config -Name '/hansul/app/cloudflare-key' -Value ([IO.File]::ReadAllText($keyPath)) -Workspace $workspace

    $releaseId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $stage = Join-Path $workspace 'release'
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $stage 'server.jar')
    foreach ($file in @('Dockerfile','compose.production.yaml','origin-nginx.conf','hansul-realm.production.json','init-keycloak-db.sh','remote-deploy.sh')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $stage $file)
    }
    $archive = Join-Path $workspace "$releaseId.tar.gz"
    & tar -czf $archive -C $stage .
    if ($LASTEXITCODE -ne 0) { throw 'Could not package release.' }
    Invoke-Checked aws @('s3','cp',$archive,"s3://$deployBucket/releases/$releaseId.tar.gz",'--profile',$config.Profile,'--region',$config.Region,'--only-show-errors') 'Could not upload release artifact.'
    $command = "aws s3 cp s3://$deployBucket/releases/$releaseId.tar.gz /tmp/hansul-bootstrap.tar.gz --region $($config.Region) --only-show-errors && mkdir -p /opt/hansul/releases/$releaseId && tar -xzf /tmp/hansul-bootstrap.tar.gz -C /opt/hansul/releases/$releaseId && bash /opt/hansul/releases/$releaseId/remote-deploy.sh $releaseId $deployBucket $($config.Region) $aiIp $restoreId $hansulHash $keycloakHash"
    Invoke-SsmShell -Config $config -InstanceId $appInstance -Commands @('set -euo pipefail','cloud-init status --wait >/dev/null',$command) -Workspace $workspace -TimeoutSeconds 1800 | Out-Null

    # Origin is verified before touching DNS. Cloudflare Origin CA is intentionally
    # not treated as a publicly trusted direct-origin certificate.
    Update-Dns -Token $cf['CLOUDFLARE_API_TOKEN'] -Context $dns -Domain $config.Domain -Ip $appIp
    Test-PublicEndpoint -Url 'https://hansul.example.com/actuator/health'
    Test-PublicEndpoint -Url 'https://hansul.example.com/realms/hansul/.well-known/openid-configuration'
    Write-Host "DEPLOYED https://$($config.Domain)/ (release $releaseId, app $appInstance, AI $aiInstance)"
    Write-Host 'AI cooking turns additionally require one-time Codex CLI device login on the isolated AI host.'
}
finally {
    if (Test-Path -LiteralPath $workspace) { Remove-Item -LiteralPath $workspace -Recurse -Force }
}
