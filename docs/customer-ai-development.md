# 顾客 AI 开发与验收

## 复用与新增

复用现有 AI 模型/API Key 管理页、Spring AI 模型工厂、会员令牌和租户过滤、药品档案、门店、可售库存 SQL、商品详情及 Redis 限流框架。新增顾客专用 `/app-api/pharmacy/ai/consult`、查询幂等表和小程序 AI 页面；没有向顾客开放管理端 AI 的写工具。

当前模型解析 `searchDrugs/getDrugDetail/advice/chat/refuse` 白名单意图；价格、库存、商品 ID、来源和时间由数据库查询与服务器组装。查询只提供上架、启用、已审核、有可售库存的普通非处方、非特管、非含麻黄碱商品；过期、质量异常与冻结库存沿用既有 SQL 排除。查询词必须来自本次问题或有限历史中的用户输入；症状词被模型改写时降级为不查询商品的一般追问，商品 ID 必须来自本次用户明确输入。允许常见症状的护理参考、澄清问题及有资料依据的条件性非处方参考；不作确诊、个体剂量、开方或写操作。

会员与当前租户由服务端鉴权；门店属于当前租户且启用。请求 ID 在租户及会员范围唯一；同 ID 改内容返回业务错误，处理中可重查，完成后复用原响应。每租户/会员每分钟最多 10 次解析请求，复用已有 Redis，不缓存咨询正文。数据库只存内容摘要和安全响应；接口关闭请求/响应正文访问日志。页面换账号或登出清空当前会话内容，跨账号迟到响应丢弃。

## 本地配置

真实配置位于 `.tmp/business-private/ai-source.env`，该目录已被 Git 忽略。由本地后端进程或独立测试容器加载；不要复制进源码、SQL、前端或提交。服务商 DeepSeek，基础地址 `https://api.deepseek.com`，模型 `deepseek-flash`；模型注册复用 36 号迁移的环境占位符。用户咨询原文会发送给配置的模型服务商，页面提供问药参考与只读商品查询。永久本地环境加载 Git 忽略的 `deploy/.env.thesis.local`，不把密钥发送到前端。

## SQL 与环境

新库沿用 01–39，再执行 40 `20261001_a_customer_ai_consult.sql`。既有库不可依靠 Docker init 自动更新；需单独执行增量，本轮未操作旧库或云端。联调仅使用新项目 `firstsun-thesis-business-20261001`、新数据库 `firstsun_thesis_business_20261001`、新 MySQL/Redis 卷和随机凭据，HTTP `http://127.0.0.1:26190`。数据库/Redis 在独立内部网络，仅后端另接模型出站网络。模拟支付开关为 false；不能以 local profile 代替环境隔离。

本轮测试基底抽取现有框架表结构与药店初始化，应用购物链路所需迁移、36 和 40，再导入独立合成 seed；不将测试 seed 放入通用初始化链。首次初始化的测试标记 CHAR(32) 与随机标记长度不匹配，在独立测试标记表修正为 CHAR(64)，业务迁移没有更改。

## 验收

- 后端 `CustomerAiIntentServiceTest` 5 项、`CustomerAiConsultServiceTest` 7 项及 `PrivateBusinessLogPathTest` 1 项通过；模型异常内容脱敏、身份/租户拒绝、重复请求、过期记录和最终写入竞争均覆盖。
- 真实适配器 stub 测试通过，覆盖请求门店、45 秒超时、登录门禁与网络失败；这不是 HTTP/模型验收。
- H5 构建通过；独立合成 HTTP UI 测试通过 320/375/430px、长药品名/金额、输入栏、防重复发送、失败/PENDING 重试、网络错误、游客门禁。截图位于 `.tmp/business-ai-ui/`，已实际查看窄屏截图。
- 真实后端 + 真实 DeepSeek + 合成独立数据库：登录、查询商品 DB 价格库存、重复返回、编号内容冲突、游客、跨租户、无效门店、诊断/写操作拒答通过。详情能力沿用现有商品页面，AI 卡片只跳转、不加购。
- 真实商品边界联调通过：处方药、特管药、冻结库存、过期批次、其他租户商品排除；40 号迁移重复执行成功。
- 真实 Redis 限流：同会员 11 个独立请求，前 10 个处理、第 11 个安全失败；使用拒绝写操作意图，未调用供应商。
- 真实 H5 页面联调通过：浏览器只将 API 请求转发至独立 localhost 后端，未合成响应；AI 卡片显示 DB 价格 12.34、库存 50，跳转真实商品详情。截图 `.tmp/business-ai-ui/ai-real-http.png`，已实际查看。时间戳已格式化，定向适配器测试新增毫秒/ISO 时间回归并通过。
- 本轮微信开发模式编译通过，导入目录 `D:\github-3\FirstSun-thesis\mall-uniapp\dist\dev\mp-weixin`，包含 `app.json` 和 AI 页面；启动路由 `pages/pharmacy/index`。

证据（全部本地忽略目录）：`.tmp/business-ai-package.log`、`.tmp/business-ai-h5.log`、`.tmp/business-ai-weixin.log`、`.tmp/business-ai-ui/report.json`，以及 `.tmp/business-integration-20261001/ai-http-report.json`、`ai-boundary-report.json`、`ai-rate-report.json`、`ai-real-ui-report.json`。

## 当前限制

### 02 审查修正（2026-10-01）

限流拒绝日志仅记录方法签名，不再读取或输出方法参数；私密路径在判断前去除各段路径参数。AI 商品白名单同时限制 `drug_type IN (1,2)` 与非处方、非特管、非含麻黄碱标志。过期重放的条件更新失败后返回并发胜者的持久化结果；同会员刷新令牌保留回答，换账号仍清空并拒绝迟到结果。本地根 Compose 也已按 39→40 挂载增量。

本次选定后端测试共 27 项通过（AI 14、日志 2、既有小程序权限 5、处方新增 6），打包通过，证据 `.tmp/business-rx-package.log`。当前 AI 合成界面测试 9 项通过，增加同会员令牌变化后的回答保留，截图已更新。真实模型/后端/Redis复核通过：特管类型且三个标志均为零仍不返回，限流拒绝及带路径参数请求的原文未进入容器、访问或错误日志，证据 `ai-review-http-report.json`。此前真实联调证据是上个版本结果，以上新增复核不替代全部旧链路重跑。

查询最多读取 21 条候选并返回 20 条有货商品；超限明确提示缩小查询，不宣称全店汇总。重复响应是带时间的历史结果，价格/库存最终以下单服务校验为准。此前版本不保存问答历史、不提供症状建议；本次升级改为客户端内存中的有限上下文，仍不提供处方、订单或诊疗工具。未验证微信开发者工具、真实微信登录或真机。模型超时返回安全失败；阻塞供应商调用的底层取消能力沿用现有客户端，后续远程部署需确认超时/并发预算、查询记录保留周期和供应商数据处理要求。


## AI 体验升级（2026-10-01，待审查）

### 复用与变化

- 复用模型工厂、配置、门店商品及库存过滤、会员/租户权限、40 号查询幂等表和原 Redis 每会员限流。新增 `POST /app-api/pharmacy/ai/consult/stream`，保留原非流式接口。没有新增 SQL 或 Redis 服务。
- 常见感冒先核对症状、持续时间、成人/儿童及过敏等情况；不明确腹痛先追问。明确危险症状优先提示就医。不是对所有症状直接推荐品牌，也不承诺在线医生已接通。
- 症状候选匹配现有药品说明，仍限定审核、上架、普通非处方及可售库存；没有匹配记录时不编造“999感冒灵”等商品。药品说明的完整性与临床准确性仍需人工维护，本地演示档案不能作为真实疗效证据。
- 模型真实 `stream` 输出由服务端按句检查后 SSE 推送；检测到推荐句时不透传模型原文，只用数据库完整名称生成条件性资料核对提示，前端再逐字显示；不是等整条回答完成后伪装流式。正文过滤为保守规则，不能保证模型所有医学判断正确。价格和库存仍由数据库商品卡提供，最终以下单校验为准。
- 上下文只在当前客户端内存中保留最多 20 条，发送最近 3 轮，每条历史回答最多 1000 字；不写入本地持久存储。服务端仅接受最多 6 条上下文、校验角色与长度，但它仍是不可信用户数据；不能确定权限或作为商品事实。
- 请求哈希包含有限上下文；同编号改上下文/内容不复用。停止会取消前端和服务端任务；网络断开不保证供应商立即停止计费。处理中重试查看原编号，未完成记录最多 90 秒后失效；失败后以新编号重新请求。
- SSE 异步任务显式传递并清理可信身份/租户；流式路径在控制器及访问日志统一脱敏中受保护，验证失败也不记录咨询正文。
- 五栏共享底导航为 首页 / 分类 / AI 助手 / 购物车 / 我的，中央 AI 突出显示；统一按钮重置、高度与安全区。微信系统状态栏、胶囊不重复绘制。
- 人工药师按钮复用真实文字咨询；医生入口仅有明确的模拟说明，不伪造接通、回复或诊疗。

### 本地验证和预览

本轮使用永久毕设 Docker 项目 `firstsun-thesis-local`，后端 `http://127.0.0.1:28080`、管理后台 `http://127.0.0.1:28081`，与旧环境分离。新 H5 开发预览 `http://127.0.0.1:4189/pages/pharmacy/ai`。测试使用该独立库的演示会员和药品；模型请求连接真实 DeepSeek，浏览器不拦截合成业务响应。原有 01–42 迁移保持，启动检查跳过已应用迁移；模拟支付保持关闭。

- 后端 AI 及日志定向测试、打包：`.local/ai-upgrade-final-package.log`。
- 前端分块测试覆盖中文/emoji 分割、微信分块、停止与迟到数据、H5 流式、中断、401/403；原购物适配器测试沿用。日志 `.local/ai-upgrade-client-final-test.log`。
- H5 构建 `.local/ai-upgrade-h5-final-build.log`；微信开发编译 `.local/miniapp-local-dev.log`。微信导入 `D:\github-3\FirstSun-thesis\mall-uniapp\dist\dev\mp-weixin`（含 `app.json`），启动路由 `pages/pharmacy/index`，本机模拟器后端为 `http://127.0.0.1:28080`。本地开发工具须关闭合法域名校验；真机不能用本机回环地址。
- 真实模型/后端流式证据 `.local/ai-upgrade-http-report.json`；界面截图与尺寸检查 `.local/ai-upgrade-ui/`。这批证据不代表真实患者数据或真机验收。

微信开发者工具内分块、真实微信登录、真机、键盘与原生安全区仍未验证；H5 截图不能替代这些检查。正式使用前须复核药品资料、医学输出策略、模型预算/并发/超时和用户数据告知；远程部署还需实际配置合法 HTTPS 域名、微信域名白名单及对应应用配置。没有执行远程部署或推送。


02 定向复审已确认混合推荐及迟到 401 问题修正；后端 AI 22 项与日志路径 1 项共 23 项定向测试通过，前端流式测试 7 项通过，另有既有购物适配器测试通过。401 处理同时核对请求账号及令牌，旧账号或旧令牌错误不清除当前登录态。对模型漏检文本仍保留风险，不把关键词检查称为完整医学审查。


最终真实联调通过（永久本地环境 + 真实 DeepSeek + 演示库）：感冒追问、腹痛追问、实名商品查询、危险症状提示、拒绝写操作；首段分别在 6.469 / 1.953 / 3.360 秒到达，早于完整回答 7.360 / 2.891 / 3.985 秒。重复响应一致，改内容拒绝，游客/跨租户/无效门店拒绝，验证失败正文日志脱敏通过。实际运行 JAR 哈希与本次测试打包的 JAR 一致。

H5 真实界面通过：五页 × 320/375/430px 共 15 组，无横向溢出，五项等宽且高度统一；流式未结束前可见部分回答，商品卡跳转真实详情、页面切换保留当前账号内存会话、停止后重新提问、登出清空会话均通过，页面脚本错误为零。截图已实际查看；测试未拦截业务接口合成响应。全套证据 `.local/ai-upgrade-ui/report.json`、截图目录及 `tail-report.json`。验证脚本与会员令牌留在 Git 忽略目录，未加入产品源码。

复现定向分块与登录保护测试：在 `mall-uniapp` 执行 `node --test pages/pharmacy/tests/sse.test.cjs`（7 项），沿用 `npm run test:pharmacy`；微信开发产物包含 `app.json`，不以旧 release 目录代替。


### 上一轮 AI / 导航升级修改文件（26 个）

既有启动入口、环境地址与项目配置改动已保留，以下仅列本轮 AI / 导航升级。

```text
backend/yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/ApiAccessLogSanitizer.java
backend/yudao-framework/yudao-spring-boot-starter-web/src/test/java/cn/iocoder/yudao/framework/apilog/core/PrivateBusinessLogPathTest.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/controller/app/pharmacy/CustomerAiController.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/controller/app/pharmacy/vo/CustomerAiConsultReqVO.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/controller/app/pharmacy/vo/CustomerAiConsultRespVO.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiAnswerService.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiCatalogueService.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiConsultService.java
backend/yudao-module-ai/src/main/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiIntentService.java
backend/yudao-module-ai/src/test/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiAnswerServiceTest.java
backend/yudao-module-ai/src/test/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiCatalogueServiceTest.java
backend/yudao-module-ai/src/test/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiConsultServiceTest.java
backend/yudao-module-ai/src/test/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiIntentServiceTest.java
docs/customer-ai-development.md
mall-uniapp/pages/pharmacy/ai-session.js
mall-uniapp/pages/pharmacy/ai.vue
mall-uniapp/pages/pharmacy/cart.vue
mall-uniapp/pages/pharmacy/category.vue
mall-uniapp/pages/pharmacy/index.vue
mall-uniapp/pages/pharmacy/tests/sse.test.cjs
mall-uniapp/pages/pharmacy/user.vue
mall-uniapp/sheep/api/pharmacy/server.js
mall-uniapp/sheep/api/pharmacy/sse.js
mall-uniapp/sheep/components/s-pharmacy-page/s-pharmacy-page.vue
mall-uniapp/sheep/components/s-pharmacy-tabbar/s-pharmacy-tabbar.vue
mall-uniapp/sheep/scss/pharmacy.scss
```

## 2026-10-02：话题历史、会员资料与加载显示

### 实现与复用

- AI 页增加新话题、历史记录、按话题恢复、删除单个话题和清空历史。话题列表每页 30 条，问答每页 50 轮，可继续加载；后端上下文读取当前会员所属话题最近 3 轮成功问答，忽略客户端伪造的上下文。新话题不会继承旧话题。模型续问意图输出格式异常时降为无商品工具的普通对话，避免把缺少药品名的追问当作查询失败。
- 沿用现有租户、会员认证、门店校验、AI 请求防重复和 Redis 限流。新增 `ph_customer_ai_topic`，并给原咨询记录增量增加 `topic_id`、`question`；没有新增服务。所有历史接口关闭请求/响应正文日志，失败日志也按 AI 路径脱敏。
- 历史包含健康信息，页面说明会发送给配置的模型服务商并保存在账户。删除物理移除话题标题，清除问题和答案正文；保留请求摘要、请求标识和归属等用于防重复的记录，不是物理删除全部元数据。生成期间删除也会阻止迟到回复重新保存。清空同时擦除此前没有话题的旧答案；以前没有保存的原问题不能恢复，不伪造历史内容。已发送给服务商的内容及已有备份不由此接口删除。
- 五项导航仅当前页面标签显示选中状态，AI 中央图标保留醒目入口。AI 正文字号、换行和工具栏调整，去掉成对 Markdown 加粗符号；保留 SSE 和逐字显示。首次等待显示动态思考点，共享加载状态显示旋转指示，页面跳转显示原生加载提示。
- 会员页面复用实际启用的 pharmacy 会员资料接口和框架文件上传接口，增加主动选择头像、填写昵称及保存，上传失败不显示成功；头像加载失败回退默认图标。微信 `wx.login` 本身不提供真实昵称头像，小程序使用 `chooseAvatar` 按钮与 `nickname` 输入，不能自动同步微信资料。H5 使用本机图片选择，真实微信交互仍未验证。资料读写新增会员类型校验，员工令牌不能读写顾客资料。
- 异步历史读取、确认删除和资料请求固定发起身份，切换账户后的旧响应不能改新账户。加载话题期间禁止发送问题到旧话题。

### 本次新增或进一步修改的文件

```text
backend/yudao-module-ai/.../controller/app/pharmacy/CustomerAiController.java
backend/yudao-module-ai/.../controller/app/pharmacy/vo/CustomerAiConsultReqVO.java
backend/yudao-module-ai/.../dal/dataobject/pharmacy/CustomerAiConsultDO.java
backend/yudao-module-ai/.../dal/dataobject/pharmacy/CustomerAiTopicDO.java
backend/yudao-module-ai/.../dal/mysql/pharmacy/CustomerAiTopicMapper.java
backend/yudao-module-ai/.../service/pharmacy/CustomerAiHistoryService.java
backend/yudao-module-ai/.../service/pharmacy/CustomerAiConsultService.java
backend/yudao-module-ai/.../service/pharmacy/CustomerAiIntentService.java
backend/yudao-module-ai/.../service/pharmacy/CustomerAiAnswerService.java
backend/yudao-module-ai/.../test/.../CustomerAiHistoryServiceTest.java
backend/yudao-module-ai/.../test/.../CustomerAiConsultServiceTest.java
backend/yudao-module-ai/.../test/.../CustomerAiIntentServiceTest.java
backend/yudao-module-pharmacy/.../controller/app/member/AppMemberUserController.java
backend/yudao-module-pharmacy/.../test/.../AppMemberProfileAccessTest.java
backend/yudao-framework/yudao-spring-boot-starter-web/.../ApiAccessLogSanitizer.java
backend/yudao-framework/yudao-spring-boot-starter-web/.../PrivateBusinessLogPathTest.java
mall-uniapp/pages/pharmacy/ai.vue
mall-uniapp/pages/pharmacy/ai-session.js
mall-uniapp/pages/pharmacy/user.vue
mall-uniapp/pages/pharmacy/tests/ai-history.test.cjs
mall-uniapp/pages/pharmacy/tests/profile.test.cjs
mall-uniapp/sheep/api/pharmacy/server.js
mall-uniapp/sheep/components/s-pharmacy-state/s-pharmacy-state.vue
mall-uniapp/sheep/components/s-pharmacy-tabbar/s-pharmacy-tabbar.vue
sql/migrations/20261001_d_customer_ai_history.sql
deploy/local-migrations.json
docker-compose.yml
scripts/local-environment.ps1
scripts/tests/local-environment.test.ps1
README.local.md
docs/customer-ai-development.md
```

上表 Java 路径中的 `...` 表示各模块现有 `src/main/java/cn/iocoder/yudao/module/...` 或对应 `src/test/java` 包路径；完整当前工作区文件清单在忽略目录 `.local/ai-history-scope-report.json`。停用的 `yudao-module-member` 没有业务源码改动；原有启动入口、UI 和项目配置改动均保留，没有暂存或提交。

### 验证结果与证据

| 验证 | 结果与范围 |
| --- | --- |
| 后端定向测试及打包 | 30 项通过：AI 27、日志 1、实际 pharmacy 会员权限 2；`.local/ai-history-package.log`。运行容器 JAR 哈希与本次打包一致。 |
| 前端定向测试 | 12 项通过：SSE、头像资料、切换账号确认删除、历史加载防误发；`.local/ai-history-frontend-test.log`。已有 `npm run test:pharmacy` 通过，属于适配器/合成测试。 |
| SQL 与本地启动 | 43 先在专用测试 schema 连续执行两次通过；永久本地库仅应用增量 43，前 42 条哈希、状态和执行时间未变化。文件存储定向脚本测试通过，四服务健康。 |
| 真实模型 / 后端 | 21 项通过；感冒与“成人，刚开始，没有过敏”续问、后端历史、重复请求、游客/员工/跨租户/跨会员拒绝、生成期间删除、清空旧答案、上传和资料保存；`.local/ai-history-http-report.json`。用独立测试账户和演示药品，不是真实患者数据。 |
| H5 真实界面 | 新话题、恢复历史、独立浏览器恢复、删除、昵称保存刷新、公开头像实际加载通过；AI/首页/分类/会员 12 组 320/375/430px 检查无横向溢出，AI 末项和输入栏没有被导航遮挡，脚本错误为零；`.local/ai-history-ui/report.json`。长历史名称与清空记录另见 `tail-report.json`。已实际查看截图。 |
| 加载动画 | 思考点在真实模型等待中可见；共享分类加载截图仅在独立浏览器上下文延迟真实接口响应，不替代真实联调结果。截图 `ai-thinking.png`、`category-loading-delay-only.png`。 |
| 构建 | H5 构建通过 `.local/ai-history-h5-build.log`；本轮微信开发编译完成 `.local/miniapp-local-dev.log`，输出含 `app.json`。既有循环 chunk / img 选择器警告保留。 |

预览 `http://127.0.0.1:4189/pages/pharmacy/ai`，先登录本套本地账户。微信导入 `D:\github-3\FirstSun-thesis\mall-uniapp\dist\dev\mp-weixin`，启动 `pages/pharmacy/index`，开发后端 `http://127.0.0.1:28080`。服务启动方式见 `README.local.md`。SQL 43 依赖 40 的咨询记录表，在 42 之后执行；独立测试 seed 不在通用初始化链。

**未验证**：微信开发者工具中的真实登录、原生头像选择/昵称输入、分块显示、键盘及安全区、真机和远程发布。H5 不能替代微信验收。头像走公开文件接口，仅适合公开个人资料；处方继续使用私有接口。本地文件 URL 不能供真机访问，部署必须配置真实可达地址及微信 HTTPS 域名白名单，不编造域名。模拟支付仍默认关闭，医生咨询仍明确模拟，未接入真实支付或医生。AI 回答仅为用药参考，词句过滤不能保证完整医学安全，正式使用仍需药师复核、隐私和服务商数据策略审查。

02 最终定向复审未发现阻塞项：独立核对本机 DB 文件配置占用保护、历史正文擦除与迟到完成保护，以及实际启用的会员鉴权；前端 12 项此前由 02 独立执行通过，本轮后端/真实联调证据由实施会话执行、02 阅读核对。审查不代表微信原生验收。本轮保持待审查，分支 `codex/miniapp-ui`，HEAD `3c25ba3fe0f02f05051439cd9c2cf55e7a00cb36` 未变；暂存区为空，未提交或推送。
