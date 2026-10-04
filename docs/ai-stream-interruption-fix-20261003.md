# 顾客 AI 流式回复中断修复（2026-10-03）

## 原因与处理

- 本机后端此前多次记录 `phase=answer, reason=EMPTY_ANSWER`。真实服务商对照测试确认：当前 DeepSeek 模型默认开启思考模式，隐藏推理也消耗 `max_tokens`。256 token 意图请求出现 `finish_reason=length` 且正文为 0；1200 token 追问出现回答被截断。官方说明：[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)、[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/)。
- 新增顾客专用 `CustomerAiModelClient`，复用已有模型、密钥校验及环境变量解析，使用已有 Spring AI OpenAI 兼容客户端发送顶层 `thinking.type=disabled`。通用 AI 客户端与其他服务商保留原有行为，不升级依赖。
- 顾客客户端禁止自动工具执行，并关闭 SDK 和底层 HTTP 客户端的隐式重试；界面仍可显式重试。意图总等待 20 秒，回复总等待 45 秒，SSE 服务端等待 75 秒，客户端总等待 90 秒。
- 明确检查 DeepSeek 结束标记：空正文、`length` 等异常结束以及缺少 `stop` 的提前断流均不能记作成功。未完成句子不会越过现有句子校验后输出。
- 后端失败记录保留已校验、已输出的文字和真实查询商品卡，并保持 `FAILED`。不保存原始服务商异常、内部推理或凭据。重复请求和历史恢复读取同一失败结果。
- 前端失败时保留已经接收的正文，包括尚在打字队列中的字符；停止动画和光标，显示独立未完成提示，恢复发送及重试。收到 SSE `done` 后立即完成，不等待 HTTP 连接关闭；H5 增加总等待上限。

## 修改范围

后端（路径位于 `backend/yudao-module-ai/src`）：

- `main/java/cn/iocoder/yudao/module/ai/service/pharmacy/CustomerAiModelClient.java`（新增）
- 同目录 `CustomerAiIntentService.java`、`CustomerAiAnswerService.java`、`CustomerAiConsultService.java`
- 对应 `test/java/.../pharmacy/CustomerAiModelClientTest.java`（新增），以及 Intent、Answer、Consult 三份测试

小程序：

- `mall-uniapp/pages/pharmacy/ai.vue`
- `mall-uniapp/sheep/api/pharmacy/sse.js`
- `mall-uniapp/pages/pharmacy/tests/sse.test.cjs`
- `mall-uniapp/pages/pharmacy/tests/ai-stream-state.test.cjs`（新增）

既有 UI、分类分页、启动脚本和其他会话的药品调查文件保留。本次不修改药品数据、SQL、根 README、支付能力或云端配置；未暂存、提交或推送。

## 验证

### 定向测试及编译

- 后端离线 Maven 构建成功；Intent、Answer、ModelClient、Catalogue、Consult、History 共 **42 项**通过。包括思考开关的实际 HTTP 请求体、推理不进入正文、同步/流式 503 不重复调用、异常结束与提前 EOF、失败文字持久化及幂等恢复、已有身份/租户校验。
- 前端状态、SSE、历史定向测试 **17 项**通过；原 `npm run test:pharmacy` 通过（适配器/合成测试，不算真实联调）。
- `npm run build:h5` 成功。
- 重新启动 `npm run dev:mp-weixin`，本轮开发编译成功，生成 `app.json` 及新的 AI/SSE 文件；保留现有 Sass、循环分块等编译警告。
- `git diff --check` 通过；本地凭据、日志、构建包、独立测试脚本及截图均处于 Git 忽略目录。

### 独立合成界面验收

仅在独立浏览器会话拦截 HTTP，无真实后端、模型或数据库调用。FAILED 与无 done 的截断连接均保留正文，结束加载并恢复操作。实际查看 320、375、430px 截图，确认无横向溢出；长药名、规格、大金额正常，滚到底最后卡片不被输入栏遮挡。

证据：`.local/ai-stream-ui-20261003/report.json`、`failed-*.png`、`failed-prefix-*.png`、`truncated-430.png`。

### 真实本机后端与模型

- 从最新构建 JAR 更新毕设专用后端镜像，并通过现有启动入口启动；四项服务健康，43 项已记录迁移跳过，不重跑种子或删除数据。模拟支付仍关闭。
- 使用本机独立环境的专用验收会员、真实后端及已授权模型配置；无网络拦截、假回复或演示适配器。
- 三个新话题，每个含症状及成人/一天/风险信息追问：**6 次 SSE 均 SUCCESS，0 次手动重试**。每次有 18–21 条 delta、2 张真实查询商品卡；含前端打字显示的完成耗时约 3.4–4.9 秒。
- 历史重新选择后，两轮问答完整恢复；无光标、加载动画或停止按钮残留，无页面异常。
- 当前匹配商品实际库存为 0，明确标记缺货，仅作为说明参考；没有修改库存或伪造可购状态。

证据：`.local/ai-stream-real-20261003/report.json`、`completed.png`、`history-restored.png`。所有问题是验收用合成输入，真实模型和后端联调与上面的拦截测试分别记录。

构建/启动日志：`.local/ai-stream-backend-final-20261003.log`、`ai-stream-h5-build-20261003.log`、`ai-stream-mp-dev-20261003.log`、`ai-stream-start-20261003.log`。

## 使用与边界

- 本机后端已更新并启动：`http://127.0.0.1:28080`；管理后台：`http://127.0.0.1:28081`。
- H5 开发预览：`http://127.0.0.1:4189/pages/pharmacy/ai`。
- 微信开发者工具导入：`D:\github-3\FirstSun-thesis\mall-uniapp\dist\dev\mp-weixin`；路由 `pages/pharmacy/ai`。当前开发产物连接 `http://127.0.0.1:28080/app-api`，请重新编译当前导入项目。
- 本轮微信开发者工具内实际运行、真实微信登录及真机网络流式表现**未验证**；H5 验收不能替代微信端验收。外部模型/网络仍可能失败，已验证的处理是保留安全的已输出内容并明确标记未完成。
