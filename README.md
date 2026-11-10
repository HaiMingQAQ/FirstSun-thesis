# FirstSun · 融合 AI 助手的多端药店管理系统

毕业设计题目：**《融合 AI 助手的多端药店管理系统设计与实现》**。

FirstSun 在药店课程项目基础上，整合**电脑顾客端**（`customer-web`）、**uni-app 顾客端**（`mall-uniapp`，H5 / 微信小程序）与**管理端**（`admin-ui`），共用一个 Spring Boot 后端。顾客 AI 通过受控接口查询当前门店商品；管理端 AI 可查询药品与门店库存，涉及业务修改时只生成预览，由人工确认后才执行。

本仓库用于教学、毕设展示和开发验证。商品、会员、处方与咨询使用演示资料，**不是生产数据**。真实支付、真实医生接诊、AI 诊疗和生产部署都不在当前完成范围内。

> **状态说明（2026-11-11）**：本页按当前 `main`（`305c36db`）的源码、已合并 PR、[HANDOFF.md](HANDOFF.md)、[README.local.md](README.local.md) 和部署文件整理。“已实现”指源码已在 `main` 中，不代表所有能力都在同一基线上联合验收通过。具体验证范围见文末“当前进度”。

## 项目来源与个人工作

| 层次 | 来源与范围 |
| --- | --- |
| 开源基础 | 基于 yudao / RuoYi-Vue-Pro 后端、yudao-ui-admin-vue3 管理端和芋道商城 uni-app 顾客端做二次开发，复用认证、租户、权限、持久化、文件服务、模型配置和前端基础组件。上游说明与版权保留在各子项目中。 |
| 团队课程项目 | 前期 FirstSun 团队项目提供药品与门店基础资料、供应商与采购收货、批次库存、POS、处方审核、会员、线上订单等业务基础，以及管理端 AI 查询和受控药品操作原型。 |
| 个人毕设增量 | 在团队基线上移植并整合顾客页面，新增电脑顾客端、顾客 AI 查询（流式回答、话题历史、商品卡）、私有处方材料与购买约束、业务通知、门店人工文字咨询、邮箱会员认证、药品说明书 RAG 检索，以及统一的本机 Docker 部署入口。 |

开源框架、团队业务模块和个人增量分开说明，整套仓库不是个人从零开发的。

## 功能模块

**电脑顾客端（`customer-web`，Vue 3 + Vite）**

- 首页、推荐、找药购药：分类、按通用名或商品名搜索、“加载更多”分批展示、当前门店价格和库存。
- 商品详情：商品信息、说明书页签、询问 AI、咨询药师 / 客服入口。
- 处方药：商品卡不显示价格，标注“凭处方购买 · 需药师审核后结算”；顾客上传私有处方材料、查看审核状态。核准后的结算与付款尚未在电脑端开放。
- AI 购药助手：话题历史、流式回答；商品事实（名称、价格、库存）由服务端商品卡提供。
- 购物车、普通 OTC 结算、订单列表与详情；模拟支付默认关闭，只用于明确授权的隔离测试环境。
- 会员：邮箱注册、验证码登录、密码登录；资料、头像、修改密码；业务通知；门店人工文字咨询。

**uni-app 顾客端（`mall-uniapp`）**：H5 与微信小程序开发编译，复用同一套后端 API。微信原生运行和真机尚未验收。

**管理端（`admin-ui`，Vue 3 + TypeScript + Element Plus）**

- 基础资料：药品分类、门店、员工、药品档案（含会员价、线上可售、审核状态）、药品条码。
- 库存、采购：仓库与批次库存、供应商与证照、采购订单与收货。
- 会员管理：会员档案、等级、积分明细、收货地址、购物车、小程序订单。
- 处方支付管理：处方登记、处方审核、处方台账、支付单与退款单查询。
- 药店 POS：收银台、销售单、退货单、收银班次、支付明细、销售统计。
- 门店文字咨询接待；药店 AI 助手（查询药品、门店库存与预警，生成药品变更或采购草稿预览，写操作须再次确认）。
- 与药店无关的上游 AI 页面（绘画、音乐、写作、思维导图等）已停用或隐藏（PR #11 及后续提交）。

**AI 与知识库**

- 顾客 AI 只读查询当前门店允许展示的商品，不开放 AI 下单、诊断或开方；提示词和正则双层拦截越权请求。
- 药品说明书 RAG：代码已合入 `main`（提交 `d493721c` 及后续修正），包括 Qdrant 向量库、本地向量化服务 `deploy/embedding`（BAAI/bge-small-zh-v1.5）和按租户过滤的检索。语料 `deploy/knowledge/corpus.json` 是**演示语料，不是官方说明书原文**。召回效果的验证只有 `scripts/e2e-ai` 演示脚本，运行产物不入库；仓库里没有正式验收记录。

## 技术栈

| 部分 | 当前使用技术 |
| --- | --- |
| 电脑顾客端 | Vue 3、Vite，Nginx 托管静态产物并反向代理 `/app-api` |
| uni-app 顾客端 | uni-app、Vue 3、Pinia、Vite；H5 与微信小程序 |
| 管理端 | Vue 3、TypeScript、Element Plus、Vite、pnpm |
| 后端 | Java 17、Spring Boot 3、MyBatis Plus、Maven（yudao-server） |
| AI | yudao 模型管理 + Spring AI，SSE 流式输出，Qdrant 向量库，本地 embedding 服务 |
| 数据与运行 | MySQL 8、Redis 7、Qdrant 1.12、Docker Compose |
| 测试 | Node 测试、后端单元测试、Playwright（OTC 验收与 AI 演示脚本） |

## 截图

以下截图于 2026-11-11 从**本地演示环境**采集：共享 Linux 电脑上的 Docker Compose 栈，数据库为本机演示库副本，宽度 1280。采集时只放行登录请求，其余写请求一律拦截。会员昵称、头像、邮箱、手机号、AI 历史话题和员工姓名已在截图前遮挡或模糊。截图只说明页面形态，不证明业务闭环、医学内容或支付流程已经验收。

### 电脑顾客端

| 首页 | 找药购药 |
| --- | --- |
| <img src="docs/images/readme/customer-01-home.png" width="420" alt="电脑顾客端首页"> | <img src="docs/images/readme/customer-02-catalog.png" width="420" alt="找药购药商品列表"> |
| **商品详情** | **处方申请入口** |
| <img src="docs/images/readme/customer-03-drug-detail.png" width="420" alt="商品详情页"> | <img src="docs/images/readme/customer-04-prescription-entry.png" width="420" alt="提交处方申请页"> |
| **个人中心（已遮挡）** | **AI 购药助手（入口，历史已模糊）** |
| <img src="docs/images/readme/customer-05-account.png" width="420" alt="个人中心"> | <img src="docs/images/readme/customer-06-ai.png" width="420" alt="AI 购药助手"> |

### 管理端

| 登录页 | 首页 |
| --- | --- |
| <img src="docs/images/readme/admin-01-login.png" width="420" alt="管理端登录页"> | <img src="docs/images/readme/admin-02-dashboard.png" width="420" alt="管理端首页"> |
| **药品档案** | **门店管理** |
| <img src="docs/images/readme/admin-03-drug-records.png" width="420" alt="药品档案列表"> | <img src="docs/images/readme/admin-04-stores.png" width="420" alt="门店管理"> |
| **员工管理（姓名等列已模糊）** | **药店 AI 助手** |
| <img src="docs/images/readme/admin-05-staff.png" width="420" alt="员工管理"> | <img src="docs/images/readme/admin-06-ai-assistant.png" width="420" alt="药店 AI 助手"> |

### uni-app 顾客端（H5）

以下截图同样来自**本地演示环境**：`mall-uniapp` 的 H5 开发服务器（`npm run dev:h5`）连接本机后端、租户 901，用 headless Playwright 以手机视口（390×844，2 倍像素）采集。H5 普通模式只提供微信小程序登录入口，所以截图都是**未登录状态**。这些截图不代表微信原生运行或真机已经验证，两者**均未验收**。

<table>
  <tr>
    <td align="center"><img src="docs/images/readme/mobile-01-home.png" width="220" alt="H5 首页"><br>首页</td>
    <td align="center"><img src="docs/images/readme/mobile-02-category.png" width="220" alt="H5 药品分类"><br>药品分类</td>
    <td align="center"><img src="docs/images/readme/mobile-03-drug-detail.png" width="220" alt="H5 药品详情"><br>药品详情</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/readme/mobile-04-cart.png" width="220" alt="H5 购物车（未登录）"><br>购物车（未登录）</td>
    <td align="center"><img src="docs/images/readme/mobile-06-my.png" width="220" alt="H5 会员中心（未登录）"><br>会员中心（未登录）</td>
    <td align="center"><img src="docs/images/readme/mobile-07-login.png" width="220" alt="H5 登录页"><br>登录页（仅微信小程序登录）</td>
  </tr>
</table>

此前的截图仍保留在 `docs/images/`：2026-10-02 的 uni-app H5 截图和更早的管理端采购页，作为历史记录。

## 顾客与管理端流程

**顾客主流程：AI 查询药品信息 → 筛选商品 → 下单（模拟支付）→ 门店处理。**

顾客 AI 根据问题查询当前门店允许展示的商品。商品卡里的名称、价格、库存由服务端提供，回答正文不给价格；点击卡片进入商品详情，由顾客自己选择商品和数量后下单。AI 不直接创建订单。下单结果和后台状态由业务接口决定，历史回答里的价格和库存不能代替下单时的校验。

处方药需要人工药师审核；人工咨询需要本店有权限的员工领取并回复。系统不会生成假回复，也不承诺实时有人接待。

**管理端目标流程：AI 查询库存预警 → 查看依据 → 人工确认采购处理。**

AI 助手可查询药品档案和当前门店批次库存，并生成药品变更或采购草稿的预览，写操作必须人工再次确认。“低库存预警 → AI 依据 → 采购关联处理”**尚未形成已验收闭环**，仍是后续工作。

## 本地启动

日常环境是一个 Docker Compose 项目 `firstsun`，定义在 `deploy/docker-compose.local.yml`，根目录 `docker-compose.yml` 引用同一份配置。共七个服务：MySQL、Redis、Qdrant、embedding、后端、管理端、电脑顾客端。详细说明见 [README.local.md](README.local.md)。

**Windows（Docker Desktop，Linux containers）**，在仓库根目录：

| 入口 | 作用 |
| --- | --- |
| `start-local.bat` | 复用已有镜像和数据卷启动并等待健康检查，不自动构建或拉取 |
| `rebuild-local.bat` | 先构建顾客端产物和应用镜像，再启动 |
| `status.bat` / `stop-local.bat` | 查看状态 / 正常停止（保留容器和数据卷） |

**非 Windows**可以直接用同一份 Compose（需要已有镜像和 external 数据卷）：

```sh
docker compose --project-name firstsun --env-file .env -f deploy/docker-compose.local.yml config --quiet
docker compose --project-name firstsun --env-file .env -f deploy/docker-compose.local.yml up -d --no-build --pull never --wait --wait-timeout 300
```

默认只绑定 `127.0.0.1`，实际端口以私有 `.env` 为准：

| 服务 | 默认地址 |
| --- | --- |
| 管理端 | <http://127.0.0.1:28081> |
| 电脑顾客端 | <http://127.0.0.1:4193> |
| 后端健康检查 | <http://127.0.0.1:28080/actuator/health> |
| MySQL / Redis | `127.0.0.1:23327` / `127.0.0.1:26397` |
| Qdrant | `127.0.0.1:26333`（embedding 只在容器网络内） |

注意事项：

- 私有配置放在环境根目录的 `.env`，参考 [deploy/.env.thesis.local.example](deploy/.env.thesis.local.example)。示例里没有可用凭据，`.env` 不进 Git。
- 启动和重建**都不会执行 SQL**。新建隔离测试库时按 [deploy/local-migrations.json](deploy/local-migrations.json) 的 01–47 顺序导入，并记录迁移账本 `firstsun_local_migration`，完成后应有 109 张 BASE TABLE。步骤见 [数据库初始化路径说明](docs/database-init-paths.md)。
- 顾客端租户 `FIRSTSUN_TENANT_ID` 在**构建时**写入，修改后要重新构建顾客端。
- 邮箱登录需要在 `.env` 中配置 `PHARMACY_MEMBER_EMAIL_*` 和 `RESEND_API_KEY`；未配置时邮箱认证返回“暂不可用”。
- 不要用 `down -v`、全局 prune 或空库替换来处理环境问题。
- 账号和密码由环境负责人提供，不写进 README。

### uni-app 顾客端预览

```powershell
Set-Location mall-uniapp
npm ci --ignore-scripts
$env:SHOPRO_DEV_BASE_URL = 'http://127.0.0.1:28080'
$env:SHOPRO_TENANT_ID = '<租户编号>'
npm run dev:h5 -- --host 127.0.0.1 --port 4189     # H5 预览
npm run dev:mp-weixin                              # 微信开发编译，产物在 dist/dev/mp-weixin
```

回环地址只供同一台电脑使用。正式微信构建需要 HTTPS 部署地址和合法域名配置，见 [微信构建说明](mall-uniapp/README.deploy.md)。

## 目录结构

```text
FirstSun-thesis/
├─ admin-ui/              # 管理端（Vue 3 + Element Plus）
├─ customer-web/          # 电脑顾客端（Vue 3 + Vite，Nginx 托管）
├─ mall-uniapp/           # uni-app 顾客端（H5 / 微信小程序）与 API 适配器
├─ uni-h5-cli-root/       # uni-app H5 命令行构建辅助
├─ backend/               # 后端：yudao 框架、药店业务、AI 模块
├─ deploy/
│  ├─ docker-compose.local.yml  # 本机统一环境（7 个服务）
│  ├─ embedding/                # 本地向量化服务
│  ├─ knowledge/corpus.json     # 说明书演示语料
│  ├─ local-migrations.json     # 初始化清单 01–47
│  └─ sql/                      # 部署辅助 SQL（RAG 基线等）
├─ sql/                   # 业务初始化与 migrations 增量
├─ scripts/               # 启动器、readiness、OTC 验收（e2e-otc）、AI 演示（e2e-ai）
├─ docs/                  # 设计、交付、验收记录与截图
├─ HANDOFF.md             # 协作交接与证据边界
├─ README.local.md        # 本机统一环境说明
└─ start-local.bat / rebuild-local.bat / status.bat / stop-local.bat
```

## 当前进度

“已完成”指源码存在，并有对应范围的交付或验证记录；不代表全部功能在当前基线上联合通过。

| 能力 | 当前状态 |
| --- | --- |
| 管理端药品、库存、采购、会员、处方、POS 基础 | 团队项目模块，毕设复用；PR #7–#12 做了页面改进，各 PR 只做了定向测试和构建，登录后的业务点击验收仍缺。 |
| 电脑顾客端（PR #6 及后续提交） | 商品、详情、AI、购物车、OTC 结算、订单、处方申请、咨询、通知、个人中心已有页面和接口；PR #6 记录 245 条 Node 测试通过。登录后的咨询收发、处方提交仍需在当前 `main` 上重新联调。 |
| scoped OTC 账务闭环 | **只在限定范围内验证**：2026-11-05 在 readiness **隔离库**、租户 901 上，一笔“下单 → **模拟支付** → 拣货 → 核销”的 HTTP + SQL 对账达到 `RECONCILED_PASS`。这不是浏览器 UI 闭环，不是真实支付，也不是生产流程。 |
| OTC Playwright 验收脚本 | 脚本已合入（PR #19，fail-closed）；完整 UI 演示闭环尚未宣称通过。 |
| 顾客 AI、流式回答、话题历史 | 已实现，有历史本地联调和测试记录；不提供诊断或开方。 |
| 药品说明书 RAG（Qdrant） | 代码与演示语料已合入；仓库里没有正式验收记录，只有演示脚本。不使用官方说明书，不能宣称医学准确。 |
| 管理端 AI 低库存预警 → 采购处理 | 计划实现 / 整合，尚未联合验收。 |
| 本机统一部署入口 | PR #14 及后续提交合入；2026-11-11 已在 Linux 共享电脑上用同一份 Compose 起齐 7 个服务并通过健康检查，两端登录页均可打开。 |
| 微信小程序 | 有编译记录；原生运行、真实微信登录、真机仍未验证。 |
| 真实支付、退款来源关联、正式短信、生产部署 | 未完成，不在当前范围。 |

历史数据：2026-10-26（源码 `f8363422`）有 989 条自动化测试通过记录，之后没有重跑。这个数字不等于完整业务演示闭环通过。

## 开发记录与贡献

开发者先阅读 [HANDOFF.md](HANDOFF.md)、[文档导航](docs/README.md) 与 [开发规范](docs/开发规范与AI协作规则.md)。变更按“独立分支 → 定向验证 → PR 审查”推进，并说明新增、复用和未验证的范围。

| 记录 | 内容 |
| --- | --- |
| [顾客 AI 开发与验收](docs/customer-ai-development.md) | 模型与商品查询边界、流式、历史、会员资料 |
| [私有处方与业务通知](docs/prescription-business-development.md) | 材料鉴权、人工审核、订单明细与资格恢复 |
| [门店人工文字咨询](docs/store-consultation-development.md) | 会话归属、领取、消息去重、已读 |
| [数据库初始化路径](docs/database-init-paths.md) | 快照恢复与仓库 SQL 初始化，108 / 109 张表口径 |
| [后端就绪说明](docs/backend-readiness.md) | readiness 迁移与前置条件 |
| [OTC 一键测试方案](docs/acceptance/otc-playwright-oneclick-plan.md) / [scripts/e2e-otc](scripts/e2e-otc/README.md) | OTC 验收设计与脚本 |
| [AI 演示动线](scripts/e2e-ai/README.md) | 顾客端 AI、管理端 AI 与手机端联动演示 |
| [历史验收报告](docs/acceptance/reports/) | 对应历史基线，不代表当前全量通过 |
| [管理端历史交付部署](deploy/README-deploy.md) | 旧管理端独立交付说明；当前本机环境以 README.local.md 为准 |

提交里不得包含真实凭据、会员令牌、患者资料、运行日志、数据库卷或个人环境文件。仓库包含大量上游模块，源码存在不等于该模块已在 FirstSun 中启用或验收。

## License

遵守并保留 [backend/LICENSE](backend/LICENSE)、[admin-ui/LICENSE](admin-ui/LICENSE) 与 [mall-uniapp/LICENSE](mall-uniapp/LICENSE) 中的 MIT 许可和版权声明。各子项目 README 保留了 yudao、RuoYi-Vue-Pro、商城端及相关依赖的来源说明。二次开发、展示和分发时，继续保留上游许可、第三方资源授权和团队贡献记录。
