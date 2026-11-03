param([ValidateSet('create','start','stop','status','rebuild')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$parent = Split-Path $repo -Parent
$sharedRunner = Join-Path $parent 'firstsun.ps1'
if ((Test-Path -LiteralPath $sharedRunner) -and (Test-Path -LiteralPath (Join-Path $parent 'compose.yaml'))) {
    & $sharedRunner -Action $Action
} else {
    & (Join-Path $PSScriptRoot 'firstsun.ps1') -Action $Action
}
exit $LASTEXITCODE
