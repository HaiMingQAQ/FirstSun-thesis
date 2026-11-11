$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$errors = $null; $tokens = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'scripts/local-migration-helpers.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
foreach ($name in @('Migrate','Configure-LocalFiles','File-Sha256','Quote-Argument','Protect-Output','Write-Step')) {
    $function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    Invoke-Expression $function.Extent.Text
}
$repo = Join-Path $root '.local/tests-local-environment'
New-Item -ItemType Directory -Force (Join-Path $repo 'deploy') | Out-Null
[IO.File]::WriteAllText((Join-Path $repo '01.sql'), 'SELECT 1;')
[IO.File]::WriteAllText((Join-Path $repo '02.sql'), 'SELECT 2;')
[IO.File]::WriteAllText((Join-Path $repo 'deploy/local-migrations.json'), '[{"id":1,"path":"01.sql"},{"id":2,"path":"02.sql"}]')
$hash1 = File-Sha256 (Join-Path $repo '01.sql')
$hash2 = File-Sha256 (Join-Path $repo '02.sql')
function Sql([string]$query) {
    $script:queries.Add($query)
    if ($query -like '*table_name=*') { return $script:ledger }
    if ($query -like '*FROM information_schema.tables*') { return $script:tables }
    if ($query -like 'SELECT id,sha256,status*') { return $script:rows }
    if ($query -like 'SELECT name FROM infra_file_config*') { return $script:fileName }
    return ''
}
function Reset-Fixture {
    $script:queries = New-Object 'Collections.Generic.List[string]'
    $script:ledger = '1'; $script:tables = '0'; $script:rows = ''
}
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
function Must-Fail([string]$pattern) {
    try { Migrate; throw 'Expected migration failure' }
    catch { Assert ($_.Exception.Message -like $pattern) "Wrong failure: $($_.Exception.Message)" }
}
Reset-Fixture
$script:ledger = '0'; $script:tables = '2'
Must-Fail '*no migration ledger*'
Assert (-not ($queries | Where-Object { $_ -match 'CREATE|INSERT|UPDATE' })) 'Unmanaged DB was changed'
Reset-Fixture
$script:rows = "1`t$hash1`tRUNNING"
Must-Fail '*interrupted*'
Reset-Fixture
$script:rows = "1`t$('a' * 64)`tAPPLIED"
Must-Fail '*changed*'
Assert (-not ($queries | Where-Object { $_ -match '^INSERT|^UPDATE' })) 'Changed SQL wrote data'
Reset-Fixture
$script:rows = "2`t$hash2`tAPPLIED"
Must-Fail '*contiguous prefix*'
Reset-Fixture
$script:rows = "1`t$hash1`tAPPLIED`n2`t$hash2`tAPPLIED"
Migrate
Assert ($queries.Count -eq 2) 'Already applied SQL or seeds were replayed'
Reset-Fixture
$script:rows = "1`t$hash1`tAPPLIED"
Migrate
Assert ($queries -contains 'SELECT 2;') 'Pending migration not executed'
Assert (-not ($queries -contains 'SELECT 1;')) 'Applied migration was replayed'
Assert ($queries[2] -match "'RUNNING'") 'No persistent interrupted-import marker'
Assert ($queries[4] -match "status='APPLIED'") 'Success not recorded after SQL'
Reset-Fixture
[IO.File]::WriteAllText((Join-Path $repo '02.sql'), 'USE old_course; SELECT 2;')
Must-Fail '*changes database scope*'
Assert (-not ($queries | Where-Object { $_ -match '^INSERT|^UPDATE' })) 'Unsafe scope wrote business data'
$config = @{ MYSQL_PASSWORD='secret-example-123'; PHARMACY_AI_API_KEY='private-example-456' }
Assert ((Protect-Output 'secret-example-123 private-example-456') -eq '[REDACTED] [REDACTED]') 'Credential redaction failed'
Assert ((Quote-Argument 'a b') -eq '"a b"') 'Argument quoting failed'

$stepCount = 5
$stepOutput = Write-Step 3 'Check service health' 6>&1 | Out-String
Assert ($stepOutput.Contains('[3/5] Check service health')) 'Stage number or label missing'
Assert ($stageLabel -eq 'Check service health') 'Failure summary cannot identify the active stage'
$sourceBytes = [IO.File]::ReadAllBytes((Join-Path $root 'scripts/local-migration-helpers.ps1'))
Assert ($sourceBytes[0] -eq 239 -and $sourceBytes[1] -eq 187 -and $sourceBytes[2] -eq 191) 'Chinese source must be readable by Windows PowerShell 5.1'


Reset-Fixture
$config['BACKEND_PORT'] = '28080'; $script:fileName = ''
Configure-LocalFiles
Assert ($queries[1] -match 'storage,master,config' -and $queries[1] -match '127.0.0.1:28080') 'Local files do not use loopback DB storage'
Assert ($queries[2] -match 'master=\(id=45000043\)') 'Seeded S3 remains the upload master'
Reset-Fixture
$script:fileName = 'Other configuration'
try { Configure-LocalFiles; throw 'Expected occupied configuration failure' }
catch { Assert ($_.Exception.Message -like '*occupied*') 'Wrong local file configuration failure' }
Assert ($queries.Count -eq 1) 'Occupied file configuration was overwritten'

# The 01-44 migration mapping is frozen independently of daily startup.
$manifest = Get-Content (Join-Path $root 'deploy/local-migrations.json') -Raw | ConvertFrom-Json
Assert ($manifest.Count -eq 47) 'Incomplete baseline plus readiness chain'
for ($i=0; $i -lt 47; $i++) {
    Assert ($manifest[$i].id -eq ($i+1)) "Wrong SQL order at $i"
    Assert (Test-Path -LiteralPath (Join-Path $root $manifest[$i].path)) "Missing SQL at $i"
}
$first44 = ConvertTo-Json -InputObject @($manifest | Select-Object -First 44) -Compress
$hasher = [Security.Cryptography.SHA256]::Create()
$baselineSha256 = ([BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($first44)))).Replace('-','').ToLower()
Assert ($baselineSha256 -eq 'c2cd5297be8d606548b54c23d2a546f2fc39efc60452a79200d205c0be05fd27') 'Historical 01-44 mapping changed'
$readiness = @('20261102_a_firstsun_consultation_grant.sql','20261102_b_member_status_labels.sql','20261102_c_unrelated_ai_menus_disable.sql')
for ($i=0; $i -lt 3; $i++) {
    Assert ($manifest[44+$i].id -eq (45+$i) -and $manifest[44+$i].path -eq ('sql/migrations/' + $readiness[$i])) "Wrong appended readiness SQL at $i"
}
$compose = Get-Content (Join-Path $root 'deploy/docker-compose.local.yml') -Raw | ConvertFrom-Json
Assert ($compose.name -eq 'firstsun') 'Wrong Compose project'
Assert ($compose.services.mysql.environment.TZ -eq 'Asia/Shanghai') 'MySQL/backend time zone mismatch'
$rootCompose = Get-Content (Join-Path $root 'docker-compose.yml') -Raw | ConvertFrom-Json
Assert ($rootCompose.name -eq 'firstsun' -and $rootCompose.include[0].path -eq './deploy/docker-compose.local.yml') 'Root Compose uses a different environment'
$runnerErrors = $null; $runnerTokens = $null
$runnerAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'scripts/firstsun.ps1'), [ref]$runnerTokens, [ref]$runnerErrors)
Assert (-not $runnerErrors.Count) 'Unified entry failed to parse'
$legacyCalls = $runnerAst.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -in @('Sql','Migrate','Configure-LocalFiles') }, $true)
Assert ($legacyCalls.Count -eq 0) 'Daily entry can write business data or replay migrations'
foreach ($volume in @('mysql-data','redis-data','backend-logs')) {
    Assert ($compose.volumes.$volume.external -eq $true) "Missing existing volume must not create an empty $volume"
}
Assert ($compose.services.backend.environment.ARGS.Contains('--firstsun.miniapp.mock-payment-enabled=${FIRSTSUN_MOCK_PAYMENT_ENABLED:-false}')) 'Mock payment is not off by default'
Assert (-not ($compose.services.mysql.volumes -match 'initdb')) 'MySQL init hooks bypass ledger'
foreach ($service in @('mysql','redis','backend','admin-ui','customer-web')) {
    Assert ($compose.services.$service.ports[0] -like '127.0.0.1:*') "Non-local bind for $service"
    Assert ([bool]$compose.services.$service.healthcheck) "No healthcheck for $service"
}
Write-Host 'PASS: migration safety, pending-only imports, secret redaction, unchanged 01-44 plus 45-47 order and five-service unified Compose boundaries; daily entry does not invoke migration helpers.'
