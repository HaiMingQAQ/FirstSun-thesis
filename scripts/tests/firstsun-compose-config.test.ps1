$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $repo 'scripts/firstsun-checks.ps1')
$composeFile = Join-Path $repo 'deploy/docker-compose.local.yml'
$envFile = [IO.Path]::GetTempFileName()
$previous = @{}
$ambient = @{ MYSQL_PASSWORD='ambient-fixture'; REDIS_PASSWORD='ambient-redis'; FIRSTSUN_PROJECT_NAME='firstsun'
    FIRSTSUN_MOCK_PAYMENT_ENABLED='true'; COMPOSE_PROJECT_NAME='ambient-project' }
$original = @{}
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }

function Get-ComposeConfig {
    $render = & docker compose --env-file $envFile --file $composeFile config --format json 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic Compose config failed.' }
    try { return ($render -join [Environment]::NewLine) | ConvertFrom-Json }
    catch { throw 'Synthetic Compose config did not return JSON.' }
}

try {
    foreach ($key in $ambient.Keys) {
        $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $ambient[$key], 'Process')
    }
    Clear-FirstSunComposeEnvironment $composeFile @{} $previous
    $fixture = @'
MYSQL_ROOT_PASSWORD=root-fixture
MYSQL_DATABASE=firstsun_demo_config_test
MYSQL_USER=thesis
MYSQL_PASSWORD='mysql $literal # test'
REDIS_PASSWORD='redis $literal # test'
'@
    [IO.File]::WriteAllText($envFile, "$fixture" + [Environment]::NewLine)
    $daily = Get-ComposeConfig
    Assert ((Get-Content -LiteralPath $composeFile -Raw) -notmatch '\$\{COMPOSE_PROJECT_NAME') 'Compose resource names depend on a second project variable.'
    Assert ($daily.name -ceq 'firstsun') 'Default daily project changed.'
    $legacy = Get-FirstSunVolumes @{} 'firstsun'
    Assert ($daily.volumes.'mysql-data'.name -ceq $legacy.FIRSTSUN_MYSQL_VOLUME -and
        $daily.volumes.'redis-data'.name -ceq $legacy.FIRSTSUN_REDIS_VOLUME -and
        $daily.volumes.'backend-logs'.name -ceq $legacy.FIRSTSUN_LOGS_VOLUME) 'Legacy daily volumes changed.'
    [IO.File]::AppendAllText($envFile, 'FIRSTSUN_PROJECT_NAME=firstsun-config-probe' + [Environment]::NewLine)
    $demo = Get-ComposeConfig
    Assert ($demo.name -ceq 'firstsun-config-probe') 'Project name was not taken from the env-file.'
    foreach ($service in 'embedding','backend','admin-ui','customer-web') {
        Assert ($demo.services.$service.image -ceq ('firstsun-config-probe-{0}:local' -f $service)) "Wrong image namespace for $service."
    }
    Assert ($demo.networks.default.name -ceq 'firstsun-config-probe-network') 'Network did not inherit the project.'
    Assert ($demo.volumes.'qdrant-data'.name -ceq 'firstsun-config-probe_qdrant_data') 'Qdrant volume did not inherit the project.'
    foreach ($volume in 'mysql-data','redis-data','backend-logs') {
        Assert ($demo.volumes.$volume.external -and $demo.volumes.$volume.name -ceq "firstsun-config-probe_$volume") "Wrong external volume $volume."
    }
    $backend = $demo.services.backend
    Assert ($backend.environment.SPRING_CONFIG_IMPORT -ceq 'configtree:/run/secrets/') 'Missing Spring configtree import.'
    Assert ($backend.environment.ARGS -match '--firstsun.miniapp.mock-payment-enabled=false') 'Ambient payment toggle bypassed the safe default.'
    $backendInspectFields = @($backend.environment,$backend.command) | ConvertTo-Json -Depth 8
    Assert ($backendInspectFields -notmatch 'master.password|slave.password|spring.data.redis.password|MYSQL_PASSWORD|REDIS_PASSWORD|ambient-fixture|ambient-redis|literal') 'Backend Env or Cmd contains a database credential.'
    # The rendered model can escape literal dollars for use as a Compose file.
    Assert ($demo.services.mysql.environment.MYSQL_PASSWORD.Replace('$$', '$') -ceq 'mysql $literal # test' -and
        $demo.services.redis.environment.REDIS_PASSWORD.Replace('$$', '$') -ceq 'redis $literal # test') 'Passwords did not come from Compose env-file parsing.'
    $secretEnv = @(& docker compose --env-file $envFile --file $composeFile config --environment 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic Compose project environment failed.' }
    Assert ($secretEnv -ccontains 'MYSQL_PASSWORD=mysql $literal # test' -and
        $secretEnv -ccontains 'REDIS_PASSWORD=redis $literal # test') 'Secret source variables are missing from the Compose project environment.'
    $targets = @{
        'spring.datasource.dynamic.datasource.master.password' = 'MYSQL_PASSWORD'
        'spring.datasource.dynamic.datasource.slave.password' = 'MYSQL_PASSWORD'
        'spring.data.redis.password' = 'REDIS_PASSWORD'
    }
    Assert ($backend.secrets.Count -eq 3 -and @($backend.secrets.source | Select-Object -Unique).Count -eq 3) 'Secret sources were collapsed.'
    foreach ($secret in $backend.secrets) {
        Assert ([string]$secret.mode -match '^(0400|256)$') 'Backend secret mode must be 0400.'
        Assert ($targets.ContainsKey($secret.target) -and
            $demo.secrets.($secret.source).environment -ceq $targets[$secret.target]) 'Wrong secret target or source variable.'
        $targets.Remove($secret.target)
    }
    Assert ($targets.Count -eq 0) 'A configtree target is missing.'
    [IO.File]::AppendAllText($envFile, 'FIRSTSUN_MYSQL_VOLUME=firstsun-config-probe_custom-db' + [Environment]::NewLine)
    Assert ((Get-ComposeConfig).volumes.'mysql-data'.name -ceq 'firstsun-config-probe_custom-db') 'Explicit volume override failed.'
} finally {
    Restore-FirstSunEnvironment $previous
    Restore-FirstSunEnvironment $original
    Remove-Item -LiteralPath $envFile -Force
}
Write-Host 'PASS: synthetic Compose project, env-file, legacy and demo volumes, and three configtree secret targets (no Docker engine used).'
