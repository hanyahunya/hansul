Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'deployment-common.ps1')

$config = Get-DeploymentConfig
$appDirectory = $PSScriptRoot
$appInstanceId = Get-TerraformOutput -Directory $appDirectory -Name 'instance_id'
$aiAddress = Get-TerraformOutput -Directory (Join-Path $PSScriptRoot '..\ai-bridge') -Name 'private_ip'
$probePath = Join-Path $PSScriptRoot 'ai-probe.json'
$probeBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($probePath))
$workspace = New-DeploymentWorkspace

try {
    $commands = @(
        "printf '%s' '$probeBase64' | base64 -d > /tmp/hansul-ai-probe.json",
        'token=$(aws ssm get-parameter --name /hansul/ai/token --with-decryption --query Parameter.Value --output text --region ap-northeast-2)',
        "curl -sS --max-time 175 -o /tmp/hansul-ai-probe-result.json -w 'AI_HTTP=%{http_code}`n' -H `"Authorization: Bearer `$token`" -H 'Content-Type: application/json' --data-binary @/tmp/hansul-ai-probe.json http://${aiAddress}:8765/v1/responses",
        "jq -r '.status, (.output[0].content[0].text // .error.message // `"no output`")' /tmp/hansul-ai-probe-result.json"
    )
    $commandId = Invoke-SsmShell -Config $config -InstanceId $appInstanceId -Commands $commands -Workspace $workspace -TimeoutSeconds 205
    & aws ssm get-command-invocation --command-id $commandId --instance-id $appInstanceId --query '[Status,StandardOutputContent,StandardErrorContent]' --output json --profile $config.Profile --region $config.Region
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect AI probe result.' }
}
finally {
    Remove-Item -LiteralPath $workspace -Recurse -Force
}
