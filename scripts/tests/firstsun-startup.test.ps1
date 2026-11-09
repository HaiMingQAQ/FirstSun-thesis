$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $repo 'scripts/firstsun-checks.ps1')
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
$values = @{ MYSQL_ROOT_PASSWORD='fake'; MYSQL_DATABASE='fake'; MYSQL_USER='fake'; MYSQL_PASSWORD='fake'; REDIS_PASSWORD='fake' }
Assert-FirstSunConfig $values
$ports = Get-FirstSunPorts $values
Assert ($ports.backend -eq 28080 -and $ports['customer-web'] -eq 4193) 'Default ports differ'
$values.BACKEND_PORT = '30000'
Assert ((Get-FirstSunPorts $values).backend -eq 30000) 'Port override failed'
$values.BACKEND_PORT = '28081'
try { $null = Get-FirstSunPorts $values; throw 'Expected duplicate port failure' }
catch { Assert ($_.Exception.Message -eq 'DUPLICATE_PORT') 'Duplicate port not rejected' }
$values.BACKEND_PORT = '28080'
Assert (-not (Get-FirstSunConflict $ports @{ backend=@(28080) } @(28080))) 'Own running service mistaken for conflict'
Assert ((Get-FirstSunConflict $ports @{} @(28080)) -eq 'backend (28080)') 'Foreign listener not rejected'
Assert ((Get-FirstSunVersion $null 'abc' @{} $false) -like '版本未知*') 'Legacy image version should be unknown'
$ids = @{ backend='id1'; 'admin-ui'='id2'; 'customer-web'='id3' }
$record = [pscustomobject]@{ commit='abc'; dirty=$false; images=[pscustomobject]$ids }
Assert ((Get-FirstSunVersion $record 'abc' $ids $false) -like '*匹配*') 'Matching image record failed'
$record.dirty = $true
Assert ((Get-FirstSunVersion $record 'abc' $ids $false) -like '版本未知*') 'Dirty rebuild was claimed as commit image'
$record.dirty = $false
Assert ((Get-FirstSunVersion $record 'def' $ids $false) -like '*rebuild-local.bat*') 'Changed commit not flagged'
Assert ((Get-FirstSunVersion $record 'abc' $ids $true) -like '*未证明已打包*') 'Dirty source mistaken for image'
Assert ((Get-FirstSunVersion $record 'abc' @{ backend='different'; 'admin-ui'='id2'; 'customer-web'='id3' } $false) -like '版本未知*') 'Changed image not detected'

$work = Join-Path $repo ('.local/tests-firstsun-startup/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$envFile = Join-Path $work '.env'
[IO.File]::WriteAllText($envFile, "MYSQL_ROOT_PASSWORD=secret-fixture-123`nMYSQL_DATABASE=fake`nMYSQL_USER=fake`nMYSQL_PASSWORD=fake`nREDIS_PASSWORD=fake`n")
$runner = Join-Path $repo 'scripts/firstsun.ps1'
$driver = Join-Path $repo 'scripts/tests/firstsun-mock-driver.ps1'
function Run-Mock([string]$action) {
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $driver -Runner $runner -EnvironmentRoot $work -Action $action 2>&1 | Out-String
    return @{ code=$LASTEXITCODE; output=$output }
}
$env:MOCK_OWNED = '1'; $env:MOCK_LISTEN_PORT = '28080'
$result = Run-Mock 'start'
Assert ($result.code -eq 0 -and $result.output -match 'http://127.0.0.1:28081' -and
    $result.output -match '镜像标签记录' -and $result.output -match '--env-file' -and $result.output -match '--file' -and
    $result.output -match '\[3/4\].*300' -and $result.output -match '\[4/4\]') 'Own-listener start simulation failed'
$env:MOCK_PS_ARRAY = '1'
$result = Run-Mock 'status'
Assert ($result.code -eq 0 -and $result.output -match 'backend：running') 'Compose JSON array status failed'
$env:MOCK_PS_ARRAY = ''
Assert (-not $result.output.Contains('secret-fixture-123')) 'Private env leaked'
Assert (-not (Test-Path (Join-Path $work '.local/firstsun-build.json'))) 'Start wrote rebuild record'
$env:MOCK_STATE = 'exited'
$result = Run-Mock 'start'
Assert ($result.code -ne 0 -and $result.output -match 'PORT_IN_USE:backend \(28080\)') 'Exited container mapping incorrectly exempted foreign listener'
$env:MOCK_STATE = ''
$env:MOCK_OWNED = '0'
$result = Run-Mock 'start'
Assert ($result.code -ne 0 -and $result.output -match 'PORT_IN_USE') 'Foreign port did not fail'
$env:MOCK_MISSING_IMAGE = 'firstsun-backend:local'
$result = Run-Mock 'stop'
Assert ($result.code -eq 0) 'Stop blocked by missing image or busy port'
$result = Run-Mock 'status'
Assert ($result.code -eq 0) 'Status blocked by missing image or busy port'
$env:MOCK_LISTEN_PORT = ''
$result = Run-Mock 'start'
Assert ($result.code -ne 0 -and $result.output -match 'MISSING_IMAGE' -and $result.output -match 'rebuild-local.bat') 'Missing image diagnosis failed'
$env:MOCK_MISSING_IMAGE = ''
$env:MOCK_MISSING_VOLUME = 'firstsun-thesis-local_redis-data'
$result = Run-Mock 'start'
Assert ($result.code -ne 0 -and $result.output -match 'MISSING_VOLUME' -and $result.output -match '不要创建空卷') 'Missing volume diagnosis failed'
$env:MOCK_MISSING_VOLUME = ''
$env:MOCK_FAIL_BUILD = '1'
$result = Run-Mock 'rebuild'
Assert ($result.code -ne 0 -and $result.output -match 'CUSTOMER_BUILD_FAILED') 'Failed build did not return failure stage'
Assert (-not (Test-Path (Join-Path $work '.local/firstsun-build.json'))) 'Failed rebuild wrote success record'
$env:MOCK_FAIL_BUILD = ''
$env:MOCK_FAIL_UP = '1'
$env:MOCK_OWNED = '1'; $env:MOCK_HEALTH = 'unhealthy'
$result = Run-Mock 'rebuild'
Assert ($result.code -ne 0 -and $result.output -match 'unhealthy' -and $result.output -match '当前服务状态') 'Failed Compose up omitted service diagnosis'
Assert (-not (Test-Path (Join-Path $work '.local/firstsun-build.json'))) 'Failed Compose up wrote success record'
$env:MOCK_FAIL_UP = ''
$env:MOCK_HEALTH = ''
$result = Run-Mock 'rebuild'
Assert ($result.code -eq 0) 'Successful rebuild simulation failed'
$saved = Get-Content (Join-Path $work '.local/firstsun-build.json') -Raw | ConvertFrom-Json
Assert ($saved.commit -eq (& git -C $repo rev-parse HEAD)) 'Successful rebuild record commit mismatch'
foreach ($name in @('MOCK_OWNED','MOCK_LISTEN_PORT','MOCK_MISSING_IMAGE','MOCK_MISSING_VOLUME','MOCK_FAIL_BUILD','MOCK_FAIL_UP','MOCK_HEALTH','MOCK_PS_ARRAY','MOCK_STATE')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
foreach ($file in @('scripts/firstsun.ps1','scripts/firstsun-checks.ps1')) {
    $bytes = [IO.File]::ReadAllBytes((Join-Path $repo $file))
    Assert ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) "Missing UTF8 BOM: $file"
    $tokens=$null; $errors=$null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $repo $file),[ref]$tokens,[ref]$errors) | Out-Null
    Assert ($errors.Count -eq 0) "PowerShell parse failure: $file"
}
Write-Host 'PASS: FirstSun startup preflight, ports, version record, failure exit, and UTF8 BOM (offline mocks)'
