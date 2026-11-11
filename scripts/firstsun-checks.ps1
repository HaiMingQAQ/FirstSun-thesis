function Get-FirstSunPorts($values) {
    $defaults = [ordered]@{ mysql=23327; redis=26397; backend=28080; 'admin-ui'=28081; 'customer-web'=4193; 'qdrant-http'=26333; 'qdrant-grpc'=26334 }
    $keys = @{ mysql='MYSQL_PORT'; redis='REDIS_PORT'; backend='BACKEND_PORT'; 'admin-ui'='ADMIN_PORT'; 'customer-web'='CUSTOMER_PORT'; 'qdrant-http'='QDRANT_HTTP_PORT'; 'qdrant-grpc'='QDRANT_GRPC_PORT' }
    $result = [ordered]@{}
    foreach ($service in $defaults.Keys) {
        $text = if ($values.ContainsKey($keys[$service]) -and $values[$keys[$service]]) { [string]$values[$keys[$service]] } else { [string]$defaults[$service] }
        $number = 0
        if (-not [int]::TryParse($text, [ref]$number) -or $number -lt 1 -or $number -gt 65535) { throw "INVALID_PORT:$service" }
        if ($result.Values -contains $number) { throw 'DUPLICATE_PORT' }
        $result[$service] = $number
    }
    return $result
}

function Get-FirstSunProjectName($values) {
    $name = [string]$values['FIRSTSUN_PROJECT_NAME']
    if (-not $name) { return 'firstsun' }
    if ($name -cnotmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'INVALID_PROJECT_NAME' }
    if ($name -ne 'firstsun') {
        foreach ($key in 'FIRSTSUN_MYSQL_VOLUME','FIRSTSUN_REDIS_VOLUME','FIRSTSUN_LOGS_VOLUME') {
            $volume = [string]$values[$key]
            if ($volume -cnotlike "${name}_*" -or $volume -clike 'firstsun-thesis-local_*') {
                throw "UNSAFE_PROJECT_VOLUME:$key"
            }
        }
    }
    return $name
}

function Assert-FirstSunConfig($values) {
    foreach ($key in @('MYSQL_ROOT_PASSWORD','MYSQL_DATABASE','MYSQL_USER','MYSQL_PASSWORD','REDIS_PASSWORD')) {
        if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$values[$key])) { throw "MISSING_CONFIG:$key" }
    }
}

function Get-FirstSunConflict($ports, $owned, $listeners) {
    foreach ($service in $ports.Keys) {
        $port = [int]$ports[$service]
        $owner = if ($service -like 'qdrant-*') { 'qdrant' } else { $service }
        if ($listeners -contains $port -and -not ($owned.ContainsKey($owner) -and $owned[$owner] -contains $port)) {
            return "$service ($port)"
        }
    }
    return $null
}

function Get-FirstSunVersion($record, [string]$commit, $imageIds, [bool]$dirty) {
    if (-not $record -or -not $record.commit -or -not $record.images) { return '版本未知：现有镜像无成功重建记录。' }
    foreach ($service in @('backend','admin-ui','customer-web')) {
        if (-not $imageIds.ContainsKey($service) -or $record.images.$service -ne $imageIds[$service]) {
            return '版本未知：现有镜像与上次成功重建记录不一致。'
        }
    }
    if ($null -eq $record.dirty -or $record.dirty) { return '版本未知：上次重建包含未提交源码，不能证明镜像对应提交。' }
    if ($record.commit -ne $commit) { return '源码提交已变化：建议运行 rebuild-local.bat。' }
    if ($dirty) { return '源码有未提交修改：镜像仅匹配上次提交记录，修改未证明已打包。' }
    return '镜像与上次成功重建的提交及镜像 ID 匹配。'
}
