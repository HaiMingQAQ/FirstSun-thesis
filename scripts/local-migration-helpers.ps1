# Offline migration helpers retained from the previous launcher; the daily entry does not invoke them.
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

function File-Sha256([string]$path) {
    $hash = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($path)
    try { return ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $hash.Dispose() }
}

function Quote-Argument([string]$value) {
    # Windows CommandLineToArgvW quoting; also works in Windows PowerShell 5.1.
    return '"' + [regex]::Replace([regex]::Replace($value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

function Protect-Output([string]$value) {
    foreach ($key in $config.Keys) {
        if ($key -match 'PASSWORD|SECRET|API_KEY|SMS_CODE' -and $config[$key]) {
            $value = $value.Replace([string]$config[$key], '[REDACTED]')
        }
    }
    return $value
}

function Write-Step([int]$number, [string]$label) {
    $script:stageLabel = $label
    Write-Host ''
    Write-Host ("  [{0}/{1}] {2}" -f $number,$stepCount,$label) -ForegroundColor Cyan
    Write-Host ('  ' + ('-' * 56)) -ForegroundColor DarkGray
}
