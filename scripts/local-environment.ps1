param([ValidateSet('start','rebuild','stop')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $repo '.local'
$envFile = Join-Path $repo 'deploy/.env.thesis.local'
$composeFile = Join-Path $repo 'deploy/docker-compose.local.yml'
$project = 'firstsun-thesis-local'
$dockerContext = 'desktop-linux'
$config = @{}
$lock = $null
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$elapsed = [Diagnostics.Stopwatch]::StartNew()
$stepCount = if ($Action -eq 'stop') { 2 } elseif ($Action -eq 'rebuild') { 6 } else { 5 }
$stageLabel = '准备环境'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$logFile = Join-Path $runtime ("{0}-{1}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Action)

function Write-Step([int]$number, [string]$label) {
    $script:stageLabel = $label
    Write-Host ''
    Write-Host ("  [{0}/{1}] {2}" -f $number,$stepCount,$label) -ForegroundColor Cyan
    Write-Host ('  ' + ('-' * 56)) -ForegroundColor DarkGray
}

function Read-Env([string]$path) {
    $values = @{}
    if (Test-Path -LiteralPath $path) {
        foreach ($line in [IO.File]::ReadAllLines($path)) {
            if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
        }
    }
    return $values
}
function Protect-Output([string]$value) {
    foreach ($key in $config.Keys) {
        if ($key -match 'PASSWORD|SECRET|API_KEY|SMS_CODE' -and $config[$key]) {
            $value = $value.Replace([string]$config[$key], '[REDACTED]')
        }
    }
    return $value
}
function Quote-Argument([string]$value) {
    # Windows CommandLineToArgvW quoting; also works in Windows PowerShell 5.1.
    return '"' + [regex]::Replace([regex]::Replace($value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Docker([string[]]$arguments, [string]$inputText = '', [switch]$Quiet) {
    $p = New-Object Diagnostics.Process
    $p.StartInfo = New-Object Diagnostics.ProcessStartInfo
    $p.StartInfo.FileName = 'docker'
    $p.StartInfo.Arguments = (@('--context', $dockerContext) + $arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    $p.StartInfo.UseShellExecute = $false
    $p.StartInfo.CreateNoWindow = $true
    $p.StartInfo.RedirectStandardOutput = $true
    $p.StartInfo.RedirectStandardError = $true
    $p.StartInfo.RedirectStandardInput = $true
    $p.StartInfo.StandardOutputEncoding = $utf8
    $p.StartInfo.StandardErrorEncoding = $utf8
    # --env-file has lower precedence than inherited process variables in Compose.
    # Remove only this Compose's inputs from the child, keeping the user's shell intact.
    foreach ($match in [regex]::Matches([IO.File]::ReadAllText($composeFile), '\$\{([A-Z][A-Z0-9_]*)')) {
        $p.StartInfo.EnvironmentVariables.Remove($match.Groups[1].Value)
    }
    foreach ($key in @('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','BUILDKIT_HOST','BUILDX_BUILDER')) {
        $p.StartInfo.EnvironmentVariables.Remove($key)
    }
    # Compose can launch buildx as a nested CLI: pass the same verified local context to it.
    $p.StartInfo.EnvironmentVariables['DOCKER_CONTEXT'] = $dockerContext
    # The built-in docker driver uses the selected local engine; context names are not builder names.
    $p.StartInfo.EnvironmentVariables['BUILDX_BUILDER'] = 'default'
    [void]$p.Start()
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    if ($inputText) {
        $inputBytes = $utf8.GetBytes($inputText)
        $p.StandardInput.BaseStream.Write($inputBytes, 0, $inputBytes.Length)
        $p.StandardInput.BaseStream.Flush()
    }
    $p.StandardInput.Close()
    # Animate only interactive output; keep redirected logs readable and credential-free.
    $animate = -not $Quiet -and -not [Console]::IsOutputRedirected
    $frames = @('|','/','-','\'); $frame = 0; $lastNotice = 0
    $waiting = [Diagnostics.Stopwatch]::StartNew()
    while (-not $p.WaitForExit(200)) {
        $seconds = [int]$waiting.Elapsed.TotalSeconds
        if ($animate) {
            Write-Host (("`r  [{0}] 正在处理... 已等待 {1}s" -f $frames[$frame % 4],$seconds).PadRight(40)) -NoNewline -ForegroundColor Yellow
            $frame++
        } elseif (-not $Quiet -and $seconds -ge $lastNotice + 15) {
            Write-Host ("  ... {0}，已等待 {1}s；详细输出将在完成后写入日志。" -f $stageLabel,$seconds) -ForegroundColor DarkGray
            $lastNotice = $seconds
        }
    }
    if ($animate) { Write-Host ("`r" + (' ' * 50) + "`r") -NoNewline }
    $output = Protect-Output ($outTask.Result + $errTask.Result)
    [IO.File]::AppendAllText($logFile, $output + "`r`n", $utf8)
    $display = $output.Trim()
    if ($display.Length -gt 6000) {
        $display = "Full output saved in $logFile`n" + (($display -split "`n" | Select-Object -Last 25) -join "`n")
    }
    if ($p.ExitCode -ne 0) { throw "Docker failed (exit $($p.ExitCode)):`n$display" }
    if (-not $Quiet -and $display) { Write-Host $display -ForegroundColor DarkGray }
    return $outTask.Result.Trim()
}
function Compose([string[]]$arguments, [switch]$Quiet) {
    return Docker (@('compose','--project-name',$project,'--env-file',$envFile,'-f',$composeFile) + $arguments) -Quiet:$Quiet
}
function Sql([string]$query) {
    return Docker @('compose','--project-name',$project,'--env-file',$envFile,'-f',$composeFile,'exec','-T','mysql','sh','-c',
        'export MYSQL_PWD=$MYSQL_PASSWORD; exec mysql -u$MYSQL_USER --default-character-set=utf8mb4 --batch --skip-column-names $MYSQL_DATABASE') ($query + "`n") -Quiet
}
function New-Secret {
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
}
function File-Sha256([string]$path) {
    $hash = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($path)
    try { return ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $hash.Dispose() }
}
function Prepare-Config {
    if (-not (Test-Path -LiteralPath $envFile)) {
        $config['MYSQL_DATABASE'] = 'firstsun_thesis_local'
        $config['MYSQL_USER'] = 'thesis'
        foreach ($key in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','REDIS_PASSWORD')) { $config[$key] = New-Secret }
        $config['MYSQL_PORT'] = '23327'; $config['REDIS_PORT'] = '26397'
        $config['BACKEND_PORT'] = '28080'; $config['ADMIN_PORT'] = '28081'
        # Reuse only explicitly authorized local settings. Never import old DB/host configuration.
        foreach ($source in @((Join-Path $repo '.env'), (Join-Path $repo '.tmp/business-private/ai-source.env'))) {
            $existing = Read-Env $source
            foreach ($key in @('PHARMACY_AI_API_KEY','PHARMACY_AI_BASE_URL','PHARMACY_AI_MODEL',
                'PHARMACY_DEV_SMS_CODE','PHARMACY_WECHAT_TENANT_ID','PHARMACY_WECHAT_APP_ID','WX_MINIAPP_APPID','WX_MINIAPP_SECRET')) {
                if ($existing[$key]) { $config[$key] = $existing[$key] }
            }
        }
        if (-not $config['PHARMACY_DEV_SMS_CODE']) { $config['PHARMACY_DEV_SMS_CODE'] = (New-Secret).Substring(0,8) }
        $content = "# Private local credentials. Do not commit or share.`n" +
            (($config.Keys | Sort-Object | ForEach-Object { "$_=$($config[$_])" }) -join "`n") + "`n"
        [IO.File]::WriteAllText($envFile, $content, $utf8)
        Write-Host 'Created ignored deploy/.env.thesis.local; reused available authorized AI settings.'
    }
    $script:config = Read-Env $envFile
    foreach ($key in @('MYSQL_DATABASE','MYSQL_USER','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','REDIS_PASSWORD',
        'MYSQL_PORT','REDIS_PORT','BACKEND_PORT','ADMIN_PORT','PHARMACY_DEV_SMS_CODE')) {
        if (-not $config[$key]) { throw "Missing $key in deploy/.env.thesis.local" }
    }
    foreach ($key in @('MYSQL_DATABASE','MYSQL_USER')) {
        if ($config[$key] -notmatch '^[a-zA-Z][a-zA-Z0-9_]*$') { throw "Invalid ${key}: use letters, digits and underscore." }
    }
    foreach ($key in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','REDIS_PASSWORD')) {
        if ($config[$key] -notmatch '^[a-zA-Z0-9_-]{16,}$') { throw "Invalid ${key}: at least 16 letters/digits/underscore/hyphen required." }
    }
    $ports = @()
    foreach ($key in @('MYSQL_PORT','REDIS_PORT','BACKEND_PORT','ADMIN_PORT')) {
        $port = 0
        if (-not [int]::TryParse($config[$key], [ref]$port) -or $port -lt 1024 -or $port -gt 65535) { throw "Invalid $key (1024-65535 required)." }
        $ports += $port
    }
    if (($ports | Select-Object -Unique).Count -ne 4) { throw 'All four host ports must be distinct.' }
    [void](Docker @('compose','--project-name',$project,'--env-file',$envFile,'-f',$composeFile,'config','--quiet') -Quiet)
    foreach ($key in @('PHARMACY_AI_API_KEY','PHARMACY_AI_BASE_URL','PHARMACY_AI_MODEL')) {
        if (-not $config[$key]) { Write-Warning "$key is missing: AI consultation is unavailable until configured and restarted." }
    }
}
function Migrate {
    $manifest = Get-Content -LiteralPath (Join-Path $repo 'deploy/local-migrations.json') -Raw | ConvertFrom-Json
    $applied = @{}
    $hasLedger = Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='firstsun_local_migration';"
    if ($hasLedger -eq '0') {
        $tables = Sql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'
        if ($tables -ne '0') { throw 'Existing database has no migration ledger. Refusing initialization: manually audit its migrations; do not delete its volume.' }
        [void](Sql 'CREATE TABLE firstsun_local_migration (id INT PRIMARY KEY, path VARCHAR(255) NOT NULL, sha256 CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, applied_at DATETIME NULL);')
    }
    $rows = Sql 'SELECT id,sha256,status FROM firstsun_local_migration ORDER BY id;'
    foreach ($row in ($rows -split "`n")) {
        if ($row.Trim()) {
            $parts = $row.Trim() -split "`t"
            if ($parts[2] -ne 'APPLIED') { throw "Migration $($parts[0]) was interrupted. Diagnose and recover manually; scripts will not rerun seed data." }
            $applied[[int]$parts[0]] = $parts[1]
        }
    }
    $known = @($manifest | ForEach-Object { [int]$_.id })
    foreach ($id in $applied.Keys) { if ($id -notin $known) { throw "Applied migration $id is missing from manifest." } }
    # Validate the entire plan before writing any business data.
    $seenGap = $false
    foreach ($entry in $manifest) {
        $path = Join-Path $repo $entry.path
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing SQL: $($entry.path)" }
        $hash = File-Sha256 $path
        if ($applied.ContainsKey([int]$entry.id)) {
            if ($seenGap) { throw 'Migration ledger is not a contiguous prefix. Manual audit required.' }
            if ($applied[[int]$entry.id] -ne $hash) { throw "Already applied SQL $($entry.id) changed. Add a new migration instead of replaying it." }
        } else { $seenGap = $true }
        $sql = [IO.File]::ReadAllText($path)
        if ($sql -match '(?im)^\s*(USE\s|(?:CREATE|DROP)\s+DATABASE\s)') { throw "SQL $($entry.id) changes database scope; review required." }
    }
    foreach ($entry in $manifest) {
        if ($applied.ContainsKey([int]$entry.id)) { continue }
        Write-Host ("Applying SQL {0}: {1}" -f $entry.id,$entry.path)
        $path = Join-Path $repo $entry.path
        $hash = File-Sha256 $path
        $safePath = $entry.path.Replace("'", "''")
        [void](Sql "INSERT INTO firstsun_local_migration(id,path,sha256,status) VALUES ($($entry.id),'$safePath','$hash','RUNNING');")
        # A separate persistent RUNNING record survives nontransactional DDL or interrupted imports.
        [void](Sql ([IO.File]::ReadAllText($path)))
        [void](Sql "UPDATE firstsun_local_migration SET status='APPLIED',applied_at=NOW() WHERE id=$($entry.id);")
    }
    Write-Host ("SQL ready: {0} recorded migrations; applied files and demo seeds are skipped." -f $manifest.Count)
}

function Configure-LocalFiles {
    # This dedicated database uses existing DB storage; uploads must not reach seeded S3 endpoints.
    $id = 45000043
    $name = 'FirstSun thesis local DB files'
    $existing = Sql "SELECT name FROM infra_file_config WHERE id=$id;"
    if ($existing -and $existing -ne $name) { throw 'Local file configuration ID is occupied; manual audit required.' }
    $domain = "http://127.0.0.1:$($config['BACKEND_PORT'])"
    $fileConfig = '{"@class":"cn.iocoder.yudao.module.infra.framework.file.core.client.db.DBFileClientConfig","domain":"' + $domain + '"}'
    [void](Sql "INSERT INTO infra_file_config(id,name,storage,master,config,deleted) VALUES ($id,'$name',1,b'0','$fileConfig',b'0') ON DUPLICATE KEY UPDATE storage=1,config=VALUES(config),deleted=b'0';")
    [void](Sql "UPDATE infra_file_config SET master=(id=$id) WHERE deleted=b'0';")
    Write-Host "Local uploaded files: database storage at $domain (no S3 upload)."
}

try {
    $actionName = @{ start='启动本机环境'; rebuild='重新构建并启动'; stop='停止本机服务' }[$Action]
    Write-Host ''
    Write-Host ('  ' + ('=' * 56)) -ForegroundColor Cyan
    Write-Host '  FirstSun  毕设本机控制台' -ForegroundColor White
    Write-Host ("  任务：{0}    环境：firstsun-thesis-local" -f $actionName) -ForegroundColor Cyan
    Write-Host ('  ' + ('=' * 56)) -ForegroundColor Cyan
    Write-Host '  阶段编号表示执行顺序，不是构建百分比。' -ForegroundColor DarkGray
    Write-Host '  首次构建可能较久；失败后窗口保留，数据不会被删除。' -ForegroundColor DarkGray
    try { $lock = [IO.File]::Open((Join-Path $runtime 'operation.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another local start/rebuild/stop operation is running. Wait for it to finish.' }
    Set-Location $repo
    Write-Step 1 '检查 Docker 引擎与本机连接'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI missing. Install/start Docker Desktop with Linux containers.' }
    # Inspect local context metadata before contacting an engine; never inherit DOCKER_HOST.
    $endpoint = Docker @('context','inspect',$dockerContext,'--format','{{.Endpoints.docker.Host}}') -Quiet
    if ($endpoint -notmatch '^npipe:/{2,}\./pipe/[^/]+$') { throw 'Refusing non-local Docker endpoint. desktop-linux must use a local Windows named pipe.' }
    $osType = Docker @('info','--format','{{.OSType}}') -Quiet
    if ($osType -ne 'linux') { throw 'Docker must use Linux containers. Start Docker Desktop and switch to Linux containers.' }
    if ($Action -eq 'stop') {
        Write-Step 2 '停止四个服务，保留数据卷与日志'
        if (-not (Test-Path -LiteralPath $envFile)) { throw 'Local configuration missing: nothing was started by these scripts.' }
        $script:config = Read-Env $envFile
        [void](Compose @('stop','--timeout','30'))
        Write-Host '  [完成] 服务已停止，MySQL / Redis 数据和日志已保留。' -ForegroundColor Green
    } else {
        Write-Step 2 '检查端口与必要配置'
        Prepare-Config
        Write-Step 3 '启动 MySQL / Redis 并等待健康检查'
        [void](Compose @('up','-d','--wait','--wait-timeout','180','mysql','redis'))
        # Stop applications before upgrading an existing database.
        Write-Step 4 '停止应用，核对增量 SQL 与本机文件存储'
        [void](Compose @('stop','--timeout','30','backend','admin-ui'))
        Migrate
        Configure-LocalFiles
        if ($Action -eq 'rebuild') {
            Write-Step 5 '构建后端与管理后台镜像（可能需要数分钟）'
            [void](Compose @('build','backend','admin-ui'))
        }
        Write-Step $stepCount '启动后端 / 管理后台并等待健康检查'
        [void](Compose @('up','-d','--wait','--wait-timeout','300','backend','admin-ui'))
        Write-Host ''
        Write-Host '  [完成] 四个服务已启动并通过健康检查。' -ForegroundColor Green
        Write-Host "  管理后台： http://127.0.0.1:$($config['ADMIN_PORT'])" -ForegroundColor White
        Write-Host "  小程序后端：http://127.0.0.1:$($config['BACKEND_PORT'])" -ForegroundColor White
        Write-Host '  模拟支付：关闭    小程序编译：需另行运行' -ForegroundColor Yellow
    }
    Write-Host ('  ' + ('=' * 56)) -ForegroundColor Green
    Write-Host ("  操作成功 | 总用时 {0:mm\:ss}" -f $elapsed.Elapsed) -ForegroundColor Green
    Write-Host "  日志：$logFile" -ForegroundColor Cyan
    Write-Host ('  ' + ('=' * 56)) -ForegroundColor Green
    exit 0
} catch {
    $message = Protect-Output $_.Exception.Message
    [IO.File]::AppendAllText($logFile, "FAILED: $message`r`n", $utf8)
    Write-Host ''
    Write-Host ('  ' + ('=' * 56)) -ForegroundColor Red
    Write-Host "  [失败] $stageLabel" -ForegroundColor Red
    Write-Host "  原因：$message" -ForegroundColor Red
    Write-Host "  日志：$logFile" -ForegroundColor Cyan
    Write-Host ('  ' + ('=' * 56)) -ForegroundColor Red
    Write-Host 'If health checks failed: verify saved DB credentials/ports, then inspect the failing service logs; do not remove its data volume.'
    Write-Host 'For service logs: docker --context desktop-linux compose --env-file deploy/.env.thesis.local -f deploy/docker-compose.local.yml logs --tail 100 SERVICE'
    exit 1
} finally {
    if ($lock) { $lock.Dispose() }
}
