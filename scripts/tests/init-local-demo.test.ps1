$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot '../init-local-demo.ps1'
$work = Join-Path ([IO.Path]::GetTempPath()) ('firstsun-demo-test-' + [Guid]::NewGuid().ToString('N'))
$envFile = Join-Path $work '.env'
$previousContext = $env:DOCKER_CONTEXT
New-Item -ItemType Directory -Path $work | Out-Null
[IO.File]::WriteAllText($envFile, "FIRSTSUN_PROJECT_NAME=firstsun-demo-test`nMYSQL_DATABASE=firstsun_demo_test`nMYSQL_PASSWORD=fixture-private-value`n")

function docker {
    $global:LASTEXITCODE = 1
    if ($args[0] -eq 'context') {
        if ($env:FIRSTSUN_TEST_DESKTOP_CONTEXT -eq 'present') {
            $global:LASTEXITCODE = 0
            return 'desktop-linux'
        }
        Write-Error 'context "desktop-linux": context not found'
        return
    }
    if ($env:MYSQL_PASSWORD -or $env:FIRSTSUN_PROJECT_NAME -or $env:COMPOSE_PROJECT_NAME -or
        $args[[Array]::IndexOf($args, '--project-name') + 1] -ne 'firstsun-demo-test' -or
        $args[[Array]::IndexOf($args, '--env-file') + 1] -ne $envFile) {
        Write-Error 'Wrong demo configuration source'
        return
    }
    Write-Error "context `"$($args[1])`": mock connection error (fixture-private-value)"
}

function Assert-Context([string]$expected, [string]$explicit = '') {
    $message = ''
    try { & $runner -EnvironmentRoot $work -DockerContext $explicit }
    catch { $message = $_.Exception.Message }
    if (-not $message.Contains("via Docker context '$expected'") -or
        -not $message.Contains("context `"$expected`": mock connection error") -or
        -not $message.Contains('[REDACTED]') -or $message.Contains('fixture-private-value')) {
        throw "Expected context '$expected' with sanitized native Docker diagnostics."
    }
}

try {
    $savedPassword = $env:MYSQL_PASSWORD
    $savedProject = $env:FIRSTSUN_PROJECT_NAME
    $savedComposeProject = $env:COMPOSE_PROJECT_NAME
    $env:MYSQL_PASSWORD = 'ambient-fixture'
    $env:FIRSTSUN_PROJECT_NAME = 'firstsun'
    $env:COMPOSE_PROJECT_NAME = 'ambient-project'
    Remove-Item Env:DOCKER_CONTEXT -ErrorAction SilentlyContinue
    $env:FIRSTSUN_TEST_DESKTOP_CONTEXT = 'missing'
    Assert-Context 'default'
    if ($IsWindows) {
        $env:FIRSTSUN_TEST_DESKTOP_CONTEXT = 'present'
        Assert-Context 'desktop-linux'
    }
    $env:DOCKER_CONTEXT = 'environment-context'
    Assert-Context 'environment-context'
    Assert-Context 'explicit-context' 'explicit-context'
    if ($env:MYSQL_PASSWORD -ne 'ambient-fixture' -or $env:FIRSTSUN_PROJECT_NAME -ne 'firstsun' -or
        $env:COMPOSE_PROJECT_NAME -ne 'ambient-project') { throw 'Demo initializer did not restore the caller environment.' }
    [IO.File]::WriteAllText($envFile, "MYSQL_DATABASE=firstsun_demo_test`n")
    $message = ''
    try { & $runner -EnvironmentRoot $work -DockerContext 'explicit-context' }
    catch { $message = $_.Exception.Message }
    if ($message -notlike '*requires a non-daily FIRSTSUN_PROJECT_NAME*') { throw 'A separate demo root was allowed to target the daily project.' }
} finally {
    $env:MYSQL_PASSWORD = $savedPassword
    $env:FIRSTSUN_PROJECT_NAME = $savedProject
    $env:COMPOSE_PROJECT_NAME = $savedComposeProject
    $env:DOCKER_CONTEXT = $previousContext
    Remove-Item Env:FIRSTSUN_TEST_DESKTOP_CONTEXT -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $envFile -Force
    Remove-Item -LiteralPath $work
}
Write-Host 'PASS: demo Docker context selection, missing Desktop fallback, and sanitized stderr.'
