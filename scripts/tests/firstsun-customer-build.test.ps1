param([string]$Runner = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $Runner) { $Runner = Join-Path $repo 'scripts/firstsun.ps1' }
. (Join-Path $repo 'scripts/firstsun-checks.ps1')
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Runner, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Launcher parse failed' }
# Execute the actual Vite build block with a stub compiler, without running Compose.
$block = $ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.TryStatementAst] -and
    $node.Body.Extent.Text.Contains("& node './node_modules/vite/bin/vite.js'")
}, $true) | Sort-Object { $_.Extent.Text.Length } | Select-Object -First 1
if (-not $block) { throw 'Customer build block missing' }
$build = [scriptblock]::Create($block.Extent.Text)
function node {
    $script:captured = @{
        arguments = $args -join ' '
        tenant = $env:FIRSTSUN_TENANT_ID
        backend = $env:FIRSTSUN_BACKEND_URL
        login = $env:FIRSTSUN_LOCAL_TEST_LOGIN
    }
    $global:LASTEXITCODE = 0
}
$keys = @('FIRSTSUN_TENANT_ID', 'FIRSTSUN_BACKEND_URL', 'FIRSTSUN_LOCAL_TEST_LOGIN')
$original = @{}
foreach ($key in $keys) { $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$cases = @(
    @{ config=@{ FIRSTSUN_MOCK_PAYMENT_ENABLED='true' }; mode=$true; backend='http://127.0.0.1:30000'; login='' },
    @{ config=@{ FIRSTSUN_LOCAL_TEST_LOGIN='false'; FIRSTSUN_MOCK_PAYMENT_ENABLED='true' }; mode=$true; backend='http://127.0.0.1:30000'; login='false' },
    @{ config=@{ FIRSTSUN_LOCAL_TEST_LOGIN='true' }; mode=$true; backend='http://127.0.0.1:30000'; login='true' },
    @{ config=@{ FIRSTSUN_MOCK_PAYMENT_ENABLED='true'; FIRSTSUN_BACKEND_URL='http://localhost:31000' }; mode=$true; backend='http://localhost:31000'; login='' },
    @{ config=@{}; mode=$false; backend=''; login='' }
)
try {
    foreach ($case in $cases) {
        $env:FIRSTSUN_LOCAL_TEST_LOGIN = 'true'
        $env:FIRSTSUN_BACKEND_URL = 'http://ambient.invalid'
        $env:FIRSTSUN_TENANT_ID = '163'
        $values = $case.config.Clone()
        $values.FIRSTSUN_TENANT_ID = '901'
        $values.BACKEND_PORT = '30000'
        $ports = Get-FirstSunPorts $values
        $vitePrevious = @{}
        Push-Location (Join-Path $repo 'customer-web')
        & $build
        if (($captured.arguments.Contains('--mode local-acceptance')) -ne $case.mode) { throw 'Wrong Vite mode' }
        if ($captured.tenant -ne '901') { throw 'Wrong tenant' }
        if ([string]$captured.backend -ne $case.backend) { throw 'Wrong backend origin' }
        if ([string]$captured.login -ne $case.login) { throw 'Test login flag changed' }
        if ($env:FIRSTSUN_LOCAL_TEST_LOGIN -ne 'true' -or $env:FIRSTSUN_TENANT_ID -ne '163' -or
            $env:FIRSTSUN_BACKEND_URL -ne 'http://ambient.invalid') { throw 'Build environment not restored' }
    }
    Write-Output 'PASS customer build mode, independent login flag, tenant, local port fallback and environment restoration (5 cases).'
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $original[$key], 'Process') }
}
