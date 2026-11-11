# 从零搭建本机演示环境

这份流程面向首次下载项目的 Windows 用户。演示数据由仓库 SQL 生成，包含 `FirstSun-Followup` 租户（901）、演示门店（9101）、一个合成会员、店员、药师、两种合成商品和批次库存。订单、会话、处方和通知在演示过程中通过页面产生。

准备 Docker Desktop（Linux 引擎）、PowerShell 7、Node.js 及 npm，打开仓库根目录下的 PowerShell。以下首次安装步骤用于一台没有日常 FirstSun 数据的机器。已有 FirstSun 环境时，先另行准备独立的演示配置与空库，保留现有配置和数据卷。

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

如 `.env` 指定了其他持久卷名，创建对应名字的卷。初始化器按[迁移清单](../deploy/local-migrations.json)顺序执行，登记每项迁移，并要求数据库名以 `firstsun_demo_` 开头。它会提示为 `demomanager` 和 `demopharmacist` 分别设置一个 8–16 位字母数字密码，再从标准输入交给本机 Docker 内的 `htpasswd` 生成 bcrypt 哈希。首次执行会拉取 `httpd:2.4-alpine` 工具镜像，计算时容器不联网。重复运行会核对迁移和演示基线，不会重置账号或库存。

## 3. 构建并打开演示

```powershell
Set-Location customer-web
npm ci
Set-Location ..
.\rebuild-local.bat
```

| 页面 | 地址 | 演示入口 |
| --- | --- | --- |
| 顾客网页 | `http://127.0.0.1:4193` | 点「独立本机验收」，用合成手机号 `19900009011` 和私有配置里的本机验证码 |
| 管理端 | `http://127.0.0.1:28081` | 走药店登录。`demomanager` 拣货、核销和接待客服，`demopharmacist` 接待药师咨询和审方 |
| 后端健康检查 | `http://127.0.0.1:28080/actuator/health` | 返回健康状态 |

演示可按三个流程核对。先用「演示 OTC」下单、模拟付款并在管理端拣货核销，再发起一条测试咨询，最后用「演示处方品」和明确标注无临床效力的合成图片提交申请，并检查审核后的站内通知。

H5 也可连接这套演示库。在新 PowerShell 窗口从仓库根目录执行：

```powershell
Set-Location mall-uniapp
npm ci --ignore-scripts
$env:SHOPRO_TENANT_ID = '901'
$env:SHOPRO_BASE_URL = 'http://127.0.0.1:28080'
$env:SHOPRO_DEV_BASE_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_H5_TEST_BACKEND_URL = $env:SHOPRO_BASE_URL
$env:SHOPRO_H5_TEST_LOGIN = 'true'
npm run dev:h5 -- --mode local-acceptance --host 127.0.0.1 --port 4189
```

浏览器打开 `http://127.0.0.1:4189`，使用同一合成会员和本机验证码。本机 RAG 演示另需模型配置和索引，基础购物、咨询和处方演示可离线运行。
