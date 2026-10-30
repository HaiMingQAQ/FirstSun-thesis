# 前端任务：电脑顾客端接入门店文字咨询

## 目标与分工

仓库：https://github.com/HaiMingQAQ/FirstSun-thesis

从最新 `main` 创建独立任务分支。先读 `HANDOFF.md`。执行方负责独立开发环境、电脑端页面、前端逻辑、测试和 PR；协调方负责后端源码、数据库结构、关键业务规则及最终合并。

交付目标：顾客在 `customer-web` 联系当前门店药师或客服，发起和查看会话、收发文字、查看历史；后台复用现有接待功能。不得自动生成客服回复。

## 先运行项目

- 在执行方自己的隔离开发环境拉取最新 main，按锁文件安装依赖。不要操作用户电脑上既有 native/Docker 服务或复制其私有数据、账号、令牌和配置。
- 可以构建和普通启动现有后端及自己的 MySQL/Redis，不修改后端源码、SQL、权限或业务规则。仅在自己的新空测试库使用现有初始化/迁移；发现缺失或不兼容则记录具体阻断，交给协调方处理，不靠手工改数据、权限或绕过认证刷绿。
- `README.local.md` 的入口面向 Windows Docker Desktop，会运行迁移及初始化步骤；先核对实际操作系统、脚本前提和数据归属，不盲目运行。现成部署覆盖后端与管理端，`customer-web` 需单独启动或构建。
- 电脑端开发脚本默认端口仍为 4190；使用独立子进程参数选择 4193 或本环境可用的正常端口，不修改浏览器安全设置。API 基址和租户必须与自己的后端一致，不能抄用旧示例里的端口/租户作为已验配置。
- 保持本机验收模式的回环/显式开关约束。生产前端沿既有同源 `/app-api` 契约；不为了远程预览放宽开发认证门禁。
- 不对外做生产部署，不启用真实短信、邮件、支付或 AI/供应商调用。缺少这些配置时如实展示不可用状态。联调只使用自己隔离环境中的合成账号和会话。
- 后端启动或认证受阻时，可继续完成前端离线开发与测试；PR 中明确实机联调未完成及其阻断，不能伪造通过。

## 允许修改范围

- `customer-web/` 下必要的组件、路由、样式、前端逻辑、测试和说明；匹配现有页面风格，不重做整体布局。
- 只有复用接口确有需要时才改 `mall-uniapp/sheep/api/pharmacy/server.js` 等共享前端适配器，且保留 H5 既有契约并补跑相关测试。
- 必要的独立开发启动说明或前端辅助脚本，不能覆盖原运行配置；凭据、日志、构建产物和依赖目录不提交。
- 不改 `backend/`、`sql/`、数据库迁移清单、后端 Docker 配置、认证/租户/权限规则或现有支付开关。不启用通用 IM，不扩入处方、AI、交易或退款任务。

## 已有代码与接口

先读：

- `customer-web/src/App.vue`、`request.js`、`session.js`、`guards.js`：路由、登录、租户、令牌与身份生命周期。
- `mall-uniapp/sheep/api/pharmacy/server.js`：复用现成咨询 API，不复制服务端规则。
- `mall-uniapp/pages/pharmacy/consultation.vue`：已有 H5 的消息分页、轮询、同请求编号重试和生命周期逻辑，仅参考必要部分。
- `admin-ui/src/views/pharmacy/consultation/index.vue`：现有员工接待页面，默认不需要修改。
- 后端 `AppConsultationController`、`ConsultationRequests`、`ConsultationService`：只读核实真实契约；以当前源码为准。

HTTP 公共前缀为 `/app-api`，会员认证和租户沿既有适配器：

| 共享方法 | 接口 | 关键参数/结果 |
| --- | --- | --- |
| `openConsultation(kind)` | POST `/member/consultation/open` | 当前 `storeId`；kind 只能为 `PHARMACIST` 或 `SERVICE`；返回会话 ID |
| `consultationPage(pageNo)` | GET `/member/consultation/page` | pageNo；返回 list/total |
| `consultationMessages(id, params)` | GET `/member/consultation/messages` | id、after 或 before；返回 conversation/list/hasMore |
| `sendConsultation(data)` | POST `/member/consultation/send` | conversationId、clientRequestId、content |
| `readConsultation(conversationId, throughId)` | PUT `/member/consultation/read` | 使用实际收到的消息 ID 更新已读 |

发送内容 1–1000 字符，clientRequestId 为 8–64 位字母、数字、下划线或连字符。发送结果未确认时，用户重试必须沿用原请求编号和内容，不自动生成新编号重发。服务端是权限、归属和去重的最终依据。

## 页面完成标准

1. 打通商品页、商品详情、AI 页已有“药师/客服咨询”入口，使用独立人工咨询路由；未登录时进入现有登录流程，成功后继续原操作。
2. 可选择药师或客服，向当前门店发起会话；列出本人会话、未读及接待状态，支持分页。显示真实门店/会话归属，不把旧门店会话当成当前店。
3. 支持初次消息加载、增量刷新、查看更早消息、按消息 ID 去重和合理标记已读。文本按纯文本显示，不用未清理 HTML 渲染。
4. 发送时防连点；失败或结果未确认时保留原消息，提供同编号重试/放弃，不能伪造已送达、已接待或自动回复。员工回复来自现有后台/API。
5. 轮询不重叠；离开路由、组件卸载、退出、令牌失效、切换账号或门店时停止旧任务并清理私有状态。迟到响应不能写入另一账号或会话。
6. 空、加载、错误、无接待和发送结果未知状态明确；保留现有响应式风格、基本键盘操作及可访问名称。

## 验证与交付

- 保留现有测试及断言，增加有意义的消息分页去重、发送重试编号、迟到响应/切号、轮询退出和未登录返回流程回归。不能只查源码字符串就宣称交互通过。
- 在 `customer-web` 运行 `npm test`、`npm run build`；本机验收入口有改动时同时验证对应模式构建。输出留在自己的独立目录，不覆盖用户旧产物。不升级依赖或 lock 文件。
- 测试替身仅用于离线测试；产品页面不能内置合成回复、假会话或假成功状态。现有后台联调仅在自己的隔离测试环境做，明确账号/门店归属，不触及真实业务库。
- 提交必要源码、测试和简短执行报告。报告列出提交、实际命令/退出码、通过/失败/未测、启动方式与本环境地址、后端阻断和人工待验项；不要提交密钥、账号密码、token、原始敏感日志、数据库快照、node_modules 或 dist。
- 向本仓库 `main` 发起 PR，不自行合并、强推或部署生产。遇到后端缺陷时附最小复现和所需契约，由协调方修复；不自行扩大前端任务范围。
