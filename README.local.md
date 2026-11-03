# FirstSun 本机统一环境

日常只维护一个 Docker Compose 项目：`firstsun`。定义在仓库内的 `deploy/docker-compose.local.yml`，根目录 `docker-compose.yml` 引用同一份配置；移动或重新拉取代码后不依赖原来的日期目录。

## 入口

Windows 安装并打开 Docker Desktop（Linux containers），在仓库根目录使用：

| 文件 | 操作 |
| --- | --- |
| `start-local.bat` | 复用现有镜像与数据卷，启动五个服务并等待健康检查 |
| `stop-local.bat` | 正常停止，保留容器和全部持久数据 |
| `status.bat` | 查看状态 |
| `rebuild-local.bat` | 构建顾客端产物和三个应用镜像，再启动服务 |

脚本固定连接本机 `desktop-linux` context。共享父目录已有统一入口时，仓库入口转到父目录；独立clone时，直接调用本仓库 `scripts/firstsun.ps1`。私有配置位于所选环境根目录 `.env`，可参考 `deploy/.env.thesis.local.example`；示例不含可用凭据。必须填写已有数据库的正确凭据，不会生成新密码或重设原账号。

已有本机统一环境保留原 `firstsun-thesis-local` 的 MySQL、Redis、日志卷。卷名可在 `.env` 的 `FIRSTSUN_MYSQL_VOLUME`、`FIRSTSUN_REDIS_VOLUME`、`FIRSTSUN_LOGS_VOLUME` 指向已确认的现有卷；它们均为external卷，不会在缺失时悄悄创建空库。新机器先由环境负责人明确数据来源和卷，不能把空库启动视为迁移完成。

非Windows环境可以使用同一份Compose配置，例如：

```sh
docker compose --project-name firstsun --env-file .env -f deploy/docker-compose.local.yml config --quiet
docker compose --project-name firstsun --env-file .env -f deploy/docker-compose.local.yml up -d --no-build
```

这些命令要求本机已有所需镜像与持久卷；它们不会自动初始化业务数据。

## 地址与构建

默认只绑定 `127.0.0.1`：后端28080、管理端28081、顾客电脑端4193、MySQL23327、Redis26397。实际端口以私有 `.env` 为准。

后端和管理端沿用各自Dockerfile构建。顾客端Dockerfile仅打包 `customer-web/dist`；`rebuild-local.bat` 会先调用本机Node/Vite构建该目录，需先准备好 `customer-web/package.json` 中的依赖。已有本机依赖可在清单与锁文件一致时共享，不复制另一套仓库。

既有顾客端 `npm ci` 的 `Invalid Version` 问题未在本次环境收口中修改；本次两个前端构建使用已安装且清单、锁文件一致的依赖。全新依赖安装仍是单独待办，不宣称已通过。

## 数据与验收边界

启动和重建都不会自动执行SQL、初始化种子或清空数据库。旧迁移安全函数保留在 `scripts/local-migration-helpers.ps1`，仅供离线检查或明确授权的迁移工具复用，日常入口不会调用。迁移清单与配套说明仍在 `deploy/local-migrations.json`、`docs/backend-readiness.md`；已验证的迁移不能为了启动环境重新运行一遍。

正常停止使用入口脚本，保留所有卷。禁止 `down -v`、全局prune或用空库替代原库。`.env`、`.local`、数据库、日志、凭据和编译产物不提交Git。

本次收口验证了两个前端构建、镜像打包、Nginx配置、Compose解析和Windows入口。服务保持停止。SERVICE咨询有原隔离环境HTTP通过证据；最终SQL独立核对、OTC完整交易及药师处方仍未验收，不代表真实支付、退款、页面操作或生产交付通过。
