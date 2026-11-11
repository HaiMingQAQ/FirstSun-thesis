# 从零搭建本机演示环境

这份流程面向首次下载项目的 Windows 和 Linux 用户。演示数据由仓库 SQL 生成，包含 `FirstSun-Followup` 租户（901）、演示门店（9101）、一个合成会员、店员、药师、两种带图的合成商品和批次库存。订单、会话、处方和通知在演示过程中通过页面产生。

准备 Docker（Windows 使用 Docker Desktop 的 Linux 引擎，Linux 使用 Docker Engine 和 Compose 插件）、PowerShell 7、Node.js 及 npm。Node.js 推荐使用 **22.13.0 或更新的 22.x**，与管理端容器的 Node 22 一致。管理端 `package.json` 声明的最低版本为 `20.19.0`。在仓库根目录打开 PowerShell。已有日常 FirstSun 环境时，按文末的[同一台电脑并行运行演示](#同一台电脑并行运行演示)操作。

## 1. 填写私有配置

```powershell
Copy-Item deploy/.env.thesis.local.example .env
```

填入自己设置的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`，以及仅供本机演示的六位 `PHARMACY_DEV_SMS_CODE`。保留配置文件中的 `MYSQL_USER=thesis`，并设置下列选项：

```dotenv
MYSQL_DATABASE=firstsun_demo_local
FIRSTSUN_APP_DATABASE=firstsun_demo_local
FIRSTSUN_TENANT_ID=901
FIRSTSUN_PAY_APP_KEY=firstsun-demo
FIRSTSUN_MOCK_PAYMENT_ENABLED=true
FIRSTSUN_LOCAL_TEST_LOGIN=true
FIRSTSUN_BACKEND_URL=http://127.0.0.1:28080
```

若修改 `BACKEND_PORT`，同步修改 `FIRSTSUN_BACKEND_URL`。邮箱、微信和外部 AI 密钥可留空。`.env` 只保存在本机。

## 2. 初始化空库

```powershell
docker volume create firstsun-thesis-local_mysql-data
docker volume create firstsun-thesis-local_redis-data
docker volume create firstsun-thesis-local_backend-logs
docker compose --project-name firstsun --env-file .env --file deploy/docker-compose.local.yml pull mysql redis
docker compose --project-name firstsun --env-file .env --file deploy/docker-compose.local.yml up -d --wait mysql
pwsh -NoProfile -File scripts/init-local-demo.ps1
```

如 `.env` 指定了其他持久卷名，创建对应名字的卷。初始化器按[迁移清单](../deploy/local-migrations.json)顺序执行，登记每项迁移，并要求数据库名以 `firstsun_demo_` 开头。它会提示为 `demomanager` 和 `demopharmacist` 分别设置一个 8–16 位字母数字密码，再从标准输入交给本机 Docker 内的 `htpasswd` 生成 bcrypt 哈希。首次执行会拉取 `httpd:2.4-alpine` 工具镜像，计算时容器不联网。

Docker context 默认在 Linux 使用 `default`。Windows 优先使用已存在的 `desktop-linux`，缺少该 context 时使用 `default`。使用自定义 context 时，在整个安装会话里设置 `$env:DOCKER_CONTEXT = '你的-context'`，让 Compose 和初始化器访问同一个 Docker 引擎。初始化器也接受 `-DockerContext`，其优先级最高。显式指定的 context 有问题时，脚本会显示 Docker 的诊断并停止。

自动部署可由运行环境注入 `FIRSTSUN_DEMO_MANAGER_PASSWORD` 和 `FIRSTSUN_DEMO_PHARMACIST_PASSWORD`，再执行 `pwsh -NoProfile -NonInteractive -File scripts/init-local-demo.ps1`。PowerShell 调用方也可以传入 `-DemoManagerPassword $managerPassword -DemoPharmacistPassword $pharmacistPassword`，其中两个值均为 `SecureString`。密码取值优先级为参数、进程环境变量、交互输入。请通过本地密码管理器或自动部署的私有变量传值。

两张[合成商品图](../deploy/demo-assets)随仓库交付。初始化时会将图片写入演示库的文件存储，并为商品填入本机后端地址。电脑顾客端展示 OTC 图片，处方商品卡保留「处方资料受限」提示。已有这套演示库时，重新运行初始化器即可补图，保留原账号、库存和自行替换的商品图片。

## 3. 构建并打开演示

Windows 执行：

```powershell
Set-Location customer-web
npm ci
Set-Location ..
.\rebuild-local.bat
```

Linux 在 PowerShell 中执行下面的命令。如果修改过 `BACKEND_PORT`，将示例中的 `28080` 换成实际端口。

```powershell
Set-Location customer-web
npm ci
$env:FIRSTSUN_TENANT_ID = '901'
$env:FIRSTSUN_LOCAL_TEST_LOGIN = 'true'
$env:FIRSTSUN_BACKEND_URL = 'http://127.0.0.1:28080'
npm run build -- --mode local-acceptance
Set-Location ..
docker compose --project-name firstsun --env-file .env --file deploy/docker-compose.local.yml pull qdrant
docker compose --project-name firstsun --env-file .env --file deploy/docker-compose.local.yml build embedding backend admin-ui customer-web
docker compose --project-name firstsun --env-file .env --file deploy/docker-compose.local.yml up -d --no-build --pull never --wait --wait-timeout 300
```

| 服务 | 默认宿主机端口 | 同机演示端口 | 配置或入口 |
| --- | --- | --- | --- |
| 顾客网页 | `4193` | `5193` | `CUSTOMER_PORT`。点「独立本机验收」，使用合成手机号 `19900009011` 和本机验证码 |
| 管理端 | `28081` | `38081` | `ADMIN_PORT`。走药店登录，`demomanager` 负责拣货、核销和客服，`demopharmacist` 负责药师咨询和审方 |
| 后端 | `28080` | `38080` | `BACKEND_PORT`。健康检查为 `/actuator/health` |
| MySQL | `23327` | `33327` | `MYSQL_PORT` |
| Redis | `26397` | `36397` | `REDIS_PORT` |
| Qdrant | HTTP `26333`，gRPC `26334` | HTTP `36333`，gRPC `36334` | `QDRANT_HTTP_PORT`、`QDRANT_GRPC_PORT` |
| embedding | 无宿主机端口 | 无宿主机端口 | 后端通过 Compose 网络访问 `http://embedding:8000`，可用 `FIRSTSUN_EMBEDDING_URL` 指定地址 |

宿主机端口均绑定到 `127.0.0.1`。后端在自己的 Compose 网络内访问 Qdrant 的 gRPC 端口 `6334`，修改宿主机映射无需修改容器间地址。

演示可按三个流程核对。先用「演示 OTC」下单、模拟付款并在管理端拣货核销，再发起一条测试咨询，最后用「演示处方品」和仓库自带的[合成处方上传附件](../deploy/demo-assets/prescription-upload.png)提交申请，并检查审核后的站内通知。

H5 也可连接这套演示库。在新 PowerShell 窗口从仓库根目录执行：

```powershell
Set-Location mall-uniapp
npm ci --ignore-scripts
$env:SHOPRO_TENANT_ID = '901'
$env:SHOPRO_BASE_URL = 'http://127.0.0.1:28080'
$env:SHOPRO_DEV_BASE_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_H5_TEST_BACKEND_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_STATIC_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_H5_TEST_LOGIN = 'true'
npm run dev:h5 -- --mode local-acceptance --host 127.0.0.1 --port 4189
```

`local-acceptance` 模式从仓库的 [`mall-uniapp/.env.local-acceptance`](../mall-uniapp/.env.local-acceptance) 读取 API 前缀、静态资源地址、WebSocket 路径、H5 地址和上传方式。若后端端口是 `38080` 等自定义值，修改上面的 `SHOPRO_BASE_URL`，其余三个后端地址会一起采用该端口。若修改 H5 的 `--port`，同步设置 `$env:SHOPRO_H5_URL`。

浏览器打开 `http://127.0.0.1:4189`，使用同一合成会员和本机验证码。本机 RAG 演示另需模型配置和索引，基础购物、咨询和处方演示可离线运行。

## 同一台电脑并行运行演示

使用独立配置目录 `.local/demo`。示例项目名为 `firstsun-demo`，拥有自己的镜像标签、网络、MySQL、Redis、Qdrant 和日志卷，端口见上表。现有日常入口继续使用原来的 `.env`。

```powershell
$demoRoot = Join-Path (Get-Location) '.local/demo'
New-Item -ItemType Directory -Force $demoRoot | Out-Null
Copy-Item deploy/.env.demo.example (Join-Path $demoRoot '.env')
```

第一次复制后填写演示配置里的数据库密码、Redis 密码和本机验证码。之后复用这份配置。创建独立卷、启动 MySQL 并初始化：

```powershell
$demoEnv = Join-Path $demoRoot '.env'
docker volume create firstsun-demo_mysql-data
docker volume create firstsun-demo_redis-data
docker volume create firstsun-demo_backend-logs
docker compose --project-name firstsun-demo --env-file $demoEnv --file deploy/docker-compose.local.yml pull mysql redis
docker compose --project-name firstsun-demo --env-file $demoEnv --file deploy/docker-compose.local.yml up -d --wait mysql
pwsh -NoProfile -File scripts/init-local-demo.ps1 -EnvironmentRoot $demoRoot
```

Windows 按第 3 步安装顾客端依赖，然后从仓库根目录执行 `pwsh -NoProfile -File scripts/firstsun.ps1 -Action rebuild -EnvironmentRoot $demoRoot`。Linux 按第 3 步构建，将 `--env-file .env` 改为 `--env-file $demoEnv`，将 `--project-name firstsun` 改为 `--project-name firstsun-demo`，并将顾客端构建的 `FIRSTSUN_BACKEND_URL` 改为 `http://127.0.0.1:38080`。三个外部卷名称都须以项目名加下划线开头。

演示 H5 使用 `SHOPRO_BASE_URL=http://127.0.0.1:38080`。若同时运行日常 H5，将演示 H5 的 `--port` 和 `SHOPRO_H5_URL` 改到另一个空闲端口，例如 `5189`。
