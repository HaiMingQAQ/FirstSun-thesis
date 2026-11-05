# OTC 自提订单隔离验收脚本

本目录复用冻结的 12/resume-867e27e4/harness/order-flow-readiness.cjs 中的真实 API 顺序与账务断言。order-flow.cjs 保留单笔自提下单、mock 支付、拣货、取货码核销及重复调用检查，并用只读 SQL 核对销售单、支付关联、库存批次和成本、积分记录与余额。只有真实 HTTP 与 MySQL 断言全部成立才输出 BUSINESS_PASS。

**2026-11-05 已完成原隔离环境中的单笔 OTC 模拟交易验收。** 唯一订单 163685，金额 9 元；付款、拣货、核销、销售与支付关联、库存出库 1 件、积分 27→36 均已核对，同单重复付款及重复核销未重复入账。首次运行因旧脚本的付款关联字段与出库类型断言错误退出；修正后对同一订单续验通过，原失败证据保留。离线回归 15/15 通过。该结果仅覆盖本机 API、mock 渠道和 SQL 账务，不包含真实支付、页面或真机验收。

## 首次私有配置

需要 Node.js 24、MySQL CLI、由负责人明确许可的本机隔离库和只读 MySQL 账号。把 config.example.json 的结构复制到项目根目录 .local/otc-acceptance/config.json，在该私有文件填入合成会员、门店、药品、在职员工账号、隔离 schema、MySQL server UUID 与本机端口。项目 .local/ 已被 Git 忽略。密码、验证码和 MySQL defaults 文件不放入本目录或命令参数。

environment、dailyDatabase、mockPaymentOnly、externalProvidersDisabled 是执行人的明确确认。脚本拒绝仓库内日常 Compose 的默认端口 28080/23327 和生产示例库名 firstsun_pharmacy，但不能单凭健康检查和另一条 SQL 连接证明后端实际连的是隔离库。执行前仍需独立核对后端数据源、Redis 库、关闭的后台任务、仅 mock 支付及外部提供方设置，保证 HTTP 与 SQL 指向同一个获准实例。日常 Compose 库和真实支付不能用于本脚本。mock-payment-available 还会在任何购物车/订单写入前由实际 API 再次检查。

历史旧 run ID e2e12-20261030-42eba49acb3f41c485aafcdf6e19a2b7 永久保留，不能用新 runner 再次下单。它的未知调度结果和历史最多一单预算不能因本脚本的运行 ID 或目录改变而重置。若要续测，先只读核对旧 marker、订单及相关 schema/实例，记录旧 attempt 的结论，再取得**新的明确单笔运行许可**。新许可的 ID 与 run ID 写入私有配置，confirmed 才能由负责人改为 true；每个许可最多一次新订单尝试。脚本把许可使用和运行尝试持久写入 .local/otc-acceptance/permits.json 与 attempt-<runId>.json。换 run ID 不能复用旧许可。未知付款/核销结果或已发出 worker 的失败不会自动重发；遗留 execution.lock 必须人工只读核对后处理。

## 命令

在仓库根目录运行：

    .\test-otc.bat
    .\test-otc.bat --preflight
    .\test-otc.bat --execute --permit <本次许可ID>

默认仅预检：读取配置、GET 健康端点、用 MySQL CLI 的标准输入执行只读 SELECT，核对库身份、run ID 尚无订单、会员、OTC 药品和基本库存。不会登录、发短信、改购物车、下单或支付。缺配置、服务不可达、预检不满足分别失败，不显示业务 PASS。--run-id <ID> 可覆盖私有配置中的 run ID；执行许可中的 run ID 必须与覆盖值一致。--config 仅接受 .local/otc-acceptance/ 内的私有 JSON 路径。

显式 --execute 在预检通过后才原子占用许可与运行 ID，并启动真实流程。worker 启动时核对锁、attempt 和许可台账，并在任何连接前原子创建 dispatch-once 标记；同一运行 ID 的 worker 只有一次机会。HTTP 写请求无自动重试，超时或中断保留 UNKNOWN、attempt/dispatch 标记和锁，报告会显示已落盘的最后安全阶段。重复支付与重复核销各只发送一次，必须返回成功 CommonResult 且账务快照不变；HTTP 500 不能算通过。

每次调用先在 .local/otc-acceptance/invocations/<UUID>/ 写 summary.json、events.jsonl、junit.xml，然后才可能连接服务。终端输出该目录。worker 的逐阶段摘要和 HTTP/SQL 事件也在 .local/otc-acceptance/，文件名前缀为 run ID。报告只含受控状态与非秘密元数据；原始 MySQL stderr、令牌、密码和验证码不会保存。PREFLIGHT_READY 的 JUnit 用 skipped 表示业务未执行。

退出码：0 预检就绪或真实业务通过（以 status 区分）；2 配置/许可参数；3 服务或 MySQL 不可用；4 隔离身份/fixture 预检失败；5 重复、互斥或未处理锁；6 真实断言失败；7 结果未知。任何非零结果都不是业务通过。

## 离线回归与 CI

    node --test scripts/otc-acceptance/runner.test.cjs
    New-Item -ItemType Directory -Force .local | Out-Null
    node --test --test-reporter=junit scripts/otc-acceptance/runner.test.cjs > .local/otc-runner-tests.xml

离线测试只模拟 runner 的健康响应、只读 SQL 结果和子进程退出，不触及真实 OTC。CI 可先运行这组测试，再在专用隔离环境注入私有配置并显式执行 --preflight；真实 --execute 需要单独获准的 fixture、单笔许可和人工检查，不应放入普通提交 CI。

## 已完成订单 163685 的定向续验

原 run ID otc-20261105-01 的唯一订单已经创建、mock 付款、拣货并核销。旧验收在最终 SQL 断言处失败，**不得再运行 --execute，也不得换 run ID 创建第二单**。旧报告、锁及所有 attempt/dispatch/order-create 标记保留。当前服务端以订单 pay_no 存支付单 ID，pay_order_id 兼容列可为空；审计用严格正整数 pay_no 查询真实支付单，兼容列有值时必须相等。合法出库来源只接受 20/2 或 82/6 两种 flow_type/biz_type 组合，且 in_qty=0。

负责人在已获准的原隔离环境中，可先运行只读审计：

    node scripts/otc-acceptance/reconcile-existing.cjs --run-id otc-20261105-01 --permit otc-user-20261105-01

这个命令只读取原失败摘要、原许可和标记，核对数据库 UUID、同一订单身份与完整销售/支付/库存/积分断言。成功状态 AUDIT_ONLY_PASS 的 JUnit 为 skipped，**不表示重复核销已验证**。确认只读审计后，按原许可仅执行一次：

    node scripts/otc-acceptance/reconcile-existing.cjs --run-id otc-20261105-01 --permit otc-user-20261105-01 --replay-once

重放入口再次完整审计，核对原订单已经完成且付款、门店/会员/remark/orderNo 匹配；随后以原门店员工的正常后台登录核销同一订单一次，比较完整 SQL 快照不变。核销前原子写 reconcile-replay 标记；任何超时/未知结果都不重发。新的 summary.json、events.jsonl、junit.xml 在 .local/otc-acceptance/reconciliations/<UUID>/，原失败摘要和报告不覆盖。

本单只读审计报告为 `.local/otc-acceptance/reconciliations/61cdca11-4675-4934-b987-5de5ed387b20/summary.json`；同单重放报告为 `.local/otc-acceptance/reconciliations/574dc78a-1bc0-498f-b395-245d7e9ef8a9/summary.json`，后者 JUnit 为 1 项、0 失败、0 跳过。总管核对 HTTP 记录后收尾为 `RECONCILED_PASS`，仅释放本次执行锁；原失败报告、创建/派发/重放标记和已消耗的许可均保留，原 run ID 不可再执行。收尾记录在 `D:/github-3/FirstSun/.local/otc-acceptance-closeout.json`。

当前私有配置对应的单笔许可已消耗。后续新交易测试须设置新的 run ID 和明确的单笔许可，再用同一个 `test-otc.bat --execute --permit <许可ID>` 运行；已有环境、私有凭据和合成 fixture 可复用，不清库、不复制工作区，也不自动重发旧单。
