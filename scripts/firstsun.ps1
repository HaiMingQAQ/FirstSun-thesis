param(
    [ValidateSet('create','start','stop','status','rebuild')][string]$Action = 'status',
    [string]$EnvironmentRoot = ''
)
$ErrorActionPreference = 'Stop'
# docker 的 JSON 输出含 UTF-8 多字节字符（容器 Command 字段被截断成“…”）；
# Windows PowerShell 5.1 默认按 ANSI 解码，会把多字节字符里的引号切坏，导致 JSON 解析失败
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
. (Join-Path $PSScriptRoot 'firstsun-checks.ps1')
$repo = Split-Path $PSScriptRoot -Parent
$root = if ($EnvironmentRoot) { [IO.Path]::GetFullPath($EnvironmentRoot) } else { $repo }
$envFile = Join-Path $root '.env'
$composeFile = Join-Path $repo 'deploy/docker-compose.local.yml'
$localDir = Join-Path $root '.local'
$recordFile = Join-Path $localDir 'firstsun-build.json'
$services = @('mysql','redis','qdrant','embedding','backend','admin-ui','customer-web')
$imageTags = [ordered]@{ mysql='mysql:8.0'; redis='redis:7-alpine'; qdrant='qdrant/qdrant:v1.12.1'; embedding='firstsun-embedding:local'; backend='firstsun-backend:local'; 'admin-ui'='firstsun-admin-ui:local'; 'customer-web'='firstsun-customer-web:local' }
$volumeDefaults = [ordered]@{ FIRSTSUN_MYSQL_VOLUME='firstsun-thesis-local_mysql-data'; FIRSTSUN_REDIS_VOLUME='firstsun-thesis-local_redis-data'; FIRSTSUN_LOGS_VOLUME='firstsun-thesis-local_backend-logs' }

function Invoke-DockerData([string[]]$arguments) {
    # Windows PowerShell can report Docker's progress on stderr as a terminating error.
    $ErrorActionPreference = 'Continue'
    $output = & docker --context desktop-linux @arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'DOCKER_COMMAND_FAILED' }
    return $output
}
function Invoke-ComposeData([string[]]$arguments) {
    return Invoke-DockerData (@('compose','--project-name','firstsun','--env-file',$envFile,'--file',$composeFile) + $arguments)
}
function Invoke-ComposeQuiet([string[]]$arguments) {
    $null = Invoke-ComposeData $arguments
}
function Get-ImageIds {
    $ids = @{}
    foreach ($service in $imageTags.Keys) {
        try {
            $id = @(Invoke-DockerData @('image','inspect',$imageTags[$service],'--format','{{.Id}}'))[0]
            if ($id) { $ids[$service] = [string]$id }
        } catch { }
    }
    return $ids
}
function Get-ComposeRows {
    $raw = @(Invoke-ComposeData @('ps','--all','--format','json'))
    $text = ($raw -join "`n").Trim()
    if (-not $text) { return @() }
    $rows = @()
    # 镜像 label 里可能带换行（例如基础镜像的 description），按行解析会被截断，
    # 这里按花括号配对逐个切出完整的 JSON 对象，字符串内部的括号不算层级。
    $depth = 0
    $start = -1
    $inString = $false
    $escape = $false
    for ($i = 0; $i -lt $text.Length; $i++) {
        $c = $text[$i]
        if ($inString) {
            if ($escape) { $escape = $false }
            elseif ($c -eq '\') { $escape = $true }
            elseif ($c -eq '"') { $inString = $false }
            continue
        }
        if ($c -eq '"') { $inString = $true; continue }
        if ($c -eq '{') {
            if ($depth -eq 0) { $start = $i }
            $depth++
        } elseif ($c -eq '}') {
            $depth--
            if ($depth -eq 0 -and $start -ge 0) {
                $chunk = $text.Substring($start, $i - $start + 1)
                try { $rows += @($chunk | ConvertFrom-Json) }
                catch { throw 'COMPOSE_STATUS_FAILED' }
                $start = -1
            }
        }
    }
    return @($rows | Where-Object { $_ -and $_.Service })
}
function Get-OwnPorts($rows) {
    $owned = @{}
    foreach ($service in $services) { $owned[$service] = @() }
    foreach ($row in $rows) {
        if ($owned.ContainsKey([string]$row.Service) -and $row.State -eq 'running') {
            foreach ($publisher in @($row.Publishers)) {
                if ($publisher -and $publisher.PublishedPort) {
                    $owned[[string]$row.Service] += [int]$publisher.PublishedPort
                }
            }
        }
    }
    return $owned
}
function Show-Source {
    $branch = (& git -C $repo branch --show-current 2>$null | Select-Object -First 1)
    $commit = (& git -C $repo rev-parse HEAD 2>$null | Select-Object -First 1)
    $dirty = [bool](& git -C $repo status --porcelain 2>$null)
    if (-not $branch) { $branch = '未知' }
    if (-not $commit) { $commit = '未知' }
    Write-Host "源码工作区：$repo"
    Write-Host "源码版本：$branch / $commit；未提交修改：$dirty"
    return @{ commit=[string]$commit; dirty=$dirty }
}
function Show-Version($source, $imageIds) {
    $record = $null
    if (Test-Path -LiteralPath $recordFile) {
        try { $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json } catch { }
    }
    Write-Host ('镜像标签记录（非运行容器版本）：' + (Get-FirstSunVersion $record $source.commit $imageIds $source.dirty))
}
function Show-States($rows) {
    foreach ($service in $services) {
        $row = @($rows | Where-Object { $_.Service -eq $service } | Select-Object -First 1)
        $state = if ($row.Count -and $row[0].State) { [string]$row[0].State } else { '未创建' }
        $health = if ($row.Count -and $row[0].Health) { " / $($row[0].Health)" } else { '' }
        Write-Host "  $service：$state$health"
    }
}
function Show-Services($rows, $ports, [string]$logVolume) {
    Show-States $rows
    Write-Host "管理端：http://127.0.0.1:$($ports['admin-ui'])"
    Write-Host "顾客端：http://127.0.0.1:$($ports['customer-web'])"
    Write-Host "后端：http://127.0.0.1:$($ports.backend)"
    Write-Host "日志：docker --context desktop-linux compose --project-name firstsun --env-file `"$envFile`" --file `"$composeFile`" logs --tail=100 <服务名>"
    Write-Host "后端日志卷：$logVolume"
}
function Invoke-Main {
    $stage = '初始化'
    $previous = @{}
    $lock = $null
    try {
        Write-Host '[1/4] 检查工作区、私有配置和 Docker 引擎'
        New-Item -ItemType Directory -Force -Path $localDir | Out-Null
        $stage = '并发操作锁'
        try { $lock = [IO.File]::Open((Join-Path $localDir 'docker.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch { throw 'LOCK_BUSY' }
        $stage = 'Docker CLI'
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'DOCKER_CLI_MISSING' }
        $stage = '私有配置'
        if (-not (Test-Path -LiteralPath $envFile)) { throw 'ENV_MISSING' }
        $values = @{}
        foreach ($line in [IO.File]::ReadAllLines($envFile)) {
            if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
        }
        Assert-FirstSunConfig $values
        $ports = Get-FirstSunPorts $values
        $managedKeys = @($values.Keys) + @('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','BUILDX_BUILDER')
        foreach ($key in $managedKeys) {
            $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $null, 'Process')
        }
        foreach ($key in $values.Keys) { [Environment]::SetEnvironmentVariable($key, [string]$values[$key], 'Process') }
        $env:DOCKER_CONTEXT = 'desktop-linux'
        $env:BUILDX_BUILDER = 'default'
        $source = Show-Source
        Write-Host "操作：$Action（项目 firstsun）"
        $stage = 'Docker Desktop Linux 引擎'
        $endpoint = @(Invoke-DockerData @('context','inspect','desktop-linux','--format','{{.Endpoints.docker.Host}}'))[0]
        if ($endpoint -notmatch '^npipe:/+/\./pipe/[^/]+$') { throw 'WRONG_DOCKER_CONTEXT' }
        $engine = @(Invoke-DockerData @('info','--format','{{.OSType}}'))[0]
        if ($engine -ne 'linux') { throw 'WRONG_DOCKER_ENGINE' }
        $stage = 'Compose 配置'
        Invoke-ComposeQuiet @('config','--quiet')
        $imageIds = Get-ImageIds
        Show-Version $source $imageIds
        Write-Host '[2/4] 检查镜像、持久卷和本机端口'
        if ($Action -in @('create','start','rebuild')) {
            $stage = '已有镜像和持久卷'
            $requiredImages = if ($Action -eq 'rebuild') { @('mysql','redis') } else { $services }
            foreach ($service in $requiredImages) {
                if (-not $imageIds.ContainsKey($service)) { throw "MISSING_IMAGE:$service" }
            }
            foreach ($key in $volumeDefaults.Keys) {
                $volume = if ($values.ContainsKey($key) -and $values[$key]) { [string]$values[$key] } else { $volumeDefaults[$key] }
                try { $null = Invoke-DockerData @('volume','inspect',$volume,'--format','{{.Name}}') }
                catch { throw "MISSING_VOLUME:$key" }
            }
            $stage = '本机端口'
            $rows = Get-ComposeRows
            try { $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Select-Object -ExpandProperty LocalPort -Unique) }
            catch { throw 'PORT_CHECK_FAILED' }
            $conflict = Get-FirstSunConflict $ports (Get-OwnPorts $rows) $listeners
            if ($conflict) { throw "PORT_IN_USE:$conflict" }
        }
        $stage = "执行 $Action"
        $actionText = switch ($Action) {
            'start' { '启动七项服务并等待健康（最多 300 秒）' }
            'rebuild' { '构建并启动七项服务，等待健康（最多 300 秒）' }
            'create' { '创建现有项目容器' }
            'stop' { '停止现有项目服务并保留数据' }
            'status' { '读取现有项目状态' }
        }
        Write-Host "[3/4] $actionText"
        switch ($Action) {
            'create' { Invoke-ComposeQuiet @('create','--no-build','--pull','never') }
            'start' { Invoke-ComposeQuiet @('up','-d','--no-build','--pull','never','--wait','--wait-timeout','300') }
            'stop' { Invoke-ComposeQuiet @('stop','--timeout','30') }
            'status' { }
            'rebuild' {
                Push-Location (Join-Path $repo 'customer-web')
                try {
                    $viteArgs = @('build')
                    if ($values['FIRSTSUN_LOCAL_TEST_LOGIN'] -ceq 'true') {
                        $viteArgs += @('--mode', 'local-acceptance')
                    }
                    & node './node_modules/vite/bin/vite.js' @viteArgs *> $null
                    if ($LASTEXITCODE -ne 0) { throw 'CUSTOMER_BUILD_FAILED' }
                } finally { Pop-Location }
                # 向量库镜像是官方镜像，本地缺就先拉取；向量化服务是本仓库构建的
                if (-not (Get-ImageIds).ContainsKey('qdrant')) { Invoke-ComposeQuiet @('pull','qdrant') }
                Invoke-ComposeQuiet @('build','embedding','backend','admin-ui','customer-web')
                Invoke-ComposeQuiet @('up','-d','--no-build','--pull','never','--wait','--wait-timeout','300')
                $rebuiltIds = Get-ImageIds
                foreach ($service in @('embedding','backend','admin-ui','customer-web')) {
                    if (-not $rebuiltIds.ContainsKey($service)) { throw 'BUILT_IMAGE_MISSING' }
                }
                $record = @{ commit=$source.commit; dirty=$source.dirty; images=@{ backend=$rebuiltIds.backend; 'admin-ui'=$rebuiltIds['admin-ui']; 'customer-web'=$rebuiltIds['customer-web'] }; recordedAt=(Get-Date).ToUniversalTime().ToString('o') }
                $temporary = "$recordFile.tmp"
                [IO.File]::WriteAllText($temporary, ($record | ConvertTo-Json -Depth 4))
                Move-Item -LiteralPath $temporary -Destination $recordFile -Force
                $imageIds = $rebuiltIds
                Show-Version $source $imageIds
            }
        }
        $stage = '服务状态'
        Write-Host '[4/4] 查看服务状态与本机入口'
        $logVolume = if ($values.ContainsKey('FIRSTSUN_LOGS_VOLUME') -and $values.FIRSTSUN_LOGS_VOLUME) { [string]$values.FIRSTSUN_LOGS_VOLUME } else { $volumeDefaults.FIRSTSUN_LOGS_VOLUME }
        Show-Services (Get-ComposeRows) $ports $logVolume
        Write-Host '操作完成。'
        return 0
    } catch {
        $reason = [string]$_.Exception.Message
        if ($reason -notmatch '^(LOCK_BUSY|DOCKER_CLI_MISSING|ENV_MISSING|MISSING_CONFIG:[A-Z_]+|INVALID_PORT:[a-z-]+|DUPLICATE_PORT|WRONG_DOCKER_CONTEXT|WRONG_DOCKER_ENGINE|DOCKER_COMMAND_FAILED|COMPOSE_STATUS_FAILED|MISSING_IMAGE:[a-z-]+|MISSING_VOLUME:[A-Z_]+|PORT_CHECK_FAILED|PORT_IN_USE:[a-z-]+ \([0-9]+\)|CUSTOMER_BUILD_FAILED|BUILT_IMAGE_MISSING)$') { $reason = 'CHECK_FAILED' }
        $advice = switch -Wildcard ($reason) {
            'LOCK_BUSY' { '已有启动/停止操作在运行；等待结束后再试。' }
            'DOCKER_CLI_MISSING' { '安装 Docker Desktop 并确认 docker 在 PATH 中。' }
            'WRONG_DOCKER_*' { '启动 Docker Desktop Linux 引擎并确认 desktop-linux 本机 context。' }
            'ENV_MISSING' { '恢复现有私有 .env，不要新建空数据库。' }
            'MISSING_CONFIG:*' { '补齐现有私有 .env 的必填项。' }
            'MISSING_IMAGE:*' { '缺少已有镜像；先运行 rebuild-local.bat 或恢复已有镜像。' }
            'MISSING_VOLUME:*' { '恢复原持久卷；不要创建空卷代替。' }
            'PORT_IN_USE:*' { '释放外部占用端口，或在私有 .env 中调整本机端口。' }
            'CUSTOMER_BUILD_FAILED' { '检查顾客端依赖和构建；原镜像及数据未改。' }
            'DOCKER_COMMAND_FAILED' { '检查 Docker Desktop 与服务健康；可运行 status.bat 查看当前状态。' }
            default { '运行 status.bat 检查环境与服务状态。' }
        }
        Write-Host "失败阶段：$stage；原因：$reason。$advice"
        if ($stage -in @('执行 start','执行 rebuild','执行 create','服务状态')) {
            try {
                Write-Host '当前服务状态：'
                Show-States (Get-ComposeRows)
            } catch { Write-Host '当前服务状态暂不可读取。' }
        }
        return 1
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
        if ($lock) { $lock.Dispose() }
    }
}
exit (Invoke-Main)
