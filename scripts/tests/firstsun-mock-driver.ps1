param([string]$Runner, [string]$EnvironmentRoot, [string]$Action)
function docker {
    $joined = $args -join ' '
    $global:LASTEXITCODE = 0
    if ($joined -match 'context inspect') { return 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    if ($joined -match ' info ') { return 'linux' }
    if ($joined -match 'image inspect') {
        if ($env:MOCK_MISSING_IMAGE -and $joined.Contains($env:MOCK_MISSING_IMAGE)) {
            $global:LASTEXITCODE = 1; return
        }
        return 'sha256:mock-image-id'
    }
    if ($joined -match 'volume inspect') {
        if ($env:MOCK_MISSING_VOLUME -and $joined.Contains($env:MOCK_MISSING_VOLUME)) {
            $global:LASTEXITCODE = 1; return
        }
        return 'mock-volume'
    }
    if ($joined -match ' ps ') {
        if ($env:MOCK_OWNED -eq '1') {
            $health = if ($env:MOCK_HEALTH) { $env:MOCK_HEALTH } else { 'healthy' }
            $state = if ($env:MOCK_STATE) { $env:MOCK_STATE } else { 'running' }
            $row = '{"Service":"backend","State":"' + $state + '","Health":"' + $health + '","Publishers":[{"PublishedPort":28080}]}'
            if ($env:MOCK_PS_ARRAY -eq '1') { return ('[' + $row + ']') }
            return $row
        }
        return ''
    }
    if ($joined -match ' up ' -and $env:MOCK_FAIL_UP -eq '1') {
        $global:LASTEXITCODE = 1; return
    }
    return ''
}
function Get-NetTCPConnection {
    param([string]$State)
    if ($env:MOCK_LISTEN_PORT) { return [pscustomobject]@{ LocalPort=[int]$env:MOCK_LISTEN_PORT } }
    return @()
}
function node {
    $global:LASTEXITCODE = if ($env:MOCK_FAIL_BUILD -eq '1') { 1 } else { 0 }
}
$env:DOCKER_HOST = 'original-mock-host'
$env:BUILDX_BUILDER = 'original-builder'
& $Runner -Action $Action -EnvironmentRoot $EnvironmentRoot
$result = $LASTEXITCODE
if ($env:DOCKER_HOST -ne 'original-mock-host' -or $env:BUILDX_BUILDER -ne 'original-builder') { exit 92 }
exit $result
