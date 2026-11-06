# FirstSun OTC：Playwright UI 一键测试方案

> **仅方案 · 尚未实施 UI · 尚无 UI PASS · 不含完整 Playwright 用例实现。** 本次写作只起草本文，不编写测试实现、不启动服务、不执行登录或业务写入，也不自行提交、推送或创建 PR；随后可由执行者按已批准流程仅提交、推送本文并开草稿 PR。草稿 PR 只含本文，非 UI 成果；除非海冥明确确认，否则禁止合并。下述阶段、命令和通过标准均为未来实施设计。
>
> **基线 commit：`04b3659d4c007fe47adaeb911367a942cfbfacc2`（`04b3659d`）**，由只读 `git rev-parse origin/main` 核对；当前 HEAD 同值。工作分支为 `codex/docs-otc-playwright-plan-20261106`，调查日期为 2026-11-06。
>
> **真实性原则：无证据不写成果。** 源码存在、HTTP 就绪、账务通过和浏览器通过分别报告；未执行写“未执行”，不能将既有 HTTP/SQL scoped 账务闭环改称浏览器闭环。

本文承接 [HANDOFF.md](../../HANDOFF.md) 与 [PR #17](https://github.com/HaiMingQAQ/FirstSun-thesis/pull/17) 的事实边界。Git 历史可核对 #17 的交接文档提交为 `fec432efa01a6bf7b8e9145608058e1bba5b2942`，已包含在本基线中；本文补充其“Playwright UI E2E 未做”的后续方案。HANDOFF 正文仍保留撰写当时的 `713248a5` 等版本时点，不能当成本次基线；本次也不重写其中历史记录。

已只读核对仓库外旧方案及私有证据。旧方案基线为 `d955f9a7`，其中 `163/407` 夹具及现场查询结论没有在本次连接数据库重验，仅作兼容候选。私有证据仅引用以下已核验摘要，不复制 closeout 全文、原始 JSON、秘密或私有目录细节：

| 已运行证据字段 | 可引用事实与边界 |
| --- | --- |
| runId | `otc-20261105-01` |
| 租户／门店 | `901 / 9101`，已核验账务路径的基线 |
| 订单 | `orderId=163685`，`orderNo=WX-9101-20261105-0001` |
| 路径／结果 | 下单 → 模拟支付 → 拣货 → 核销，`RECONCILED_PASS` |
| liveScope | Local API / mock / SQL，无 browser、无真实支付 |
| 脚本状态 | `scriptsUncommitted=true`，不能声称该轮验收脚本已入库 |

这份历史结果仅证明所述 scoped 账务路径；不证明下文六阶段曾在浏览器执行，也不证明当前运行服务、双端登录或测试环境已准备好。本次没有探测监听端口、调用服务或查询数据库。

## 1. 覆盖范围：一笔普通 OTC 自提贯穿双端

目标是给已有账务验收补齐浏览器可见性、真实 UI 登录与交互：顾客端 `customer-web` 下单及 mock 支付，管理端 `admin-ui` 拣货及核销，同一笔订单贯穿。只做普通 OTC、到店自提（`orderType=0`）、无处方；不增加第二笔订单，不要求先开发一套完整多业务脚本。当前顾客端固定 `usePoints=0`，不覆盖积分抵扣正例。

### 当前仓库核对到的入口

| 入口与来源 | 当前静态契约 | 实施时怎么使用 |
| --- | --- | --- |
| [顾客 App](../../customer-web/src/App.vue)、[商品卡](../../customer-web/src/ProductCard.vue) | hash 路由：`#catalog`、`#detail?id=<drugId>`；商品卡按钮可访问名称为“加入购物车：<商品名>”；“登录”打开登录弹窗 | 浏览当前测试门店，按唯一商品身份定位普通 OTC；只加本轮商品。商品详情可作为检查点，不进入 AI／处方入口 |
| [交易页面](../../customer-web/src/TradePages.vue) | `#cart` → “去结算” → `#checkout` → “到店自提” → “创建订单” → `#order-detail?id=<orderId>`；`#orders` 为“我的订单” | 校验门店、商品、数量、估算金额；创建后以服务端实际应付为准，不把估算当账务值 |
| 同上及 [交易逻辑](../../customer-web/src/trade-core.js) | 详情页“模拟支付（测试）”→“测试模拟支付”弹窗→“确认”；“刷新订单”；状态文案为“待支付／待拣货／拣货中／待自提／已完成” | 验证能力提示和按钮可用，支付后刷新回读。自提没有顾客自助核销，不使用配送“确认收货”代替核销 |
| [管理路由](../../admin-ui/src/router/index.ts)、[静态登录路由](../../admin-ui/src/router/modules/remaining.ts) | 管理端是 history 路由，登录入口 `/login`，并非顾客端 hash 路由 | 新浏览器上下文通过 UI 登录药店租户，核对实际 base path 与登录后菜单 |
| [根菜单定义](../../sql/migrations/20260908_a_pharmacy_module_init.sql)、[会员菜单定义](../../sql/migrations/20260911_f_pharmacy_member_menu.sql) | 菜单链 `/pharmacy-base` → `member` → `order`，据此得到订单页 `/pharmacy-base/member/order`；组件 `pharmacy/member/order`，菜单名“小程序订单” | 这是仓库菜单定义推导的路径，运行库菜单、套餐、base path 与授权仍须实施前确认；不假定一定存在 `/pharmacy/member/order` 页面路由 |
| [管理订单页](../../admin-ui/src/views/pharmacy/member/order.vue)、[API 包装](../../admin-ui/src/api/pharmacy/member/order/index.ts) | “订单号”输入→“搜索”；同单行“详情／开始拣货／完成拣货／核销”；核销弹窗“提货码／确认核销” | 精确限定订单号对应行，禁止点击列表第一条。拣货有确认弹窗；待自提且已支付才显示“核销”。管理状态标签来自字典，实际文案实施前确认 |

两端用不同 browser context，禁止注入 token、伪造会话、预填 storageState 绕过本轮 UI 登录，禁止拦截业务请求返回假成功。优先 role、label、可访问名称与现有 `data-order-id` 定位；不加 `data-testid` 为默认。若确需改 UI，另列最小白名单授权，不纳入本文交付。

### 每阶段的 UI 与账务关联

顾客 API 完整前缀为 `/app-api`，管理 API 为 `/admin-api`。下表状态数字来自当前交易逻辑和后端流转；它们是拟验证预期，不是本次执行结果。

| 阶段 | 浏览器动作及前后状态 | 后端动作及只读核对 |
| --- | --- | --- |
| 创建前 | 双端登录身份正确；购物车仅本轮商品，结算选择自提 | 记录 runId、tenant/store/member/drug、数量、价格、库存和积分基线；只读核对可售性、权限、mock 能力。此时尚无 orderId，后续回填关联 |
| 顾客下单 | 点击一次“创建订单”；详情出现实际订单号、应付金额、“待支付／未支付” | `POST /app-api/member/wx-order/create`；保留响应 orderId，随后 `GET .../get?id=...`、`GET .../page` 回读同单，预期 `status=0/payStatus=0`。不可重试创建结果不明的请求 |
| mock 支付 | “模拟支付（测试）”→“确认”；刷新后“待拣货／已支付” | `POST /app-api/member/wx-order/simulate-pay?id=...`；预期 `1/1`。用同单只读 API/SQL 验证支付成功、金额与库存出库；核对自动业务通知结果，不以手工重放掩盖通知失败 |
| 管理拣货 | 搜索同一订单；“开始拣货”并确认，`1→2`；“完成拣货”并确认，`2→3`；顾客刷新为“待自提” | `PUT /admin-api/pharmacy/member/order/start-picking?id=...`、`finish-picking?id=...`；GET `/page`、`/get?id=...`、`/line-list?wxOrderId=...` 与只读 SQL 核对；支付保持成功，不能再扣一次库存 |
| 管理核销 | 从顾客同单详情读取取货码，在管理端填写“提货码”→“确认核销”；双端刷新同单为完成 | `PUT /admin-api/pharmacy/member/order/verify`，query 参数 `id,pickupCode`；核销员工由服务端从当前账号解析。预期 `status=4/payStatus=1`，核对销售、销售支付、完成时间及按规则赠分，不重复扣库 |
| 跨端核对／重放 | 顾客列表与详情、管理同单行与详情都指向同一订单；重放后终态不回退、不重复推进 | 对账与重放前后均只读 GET/SQL 取证；安全重放动作本身是受控业务请求，不能叫“只读”。不重新创建订单，不用 SQL 改状态 |

每阶段记录 `runId + tenantId + storeId + orderId + orderNo/outTradeNo`，支付出现后补 `payOrderId`、商户订单号及渠道交易号映射，核销后补销售单关联。当前服务以 `orderNo` 设置支付 `merchantOrderId`；报告中的 out trade no 应显式映射到该业务订单号，不能把支付内部 ID、`payNo` 或渠道号混作同一字段。历史订单号仅用于引用既有证据，未来新单不得复用该历史订单或硬编码其号码。

账务断言复用现有 HTTP/SQL 验收层及已核验 scoped 对账口径，不在 UI 层重写另一套记账语义：支付时扣库，核销时转销售并按规则赠分，核销不再次扣库；金额用整数分／精确小数比较，业务元与支付分显式转换。积分固定以配置和订单快照为准，不凭空固定赠分数；`usePoints=0` 应无本单积分抵扣。按同单关联核对支付／扩展、出库分配与流水、销售／销售支付和积分事件的唯一性、金额及数量；多批次情况下按集合及合计核对，不凭空要求每种明细只有一行。

SQL 断言仅允许参数化白名单 `SELECT`，使用只读账号，按租户和本单关联限制范围；禁止 INSERT/UPDATE/DELETE、DDL、回填销售、改库存、改状态或预造成功记录。准备数据的 seed 与验收只读断言分离。轮询设置时限，超时即失败，不能等待无限长或只凭 toast／HTTP 200 判成功。

源码可行性依据为 [AppWxOrderController](../../backend/yudao-module-pharmacy/src/main/java/cn/iocoder/yudao/module/pharmacy/controller/app/member/AppWxOrderController.java)、[管理 WxOrderController](../../backend/yudao-module-pharmacy/src/main/java/cn/iocoder/yudao/module/pharmacy/controller/admin/member/WxOrderController.java)、[WxOrderServiceImpl](../../backend/yudao-module-pharmacy/src/main/java/cn/iocoder/yudao/module/pharmacy/service/member/WxOrderServiceImpl.java)、[AdminWxOrderService](../../backend/yudao-module-pharmacy/src/main/java/cn/iocoder/yudao/module/pharmacy/service/member/AdminWxOrderService.java) 与 [销售入账服务](../../backend/yudao-module-pharmacy/src/main/java/cn/iocoder/yudao/module/pharmacy/service/member/WxOrderSalePostingService.java)。当前源码已有模拟支付 body 和 `/payment-notify` 接收入口，旧 HTTP README 的对应缺口描述不能直接当作当前结论；源码存在也不等于运行验证通过。不使用已废弃的 `/update-status`。

## 2. 前置条件：环境、登录与数据归属

推荐使用隔离测试库／隔离演示栈，避免测试写入污染共享演示数据，但不因此擅自复制仓库、创建日期 Docker 项目或新栈。先复用已有且获授权的隔离环境；日常 Compose 统一为 `firstsun`，入口为 [deploy/docker-compose.local.yml](../../deploy/docker-compose.local.yml)、根启动脚本与 [scripts/firstsun.ps1](../../scripts/firstsun.ps1)。若复用无法隔离业务写入，再说明原因，由海冥确认资源与生命周期后安排隔离。不得绑死共享演示库；仅有共享环境而未获业务写授权时停在 readiness。

| 环境口径 | 后端 | admin-ui | customer-web | MySQL | Redis |
| --- | --- | --- | --- | --- | --- |
| 共享演示端口记录 | 48080 | 5173 | 4190 | 3307 | 6379 |
| 曾用 readiness 隔离端口 | 18482 | 实施前确认 | 实施前确认 | 23347 | 26381 |

这些是历史入口记录，不是当前监听证据，也不是新端口分配建议。仓库 Compose 还有变量化宿主映射，不能将容器内部 `48080/6379` 直接视为宿主监听。启动前先只读核对真正监听、进程／容器归属、运行版本与本次源码、健康接口实际 HTTP 200/UP、前端静态版本、代理目标、租户、DB 主从连接、Redis 实例及 db、支付回调环境变量。只记录脱敏配置指纹，不输出 env 全文；不自造端口映射、不抢占已有端口，不混接共享／readiness／其他历史库。

实施前的具体门槛：

1. **夹具基线**：优先 `tenant=901/store=9101`，原因是该账务路径已有证据；仍须核对隔离实例里租户启用、门店、在线普通 OTC、有效批次与可售库存、支付 mock 应用／渠道、通知地址及积分规则。旧 `163/407` 仅兼容候选。[演示用户说明](../演示用户.md) 描述的是该旧租户与员工绑定，不能证明 901 登录身份已就绪，不复制其中密码。
2. **真实双端登录**：顾客用 [邮箱密码表单](../../customer-web/src/EmailLogin.vue)，或在已批准的本机模式中用 [LocalLogin](../../customer-web/src/LocalLogin.vue)／[PasswordLogin](../../customer-web/src/PasswordLogin.vue)；管理端用 [LoginForm](../../admin-ui/src/views/Login/components/LoginForm.vue)。执行者安全配置测试身份，浏览器实际输入并请求后端验证；不得写死凭据。优先已有合成密码身份，避免真实邮件／短信；若采用本机验证码，要真实请求与消费验证码，并满足六位格式，值从受限配置注入。管理端租户名、验证码配置及账号权限实施前确认；不可绕过验证码或认证刷绿。
3. **核销权限**：管理账号具备订单 query/update/verify 权限，并绑定同租户、同门店的在职员工。仅有租户管理员角色不代表能核销；菜单可见也不代表 API 授权通过。
4. **只允许 mock 支付**：顾客端 [config.js](../../customer-web/src/config.js) 要求 `local-acceptance` 模式、HTTP loopback；[Vite 配置](../../customer-web/vite.config.js) 要求显式 `FIRSTSUN_BACKEND_URL`，并用 `FIRSTSUN_TENANT_ID=901` 覆盖默认 163。若使用本机手机号 UI，另核对 `FIRSTSUN_LOCAL_TEST_LOGIN` 门禁。后端要求 `firstsun.miniapp.mock-payment-enabled=true`，且所有 active profiles 均在 `local/dev/test` 内；登录后 `GET /app-api/member/wx-order/mock-payment-available` 必须返回 true。默认 Compose 关闭 mock，不能假定启栈即满足。能力不符在下单前阻断，禁止回退真支付。
5. **数据独立唯一**：每轮唯一 runId、专用会员／商品或经批准的专用数据范围，备注只放非敏感运行标记；不使用他人购物车、订单或库存做成功夹具。seed 如需补数据，仅在获授权的隔离库走既有支持方式，不改库结构、不扩后端、不预造支付或终态；ID、商品价格及数量在实施前核验，不照搬旧方案固定值。
6. **谁启动谁负责清理／交还**：运行清单记录操作者、资源归属、启停前状态与保留期限；只结束本轮拥有的进程，保留数据库持久卷。禁止 `down -v`、全局 prune、清库、空库替换，以及合并／覆盖原生历史库或旧 Docker 库。成功测试单可保留为专用验收记录；失败单先留证并停止写入，恢复／业务清理由指定操作者按拍板策略处理，不能默认退款、补库或删单。结束报告说明已恢复项、保留项、负责人及剩余影响。

## 3. 拟议目录、配置和“一键”接口

拟议 `scripts/e2e-otc/` 为独立子包，承担 UI 驱动与编排；[scripts/miniapp-http-acceptance/](../../scripts/miniapp-http-acceptance/README.md) 是 HTTP/SQL 账务层参考与复用来源，现有 `accept.cjs` **未覆盖 UI**。该 README 仍保留旧工作区、旧资源启停／删卷命令和两笔支付范围，不能原样照跑：它不含完整员工履约覆盖，不能作为本次“一笔 UI 单”的前置创建器；也不得沿用其删卷策略。

拟议子包只需小型 harness、Playwright 配置与单条业务流程、现有对账层适配接口。可先在已有授权环境上验证 readiness、已批准 seed 的就绪性、既有同单只读对账及安全 replay 接口，再接 UI；不得为准备 harness 额外创建第二单。私有 scoped 脚本需由协调者脱敏移交并评审接口后复用，`scriptsUncommitted=true` 不能当成当前仓库已有可调用能力。对账接口必须接收 UI 新建的 orderId/orderNo，不能内部再下单；本步不提供完整脚本或测试实现。

| 拟议职责 | 输入／输出约定 |
| --- | --- |
| setup | 安装锁定的 Playwright 浏览器、验证工具依赖；不隐式启栈或写业务数据。未来子包需自带选定包管理器的锁文件 |
| readiness | 读取仓库外私有配置，核对目标环境、版本、健康、租户、权限与 mock；不通过即停止，不下单 |
| config | 两个 browser context；初期 Chromium、单 worker、`retries=0`，禁止测试框架失败后整链自动重试；超时及账务轮询上限明确 |
| oneclick | readiness → 双端 UI 登录与一单流程 → 同单只读对账 → 经确认的同单重放 → 终态复核 → 归档。环境及已授权 seed 已准备好才执行，不隐式申请新资源 |
| reports | 仓库外固定运行根目录下按 runId 分目录保存 summary、机器可读结果、HTML/JUnit、阶段截图与受限 trace；不复制整套项目 |

**以下命令仅展示未来实施接口，目前不存在，当前不可直接运行。** 推荐此独立子包选 npm；这不改变 admin-ui 原有 pnpm 约定。`E2E_ENV_FILE` 由执行者设为仓库外受限配置文件路径，命令行只传路径，不传密码。安装完成与一键业务执行分开，避免每轮重复安装或启栈。

```bash
# 未来首次安装：前提是子包及 package-lock.json 已经实施并确认
npm --prefix scripts/e2e-otc ci
npm --prefix scripts/e2e-otc run setup -- --env-file "$E2E_ENV_FILE"

# 未来只读预检；非零时停止，不执行下一条业务入口
npm --prefix scripts/e2e-otc run readiness -- --env-file "$E2E_ENV_FILE"

# 未来一键业务入口，内部必须再次检查 readiness
npm --prefix scripts/e2e-otc run oneclick -- --env-file "$E2E_ENV_FILE"
```

若海冥选择 pnpm，则为该子包提供对应锁文件，以 `pnpm --dir scripts/e2e-otc install --frozen-lockfile`、`pnpm --dir scripts/e2e-otc run setup --env-file "$E2E_ENV_FILE"`、`pnpm --dir scripts/e2e-otc run readiness --env-file "$E2E_ENV_FILE"`、`pnpm --dir scripts/e2e-otc run oneclick --env-file "$E2E_ENV_FILE"` 替换上组命令，二选一，不混装双锁文件。

开发诊断时可在**未来子包目录**内使用下列底层命令形状；避免在仓库根目录由 npx 临时下载未知版本。此时配置须从 `E2E_ENV_FILE` 环境变量加载，并强制相同 readiness 和对账门禁；该命令只是浏览器 runner，不替代一键归档入口，也不能单凭其退出码宣称全链通过。

```bash
npx --no-install playwright test --config=playwright.config.ts --project=chromium --workers=1 --retries=0
```

拟议配置参数：`E2E_CUSTOMER_BASE_URL`、`E2E_ADMIN_BASE_URL`、`E2E_API_BASE_URL`、`E2E_TENANT_ID`、`E2E_STORE_ID`、`E2E_RUN_ID`、`E2E_ARTIFACT_ROOT`，以及私有凭据引用、只读 DB 连接引用、批准的夹具／重放方式。harness 自有变量与既有前端 `FIRSTSUN_*`、管理端 `VITE_*` 配置分别校验，不能误以为运行时改变量会改变已构建前端。fixture、seed 策略、schema 及配置版本需写入脱敏运行清单。

一键入口建议退出码：`0` 仅全链满足 PASS；`2` 表示前置阻断，未下单；`1` 表示执行／断言失败；`3` 表示证据不全或收尾异常。具体码值待实现定稿，但任何失败、跳过、报告归档失败都必须非零。保留原业务失败原因；截图失败不能覆盖它。账务失败不得被 Playwright 的成功退出覆盖。

本次写作只起草本文，不自行提交、推送或创建 PR；随后可由执行者按已批准流程仅提交、推送本文并开草稿 PR（draft PR），该 PR 只含本文，非 UI 成果，除非海冥明确确认，否则禁止合并。完整脚本将来是否入库另行拍板。凭据、配置、报告、截图、trace、视频及浏览器会话默认仓库外；原始 trace 可能含 token，必须受限保管，分享前另做脱敏副本。`.gitignore` 只能辅助防误入库，不能用于提交秘密，本步也不改它。报告不得写密码、验证码、Authorization、支付密钥、完整手机号或私有证据路径；不自动上传附件。

## 4. 成功判定、阶段验收与明确不做

建议未来总结果命名 `PASS_OTC_UI`（具体命名待定）。只有同时满足以下条件，才允许产生该标记：

- 两个真实浏览器上下文分别经顾客与管理 UI 登录；同一笔本轮普通 OTC 自提订单完整经过创建、mock 支付、开始／完成拣货、核销，所有阶段未跳过。
- 双端 UI 文案、金额、门店和身份与同单只读 API/SQL 一致；现有账务层对支付、库存、销售、销售支付、积分及通知的适用断言通过。账务层失败或未验证，UI 总结果不得通过。
- 关联清晰的同单重放实际执行：终态、完成时间及业务金额／数量不被再次推进，不重复支付、出库、销售或赠分。仅核对“按钮已隐藏”不能替代幂等证据。
- 每阶段均有运行标识、订单关联、时间、UI 与账务检查结果及必要脱敏证据；失败／跳过／证据缺失均非 PASS，报告和收尾状态完整。

**重放的实施边界**：完成态 UI 会隐藏支付／核销按钮，因此建议通过已登录身份的受控 HTTP 适配器，对本单 mock 支付／核销及适用通知做同单重放，明确标为“HTTP 幂等补充”，随后双端 UI 刷新再核对。重放是写请求，需要已批准的环境与触发机制，不能从“服务看起来幂等”直接推定执行安全。具体动作、参数、租户上下文、次数及通知调度稳定窗口实施前确认；先顺序重放，并发是否需要另定。`start-picking/finish-picking` 有状态前置限制，不要求它们在完成态返回幂等成功；不重放创建、不改 DOM 强开按钮、不写 SQL 触发重放。自动通知须先有独立结果，手工通知不能补成原流程成功证据。

下表**全部待实施**；已有 `RECONCILED_PASS` 不能填入任何一行作为浏览器执行结果。

| 阶段 | 待实施验收点 | 应留证据／停止条件 | 当前状态 |
| --- | --- | --- | --- |
| 准备 readiness | 版本、监听、代理、环境归属、夹具、双端登录条件、mock 与只读对账就绪 | 脱敏环境清单；不满足则阻断且不下单 | 待实施 |
| 顾客下单支付 | UI 登录、购物车、自提创建一次、mock 支付；`0/0→1/1` | 同单 ID／订单号映射、前后页面及账务读数；结果不明停止写入 | 待实施 |
| 管理拣货核销 | UI 登录、按同单号检索、`1→2→3→4`、取货码核销 | 操作前后页面、员工归属、同单 API/SQL；任何一步失败即停 | 待实施 |
| 跨端核对 | 双端同单已完成、已支付、金额及账务关联一致 | 只读对账摘要；HTTP／SQL 失败则不能 UI PASS | 待实施 |
| 同单重放 | 批准机制实际重放，业务账不增、终态不重复推进 | 关联本单的请求摘要、重放前后账务快照及双端刷新结果 | 待实施 |
| 归档报告 | 阶段齐全、脱敏、退出码正确、资源恢复／保留说明清晰 | 仓库外报告与受限证据索引；缺证或收尾异常非零 | 待实施 |

`RECONCILED_PASS` 只代表已核验的既有账务层结果；历史 `PASS_SCOPED`、HTTP readiness、静态构建和单测数量都不能替代 `PASS_OTC_UI`。未来运行失败时如实标注失败阶段及后续未执行项，不重跑整链制造新单，不修改断言刷绿，不把只读 DB 成功改报 UI 成功。

明确不做：真实支付、外部短信／邮件／AI 调用、向量检索、退款退货、微信真机、处方审方全链、配送确认收货、积分抵扣正例、生产部署、改库结构或扩后端。发现上述依赖或产品缺陷时保留阻断，另立授权范围处理。

## 5. 风险与海冥需逐项拍板

以下是未来实施决策表，本次文档不表示已经获准执行这些操作。

| 拍板项 | 建议默认值 | 风险与需明确事项 |
| --- | --- | --- |
| 隔离栈 vs 临时挂共享演示端口 | 优先复用已有获授权隔离测试库／栈；不默认写共享演示库 | 混接数据库、缓存或回调会污染他人数据；新隔离资源须先说明复用不足，再确认资源清单与端口，不自行另建日期项目 |
| 901 vs 旧 163 夹具 | 以 `901/9101` 为账务基线，实施前重新核对 UI 夹具 | 901 的管理身份／菜单尚未验证；163/407 仅兼容候选，切换需确认全套账号、门店、库存与支付关联，不能只改 tenant-id |
| 测试脚本是否入库 | 独立子包最小实现，评审后再决定入库；本步只交本文 | 私有脚本尚未提交，移交／复用范围不明；若不入库，需明确受控保管位置、版本、可重复执行者和维护人 |
| `data-testid` 白名单 | 默认不加，优先文本／role／label 与现有订单标识 | 字典或重复文本可能导致误点；确需标识时先列具体组件与字段，另授权 UI 修改，不能顺带改产品行为 |
| seed 与配置开关 | 优先已有合成夹具及现有开关，不改生产逻辑；缺数据只在批准隔离库补最小 seed | 登录、员工绑定、套餐、mock 或通知不足会阻断；需确认允许的数据写范围和初始化操作者，禁止 DDL／后端扩展／预造成功单 |
| 隔离栈生命周期与操作者 | 指定一名操作者，复用工作区与 `firstsun` 入口，记录谁启动谁交还 | 共享机器并行启停可能破坏他人工作；明确保留期限、可停止资源与恢复状态，持久卷保留，不整库备份／替换 |
| mock 幂等重放方式 | 已登录身份的受控 HTTP 同单顺序重放；先确认自动通知，再读快照比较 | 完成态 UI 按钮不可再点；需确认具体机制、次数、通知并发窗口及预期响应，不能只假设安全或用手工回调掩盖原失败 |
| UI 失败清理／遗留单 | 立即停止业务写，先只读取证并保留失败单；不自动重跑 | 下单后失败可能遗留待付／已付库存影响；指定人工处理负责人，是否走合法取消等流程另确认，不 SQL 回滚、不默认退款或补库 |
| 凭据注入与登录选择 | 执行者提供仓库外受限配置，优先合成密码账号，浏览器真实 UI 登录 | 901 账号可用性未确认；验证码或真实邮件可能引入外部调用，须选定无外部发送的已支持方案，不绕过认证 |
| 证据存放与分享 | 仓库外固定运行根目录、按 runId 分目录；原件受限，仅分享脱敏摘要 | trace／视频／HTML 附件可能带 token、取货码和身份资料；确认访问者、保存期及脱敏责任，不提交原始 JSON／配置／证据 |
| 包管理器及总结果接口 | 子包默认 npm、单 worker、零整链重试；建议 `PASS_OTC_UI` 与非零失败码 | 如选 pnpm 则替换整组命令并单独锁定；需确认对账适配契约与报告结构，不能仅转发浏览器进程退出 0 |

实施开始前先把上述选择收敛成一份脱敏运行清单，再补最小 harness 与 UI 流程；未确认项保留“实施前确认”，不在方案中编造已可运行能力。本文仅完成只读源码／文档与限定证据摘要核对，未获得新的浏览器、HTTP 或 SQL 运行结果。
