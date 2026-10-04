# Windows 毕设本机环境

## 启动

安装并启动 Docker Desktop，选择 **Linux containers**，准备至少 6 GB 可用内存。入口固定使用 `desktop-linux` context，并在连接前核对本机 Windows named pipe，拒绝远程 endpoint，不继承当前 context 或 `DOCKER_HOST`。首次构建需要访问 Docker 镜像源、Maven 和 npm 依赖源；不需要本机安装 Java、Maven 或 Node。

在仓库根目录双击：

| 入口 | 操作 |
| --- | --- |
| `start-local.bat` | 检查 Docker 和配置，启动 MySQL/Redis，执行未应用 SQL，启动应用；首次没有应用镜像时自动构建 |
| `rebuild-local.bat` | 检查并升级数据库，重新构建当前后端和管理端源码，启动应用 |
| `stop-local.bat` | 停止这套四个服务，保留容器、MySQL/Redis 数据卷与日志 |

窗口按编号显示当前阶段：蓝色为阶段标题、黄色为等待提示、绿色为完成、红色为失败。交互窗口在 Docker 操作期间显示旋转等待动画和已等待秒数；阶段编号表示执行顺序，不是完成百分比。重定向输出时不绘制动画，较长操作每 15 秒提示一次等待状态。完成后集中显示访问地址、总用时及日志路径。窗口会保留结果。自动化调用可在当前终端设置 `FIRSTSUN_NO_PAUSE=1`；退出码 0 表示成功，1 表示失败。同一仓库只允许一个入口运行，重建期间后台不可用。普通启动复用已有应用镜像，**修改代码后使用 rebuild**。

首次启动创建被 Git 忽略的 `deploy/.env.thesis.local`，生成独立数据库/Redis 密码，并从已有仓库 `.env` 和已授权的 `.tmp/business-private/ai-source.env` 中仅复用 AI、开发验证码、微信身份配置。不会导入旧数据库连接或原环境数据。已有配置文件不覆盖；如迁移到新机器，应人工填写其中缺失项，参考 `deploy/.env.thesis.local.example`。缺少 AI 配置会明确提示，服务仍可启动，但 AI 功能不可用；缺少微信配置时真实微信登录不可用。

不要分享该文件，不要把密钥粘贴进脚本、截图或日志。开发验证码在该文件中本地查看，不由启动脚本打印。首次建库后**不要更改数据库名、用户名、密码或删除配置文件**；MySQL 镜像不会因环境变量变化重新设置已有库密码。

## 地址与隔离

默认仅监听本机 `127.0.0.1`：

| 服务 | 地址 |
| --- | --- |
| 管理后台 | http://127.0.0.1:28081 |
| 后端 | http://127.0.0.1:28080 |
| 后端健康检查 | http://127.0.0.1:28080/actuator/health |
| MySQL | 127.0.0.1:23327，数据库 `firstsun_thesis_local` |
| Redis | 127.0.0.1:26397，密码见本地配置 |

端口可在本地配置里修改，四个端口必须不同且在 1024–65535；入口会清除 Docker 子进程中同名的父进程变量，以本地文件为准，不改变当前终端的环境变量。端口冲突时 Docker 错误会指出占用端口。修改后端端口后运行 **rebuild**，让管理端构建使用新地址。演示登录复用既有 21/23/38 SQL 的租户 `FirstSun`、账号 `0407`、密码 `123456`，仅适用于本机演示数据；没有导入 HTTP 验收合成账号。

固定 Compose project 为 `firstsun-thesis-local`，容器为 `firstsun-thesis-local-{mysql,redis,backend,admin-ui}-1`，网络为 `firstsun-thesis-local-network`，卷为 `firstsun-thesis-local_{mysql-data,redis-data,backend-logs}`。它们独立于课程和临时验收环境，数据库/Redis地址明确指向本套服务；不以 `local` profile 作为隔离证明。此环境可请求配置的模型服务，其他业务服务不会自动接入旧数据库或云端部署实例。

模拟支付由服务端强制配置为 **false**，本地入口不提供开启选项。支付能力是实例级开关，实际请求仍独立校验会员、归属、状态及开关；没有真实支付能力。人工开启模拟支付只能在另外新建并确认隔离的测试实例进行，不得在既有云端演示实例开启。本套启用未支付订单超时关闭任务，复用现有服务恢复处方资格，仍须重新校验审核有效期与明细。

## SQL 与保留数据

`deploy/local-migrations.json` 完整复用现有 Compose 的 **01–43** 顺序：框架→药店基础→菜单/采购/库存/会员→演示数据（23、38）→微信身份（39）→顾客 AI（40）→私有处方（41）→人工咨询（42）→顾客 AI 话题历史（43）。独立测试 SQL `20260919_f_wx_miniapp_app_api.sql` 和 HTTP seed 均不在清单中。

应用启动前，入口在本套专用库配置现有的 **数据库文件存储**（固定配置 ID `45000043`，占用时拒绝覆盖），作为本套上传默认存储，避免初始化示例的 S3 地址参与本地头像上传。文件内容保存在 MySQL 卷，访问 URL 使用 `http://127.0.0.1:BACKEND_PORT`。这个配置步骤不属于通用 SQL 迁移，也不修改云端或旧库；每次启动更新本套文件 URL，已有文件记录里的历史 URL 不会自动改写，修改端口后旧头像可能需要重新上传。头像是公开资料，使用框架公开文件接口；处方材料仍走既有私有材料接口，不通过此公开上传接口。

入口在应用停止后执行迁移，`firstsun_local_migration` 保存编号、路径、SHA256、状态和执行时间。新空库首次执行完整清单，后续只执行连续尚未应用的增量，已完成的演示种子不会重跑。SQL 已执行后修改内容会被拒绝，应新增增量 SQL 并追加编号。执行中断会保留 `RUNNING` 状态并停止启动；MySQL DDL 不保证事务回滚，应先备份本套卷并人工核对失败 SQL，不能简单删除执行记录或反复运行种子。

发现非空库没有执行记录时，入口会拒绝初始化，不猜测已执行版本，也不覆盖数据。升级其他已有数据库须先人工审核建立可信记录，本入口不会接管旧课程数据库。不要运行 `down -v`、删除卷或重复导入初始化 SQL；停止只使用 `compose stop`。数据卷是持久化而非备份，重要材料仍需另行备份。

## 日志与故障

入口日志在忽略目录 `.local\yyyyMMdd-HHmmss-{start,rebuild,stop}.log`，脚本对已配置密钥和密码进行脱敏。首次构建可能较慢，窗口持续显示运行进度，结束后日志保留构建错误详情。

在仓库根目录运行：

```powershell
docker --context desktop-linux compose --env-file deploy/.env.thesis.local -f deploy/docker-compose.local.yml ps
docker --context desktop-linux compose --env-file deploy/.env.thesis.local -f deploy/docker-compose.local.yml logs --tail 100 backend
docker --context desktop-linux compose --env-file deploy/.env.thesis.local -f deploy/docker-compose.local.yml logs -f backend admin-ui
```

后端文件日志同时保存在本套 `backend-logs` 卷。业务日志可能有个人信息，查看仅限本机，分享前自行脱敏；不要输出 `docker inspect` 环境变量或 `compose config` 的完整配置。Docker 引擎未启动、配置缺项、迁移失败、端口占用或健康检查失败会以非零码结束并显示原因，失败不会删除数据。

## 小程序开发连接

微信开发者工具本机模拟器后端基址为 **http://127.0.0.1:28080**，小程序 API 自身追加 `/app-api`。在 `mall-uniapp` 开发终端设置环境变量后重新编译：

```powershell
$env:SHOPRO_DEV_BASE_URL='http://127.0.0.1:28080'
$env:SHOPRO_TENANT_ID='163'
npm run dev:mp-weixin
```

导入 `mall-uniapp/dist/dev/mp-weixin`，启动路由 `pages/pharmacy/index`。本机开发者工具按开发需要启用“不校验合法域名”。H5 可使用同样环境变量执行现有 H5 开发命令。

开发者工具中的具体位置是「详情 → 本地设置 → 不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」。这是本机 HTTP 调试设置；仅改地址不会绕过微信的域名检查。已有本地 `project.private.config.json` 的 `urlCheck=true` 可覆盖 manifest 设置，因此请在实际导入的开发工程中确认该选项。修改 `.env.development` 后须重启编译命令，再在工具中点击「编译」；默认 API/静态资源开发地址现为 `http://127.0.0.1:28080`，不得继续使用旧 `48080` 产物。

这里的回环地址仅供同一台电脑的模拟器使用。**真机连接、真实微信登录、远程发布与真实支付尚未验证**。真机需要可访问的实际后端地址、明确的局域网访问配置或 HTTPS 部署及微信合法域名；本套默认回环绑定无法给手机访问。正式微信构建仍按 `mall-uniapp/README.deploy.md` 配置四项实际 HTTPS 部署地址，不提供虚构域名。

## 本轮验证（2026-10-01）

在本机 Docker Desktop 的专用项目实际验证：

- 首次完整源码镜像构建、01–42 新库初始化、四服务健康检查通过。
- 管理端浏览器登录页加载、后台 `0407` 登录和权限接口通过。
- 重新构建并启动通过；停止四服务再启动通过；MySQL 与 Redis 验证标记保留，42 条执行记录的 SHA/状态/时间及演示数据数量未变化。

2026-10-02 增量验收：43 在独立测试 schema 连续执行两次通过，随后本套已有库仅应用 43，原 42 条执行记录完全保留。数据库文件存储配置及启动健康检查通过，头像上传、公开图片加载、昵称保存和刷新恢复通过。AI 历史验收详见 `docs/customer-ai-development.md`。这些本地测试不代表真机或真实微信头像选择已通过。
- 无效端口与并发操作返回 1 并显示具体原因；定向测试覆盖中断、哈希变化、未知非空库拒绝、仅执行增量和种子跳过。
- 独立端口和数据卷、模拟支付关闭、本地配置忽略、配置凭据不出现在交付文件及入口/后端日志中已核对。

本机验证记录位于忽略目录 `.local`，没有作为通用 SQL 或种子提交。完整构建不等同于微信端验收；本轮没有操作微信开发者工具、真机、云端或真实支付。
