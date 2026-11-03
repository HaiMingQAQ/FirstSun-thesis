param(
    [ValidateSet('create','start','stop','status','rebuild')][string]$Action = 'status',
    [string]$EnvironmentRoot = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$root = if ($EnvironmentRoot) { [IO.Path]::GetFullPath($EnvironmentRoot) } else { $repo }
$envFile = Join-Path $root '.env'
$composeFile = Join-Path $repo 'deploy/docker-compose.local.yml'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Missing FirstSun .env; restore the existing configuration rather than creating a new database.' }
$values = @{}
foreach ($line in [IO.File]::ReadAllLines($envFile)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
$previous = @{}
$managedKeys = @($values.Keys) + @('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','BUILDX_BUILDER')
$lock = $null
function Invoke-Docker([string[]]$arguments) {
    & docker --context desktop-linux @arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed with exit code $LASTEXITCODE." }
}
function Invoke-Compose([string[]]$arguments) {
    Invoke-Docker (@('compose','--project-name','firstsun','--env-file',$envFile,'--file',$composeFile) + $arguments)
}
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $root '.local') | Out-Null
    $lock = [IO.File]::Open((Join-Path $root '.local/docker.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    foreach ($key in $managedKeys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $null, 'Process')
    }
    foreach ($key in $values.Keys) { [Environment]::SetEnvironmentVariable($key, [string]$values[$key], 'Process') }
    $env:DOCKER_CONTEXT = 'desktop-linux'
    $env:BUILDX_BUILDER = 'default'
    $endpoint = & docker --context desktop-linux context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0 -or $endpoint -notmatch '^npipe:/+\./pipe/[^/]+$') { throw 'Expected the local Docker Desktop Linux engine.' }
    Invoke-Compose @('config','--quiet')
    switch ($Action) {
        'create' { Invoke-Compose @('create','--no-build','--pull','never') }
        'start' { Invoke-Compose @('up','-d','--no-build','--pull','never','--wait','--wait-timeout','300') }
        'stop' { Invoke-Compose @('stop','--timeout','30') }
        'status' { Invoke-Compose @('ps','--all') }
        'rebuild' {
            $customer = Join-Path $repo 'customer-web'
            Push-Location $customer
            try {
                & node './node_modules/vite/bin/vite.js' build
                if ($LASTEXITCODE -ne 0) { throw 'Customer web build failed; existing containers and data remain intact.' }
            } finally { Pop-Location }
            Invoke-Compose @('build','backend','admin-ui','customer-web')
            Invoke-Compose @('up','-d','--no-build','--pull','never','--wait','--wait-timeout','300')
        }
    }
    exit 0
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    if ($lock) { $lock.Dispose() }
}
