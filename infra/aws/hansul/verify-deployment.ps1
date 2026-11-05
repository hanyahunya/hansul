Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'deployment-common.ps1')

$config = Get-DeploymentConfig
$account = Get-AwsIdentity -Config $config
$instanceId = Get-TerraformOutput -Directory $PSScriptRoot -Name 'instance_id'
$bucket = Get-TerraformOutput -Directory $PSScriptRoot -Name 'deployment_bucket'
$workspace = New-DeploymentWorkspace

try {
    $commands = @(
        'docker exec hansul-postgres psql -U hansul -d keycloak -Atc "SELECT ''REALMS='' || count(*) FROM realm"',
        'docker exec hansul-postgres psql -U hansul -d hansul -Atc "SELECT ''MIGRATIONS='' || count(*) FROM flyway_schema_history"',
        'docker ps --filter name=hansul- --format "{{.Names}} {{.Status}}"'
    )
    $commandId = Invoke-SsmShell -Config $config -InstanceId $instanceId -Commands $commands -Workspace $workspace -TimeoutSeconds 90
    & aws ssm get-command-invocation --command-id $commandId --instance-id $instanceId --query '[Status,StandardOutputContent,StandardErrorContent]' --output json --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect deployment verification result.' }
    & aws s3 ls "s3://$bucket/backups/" --recursive --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect database backups.' }
    & curl.exe -fsS --max-time 20 -w ' HTTP=%{http_code}' "https://$($config.Domain)/actuator/health"
    if ($LASTEXITCODE -ne 0) { throw 'Public health check failed.' }
}
finally {
    Remove-Item -LiteralPath $workspace -Recurse -Force
}
