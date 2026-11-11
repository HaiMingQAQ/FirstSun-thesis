param(
    [string]$EnvironmentRoot = '',
    [string]$Database = '',
    [securestring]$DemoManagerPassword,
    [securestring]$DemoPharmacistPassword
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $OutputEncoding
$repo = Split-Path -Parent $PSScriptRoot
$root = if ($EnvironmentRoot) { (Resolve-Path -LiteralPath $EnvironmentRoot).Path } else { $repo }
$envFile = Join-Path $root '.env'
$composeFile = Join-Path $repo 'deploy/docker-compose.local.yml'
$config = @{}
foreach ($line in [IO.File]::ReadAllLines($envFile)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $config[$Matches[1]] = $Matches[2] }
}
if (-not $Database) { $Database = $config['MYSQL_DATABASE'] }
if ($Database -cnotmatch '^firstsun_demo_[a-z0-9_]{1,40}$') {
    throw 'Choose a dedicated firstsun_demo_* database. Existing daily databases are outside the demo installer scope.'
}
if (-not $config['BACKEND_PORT']) { $config['BACKEND_PORT'] = '28080' }
if ($config['BACKEND_PORT'] -notmatch '^\d{1,5}$' -or [int]$config['BACKEND_PORT'] -lt 1 -or [int]$config['BACKEND_PORT'] -gt 65535) {
    throw 'BACKEND_PORT must be a valid TCP port.'
}

function Invoke-DemoMySql([string]$statement, [string]$schema = '') {
    $ErrorActionPreference = 'Continue'
    $argsForMySql = @()
    if ($schema) { $argsForMySql = @($schema) }
    $output = $statement | docker --context desktop-linux compose --project-name firstsun --env-file $envFile --file $composeFile `
        exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --binary-mode --default-character-set=utf8mb4 --batch --skip-column-names -uroot "$@"' sh @argsForMySql 2>&1
    if ($LASTEXITCODE -ne 0) {
        $errorCode = [regex]::Match(($output -join "`n"), '\bERROR [0-9]+ \([0-9A-Z]+\)(?: at line [0-9]+)?').Value
        throw "MySQL command failed ($errorCode). Check the current migration or seed step."
    }
    return (($output | Where-Object { $_ -is [string] }) -join "`n").Trim()
}

function Sql([string]$statement) { return Invoke-DemoMySql $statement $Database }

function Get-DemoPasswordHash([securestring]$secret, [string]$username) {
    $ErrorActionPreference = 'Continue'
    if (-not $secret) { $secret = Read-Host "New local password for $username" -AsSecureString }
    $plain = ([pscredential]::new($username, $secret)).GetNetworkCredential().Password
    if ($plain -cnotmatch '^[A-Za-z0-9]{8,16}$') { throw 'Use 8-16 letters or digits for the local demo password.' }
    try {
        $output = $plain | docker --context desktop-linux run --rm --network none -i --entrypoint htpasswd `
            httpd:2.4-alpine -nBi -C 12 $username 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the local bcrypt hash. Check the httpd:2.4-alpine image.' }
        $hash = [regex]::Match(($output -join "`n"), '\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}').Value
        if (-not $hash) { throw 'Password helper returned no bcrypt hash.' }
        return $hash
    } finally { Remove-Variable plain -ErrorAction SilentlyContinue }
}

$exists = Invoke-DemoMySql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$Database';"
if ($exists -eq '0') {
    [void](Invoke-DemoMySql "CREATE DATABASE $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
}
. (Join-Path $PSScriptRoot 'local-migration-helpers.ps1')
Migrate

$tenant = Sql "SELECT CONCAT(id, ':', creator) FROM system_tenant WHERE id=901 OR name='FirstSun-Followup';"
if ($tenant -and $tenant -ne '901:firstsun-demo-seed') { throw 'The demo tenant ID or name is already in use.' }
if (-not $tenant) {
    $manager = Get-DemoPasswordHash $DemoManagerPassword 'demomanager'
    $pharmacist = Get-DemoPasswordHash $DemoPharmacistPassword 'demopharmacist'
    $seed = [IO.File]::ReadAllText((Join-Path $repo 'deploy/sql/seed-local-demo.sql'))
    Configure-LocalFiles
    [void](Sql "SET @demo_manager_hash='$manager', @demo_pharmacist_hash='$pharmacist';`n$seed")
}

$ready = Sql @'
SELECT IF(
  (SELECT COUNT(*) FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND status=0 AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_users WHERE tenant_id=901 AND username IN ('demomanager','demopharmacist') AND status=0 AND deleted=b'0')=2
  AND (SELECT COUNT(*) FROM ph_employee WHERE tenant_id=901 AND store_id=9101 AND user_id IN (95212,95112) AND status=1 AND deleted=b'0')=2
  AND (SELECT COUNT(*) FROM ph_drug WHERE tenant_id=901 AND id IN (9301,9302) AND status=1 AND approve_status=1 AND saleable_online=1 AND deleted=b'0')=2
  AND (SELECT COUNT(*) FROM pay_app a JOIN pay_channel c ON c.app_id=a.id AND c.tenant_id=a.tenant_id
       WHERE a.tenant_id=901 AND a.app_key='firstsun-demo' AND a.deleted=b'0' AND c.code='mock' AND c.deleted=b'0')=1,
  'ready','incomplete');
'@
if ($ready -ne 'ready') { throw 'Demo seed validation failed; inspect the dedicated database before running it again.' }
Write-Host "Demo data ready: $Database / tenant 901 / store 9101. Existing demo passwords were not reset."
