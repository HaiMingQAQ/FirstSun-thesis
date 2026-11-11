# 从零搭建本机演示环境

这份流程面向首次下载项目的 Windows 和 Linux 用户。演示数据由仓库 SQL 生成，包含 `FirstSun-Followup` 租户（901）、演示门店（9101）、一个合成会员、店员、药师、两种带图的合成商品和批次库存。订单、会话、处方和通知在演示过程中通过页面产生。

准备 Docker（Windows 使用 Docker Desktop 的 Linux 引擎，Linux 使用 Docker Engine 和 Compose 插件）、PowerShell 7、Node.js 和 npm。管理端最低要求 **Node.js 22.13.0**，构建固定使用 **pnpm@11.22.0**，容器内由 Corepack 安装。顾客端在宿主机使用 npm。在仓库根目录打开一个专用于本次操作的 PowerShell 窗口。

## 1. 填写私有配置

```powershell
$environmentRoot = Join-Path (Get-Location).Path '.local/demo'
New-Item -ItemType Directory -Force -Path $environmentRoot | Out-Null
$envFile = Join-Path $environmentRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item deploy/.env.demo.example $envFile
}
```

在演示 `.env` 中填入自己设置的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`，以及仅供本机演示的六位 `PHARMACY_DEV_SMS_CODE`。模板已配置租户 901、本机测试登录和模拟支付。确认 `FIRSTSUN_PROJECT_NAME` 在当前 Docker 引擎上专用于这套演示。可自定义项目名，外部卷名、网络和应用镜像标签随项目派生。模板中的 `MYSQL_DATABASE` 和 `FIRSTSUN_APP_DATABASE` 应一致，库名须以 `firstsun_demo_` 开头。同机运行多套演示时，为每套使用独立配置目录、项目名和宿主机端口。

若修改 `BACKEND_PORT`，同步修改 `FIRSTSUN_BACKEND_URL`。邮箱、微信和外部 AI 密钥可留空。`.env` 只保存在本机。Compose 从选定的 `.env` 获取 `MYSQL_PASSWORD` 和 `REDIS_PASSWORD`，将其作为 secrets 挂载到后端的 `/run/secrets/`。Spring Boot 在启动时通过 `SPRING_CONFIG_IMPORT=configtree:/run/secrets/` 读取主从数据源和 Redis 的密码。数据源地址、用户名等由 Compose 启动配置传入。

## 选择环境与 Compose 参数

首次演示沿用第 1 步的 `$environmentRoot`。若只是直接启动已有日常环境，将 `$environmentRoot` 指向已有私有 `.env` 所在的绝对路径。后续命令在同一个 PowerShell 窗口运行。

```powershell
$envFile = (Resolve-Path -LiteralPath (Join-Path $environmentRoot '.env') -ErrorAction Stop).Path
$composeFile = (Resolve-Path -LiteralPath 'deploy/docker-compose.local.yml').Path
. ./scripts/firstsun-checks.ps1
$values = @{}
foreach ($line in [IO.File]::ReadAllLines($envFile)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        $values[$Matches[1]] = $Matches[2]
    }
}
Assert-FirstSunConfig $values
$projectName = Get-FirstSunProjectName $values
$volumes = Get-FirstSunVolumes $values $projectName
Clear-FirstSunComposeEnvironment $composeFile $values @{}
if ($projectName -eq 'firstsun') {
    foreach ($key in $volumes.Keys) {
        if (-not $values[$key]) {
            [Environment]::SetEnvironmentVariable($key, $volumes[$key], 'Process')
        }
    }
}
$dockerContext = if ($IsWindows) { 'desktop-linux' } elseif ($env:DOCKER_CONTEXT) { $env:DOCKER_CONTEXT } else { 'default' }
$compose = @('--context', $dockerContext, 'compose', '--project-name', $projectName, '--env-file', $envFile, '--file', $composeFile)
docker @compose config --quiet
```

日常项目继续使用已有的 MySQL、Redis 和日志外部卷。脚本兼容旧默认卷名，也会采用日常 `.env` 里显式指定的卷名。上面清除的是本次 PowerShell 进程中会覆盖 `.env` 的变量。若只是启动已有环境，确认上面的配置校验成功后执行：

```powershell
docker @compose up -d --no-build --pull never --wait --wait-timeout 300
```

直接调用 Compose 要求已有镜像和外部卷。首次演示继续执行第 2 步。

## 2. 初始化空库

```powershell
if ($projectName -eq 'firstsun' -or $values['MYSQL_DATABASE'] -cnotmatch '^firstsun_demo_[a-z0-9_]{1,40}$') {
    throw '初始化需要独立演示项目和 firstsun_demo_ 数据库。'
}
$configJson = docker @compose config --format json
if ($LASTEXITCODE -ne 0) { throw '无法解析演示 Compose 配置。' }
$externalVolumes = (($configJson -join "`n") | ConvertFrom-Json).volumes.PSObject.Properties |
    Where-Object { $_.Value.external -eq $true } |
    ForEach-Object { $_.Value.name }
Remove-Variable configJson
foreach ($name in $externalVolumes) {
    docker --context $dockerContext volume create $name
    if ($LASTEXITCODE -ne 0) { throw "无法创建演示卷 $name。" }
}
docker @compose pull mysql redis
if ($LASTEXITCODE -ne 0) { throw '无法拉取 MySQL 或 Redis 镜像。' }
docker @compose up -d --wait mysql
if ($LASTEXITCODE -ne 0) { throw '演示 MySQL 未就绪。' }
pwsh -NoProfile -File scripts/init-local-demo.ps1 -EnvironmentRoot $environmentRoot -DockerContext $dockerContext
```

完整的 Compose JSON 包含私有值。上面的命令只在内存中读取 JSON，提取外部卷名后丢弃原始内容。Qdrant 卷由 Compose 创建。初始化器按[迁移清单](../deploy/local-migrations.json)顺序执行并登记每项迁移。它会提示为 `demomanager` 和 `demopharmacist` 分别设置一个 8–16 位字母数字密码，再从标准输入交给本机 Docker 内的 `htpasswd` 生成 bcrypt 哈希。首次执行会拉取 `httpd:2.4-alpine` 工具镜像，计算时容器不联网。

Windows 示例选择 `desktop-linux`，与重建脚本一致。Linux 可在生成 Compose 参数前设置 `$env:DOCKER_CONTEXT` 以选择本机 Docker 引擎，未设置时使用 `default`。初始化命令通过 `-DockerContext` 使用同一个引擎。

自动部署可由运行环境注入 `FIRSTSUN_DEMO_MANAGER_PASSWORD` 和 `FIRSTSUN_DEMO_PHARMACIST_PASSWORD`，在上面的 `pwsh` 命令中加上 `-NonInteractive`（放在 `-File` 之前）。PowerShell 调用方也可以传入 `-DemoManagerPassword $managerPassword -DemoPharmacistPassword $pharmacistPassword`，其中两个值均为 `SecureString`。密码取值优先级为参数、进程环境变量、交互输入。请通过本地密码管理器或自动部署的私有变量传值。

两张[合成商品图](../deploy/demo-assets)随仓库交付。初始化时会将图片写入演示库的文件存储，并为商品填入本机后端地址。电脑顾客端展示 OTC 图片，处方商品卡保留「处方资料受限」提示。已有这套演示库时，重新运行初始化器即可补图，保留原账号、库存和自行替换的商品图片。

## 3. 构建并打开演示

Windows 执行：

```powershell
Set-Location customer-web
npm ci
Set-Location ..
pwsh -NoProfile -File scripts/firstsun.ps1 -Action rebuild -EnvironmentRoot $environmentRoot
```

Linux 在同一个 PowerShell 窗口执行下面的命令，顾客端构建使用第 1 步中选定的后端地址。

```powershell
Set-Location customer-web
npm ci
$env:FIRSTSUN_TENANT_ID = $values['FIRSTSUN_TENANT_ID']
$env:FIRSTSUN_LOCAL_TEST_LOGIN = $values['FIRSTSUN_LOCAL_TEST_LOGIN']
$env:FIRSTSUN_BACKEND_URL = $values['FIRSTSUN_BACKEND_URL']
npm run build -- --mode local-acceptance
Set-Location ..
docker @compose pull qdrant
docker @compose build embedding backend admin-ui customer-web
docker @compose up -d --no-build --pull never --wait --wait-timeout 300
```

海外网络下，如果后端构建卡在华为云 Maven 镜像，编辑本机工作树的 [backend/pom.xml](../backend/pom.xml)，在 `<repositories>` 中将 `<id>huaweicloud</id>` 对应的 `<url>` 改为 `https://repo.maven.apache.org/maven2`。也可移除华为云和阿里云两个 `<repository>` 条目，采用 Maven 默认的 Central。容器构建从仓库复制此 POM，再运行 Maven。修改后用同一组 Compose 参数执行 `docker @compose build backend`，成功后继续启动。

| 服务 | 日常默认宿主机端口 | 演示模板宿主机端口 | 配置或入口 |
| --- | --- | --- | --- |
| 顾客网页 | `4193` | `5193` | `CUSTOMER_PORT`。点「独立本机验收」，使用合成手机号 `19900009011` 和本机验证码 |
| 管理端 | `28081` | `38081` | `ADMIN_PORT`。走药店登录，`demomanager` 负责拣货、核销和客服，`demopharmacist` 负责药师咨询和审方 |
| 后端 | `28080` | `38080` | `BACKEND_PORT`。健康检查为 `/actuator/health` |
| MySQL | `23327` | `33327` | `MYSQL_PORT` |
| Redis | `26397` | `36397` | `REDIS_PORT` |
| Qdrant | HTTP `26333`，gRPC `26334` | HTTP `36333`，gRPC `36334` | `QDRANT_HTTP_PORT`、`QDRANT_GRPC_PORT` |
| embedding | 无宿主机端口 | 无宿主机端口 | Compose 网络内的向量化服务，见[可选 RAG 配置](#可选-rag-配置) |

宿主机端口均绑定到 `127.0.0.1`。后端在自己的 Compose 网络内访问 Qdrant 的 gRPC 端口 `6334`，修改宿主机映射无需修改容器间地址。

演示可按三个流程核对。先用「演示 OTC」下单、模拟付款并在管理端拣货核销，再发起一条测试咨询，最后用「演示处方品」和仓库自带的[合成处方上传附件](../deploy/demo-assets/prescription-upload.png)提交申请，并检查审核后的站内通知。

H5 也可连接这套演示库。在新 PowerShell 窗口从仓库根目录执行：

```powershell
Set-Location mall-uniapp
npm ci --ignore-scripts
$env:SHOPRO_TENANT_ID = '901'
$env:SHOPRO_BASE_URL = 'http://127.0.0.1:38080'
$env:SHOPRO_DEV_BASE_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_H5_TEST_BACKEND_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_STATIC_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_H5_TEST_LOGIN = 'true'
npm run dev:h5 -- --mode local-acceptance --host 127.0.0.1 --port 4189
```

`local-acceptance` 模式从仓库的 [`mall-uniapp/.env.local-acceptance`](../mall-uniapp/.env.local-acceptance) 读取 API 前缀、静态资源地址、WebSocket 路径、H5 地址和上传方式。若演示后端使用自定义端口，将上面的 `SHOPRO_BASE_URL` 改为演示 `.env` 中的 `FIRSTSUN_BACKEND_URL`，其余三个后端地址会一起采用该地址。若同时运行日常 H5，演示 H5 可选另一个空闲端口，例如 `5189`，并同步设置 `$env:SHOPRO_H5_URL`。

浏览器打开所选 H5 地址，使用同一合成会员和本机验证码。

## 可选 RAG 配置

基础购物、人工咨询和处方演示可离线运行。需要演示知识检索时，先核对[可选 RAG 基线 SQL](../deploy/sql/seed-rag-baseline.sql)中的租户与角色 ID，再导入选定的演示库。SQL 注释中的容器名、数据库名和角色说明来自旧环境，按本次 Compose 项目和演示库核对。导入后确认后台的向量模型配置，再使用具有 `pharmacy:ai:chat` 权限的演示账号调用 `POST /admin-api/pharmacy/ai/knowledge/reindex` 重建知识库索引。

完成基线导入及模型、索引配置后，使用基线向量模型的后端会解析模型 URL 中的 `${FIRSTSUN_EMBEDDING_URL}`。Compose 默认提供 `http://embedding:8000`。只有改用其他后端可访问的向量化地址时，才需在同一份私有 `.env` 中覆盖该变量，并重新创建后端容器以加载配置。此变量配置的是向量化服务地址，知识索引由应用单独建立。
