# 毕设阶段同步准备（2026-10-02，等待授权）

## 实际 Git 状态

- 仓库：`D:\github-3\FirstSun-thesis`。
- 当前分支：`codex/miniapp-ui`；已完成业务阶段的末端提交：`d97756495b202cf422c95bbb1b4f9769127adb15`。本次文档提交的完整 SHA 以提交后的 `git log` 和主实施会话报告为准。
- origin：`https://github.com/HaiMingQAQ/FirstSun-thesis.git`，fetch/push 一致。
- upstream：`https://github.com/HaiMingQAQ/FirstSun-Pharmacy-Management-System.git`，保留原团队远端。
- 远端 `main` 和 HEAD：`d6d72ae10f40b509b78184dbbedc531c44b1b53b`，是本地 HEAD 的祖先。
- AI / UI 与本机启动两组已分别创建本地提交；本页随文档整理单独提交。没有推送、分支切换或历史改写。

## 已有但未上传的提交

按依赖顺序：

| 完整 SHA | 范围 |
| --- | --- |
| `f37ba980d2b186d71552c0c4a0c977cc121380de` | 顾客基础购物 UI 移植 |
| `bd1f5f063e63f160da1dfeaf8c3ec08dfe0b01c6` | 文档导航及历史协作资料整理 |
| `59bc24da2b09f1a6127362cb9c87f5989df216c4` | 继承的禁用 AI 测试令牌改用环境变量 |
| `3c25ba3fe0f02f05051439cd9c2cf55e7a00cb36` | 顾客 AI、私有处方购药、通知和人工文字咨询 |
| `64681b551af7c808e2b4ce13e88dec75113145fc` | AI 问答与 SSE、话题历史、会员资料及导航 UI，38 个文件 |
| `d97756495b202cf422c95bbb1b4f9769127adb15` | Windows 独立本机启动、迁移与配置，11 个文件 |

## 阶段提交范围与文档整理

拟本地分支 `codex/miniapp-ui` → `origin/codex/miniapp-ui`，使用普通推送，不改名为 main，不强推。当前 origin 只有 main，目标阶段分支尚不存在。

1. **AI / 历史 / 会员资料 / 导航阶段，38 个文件**：AI 真实问答及只读商品查询、SSE、中央入口及共享布局；后端会员归属话题历史与删除、新话题、主动头像昵称保存、加载动画、定向测试和交付文档。包含 SQL 43 及根 Compose 的顺序追加，不修改既有迁移。不将医生模拟写成真实接诊。
2. **Windows 专用本机环境阶段，11 个文件**：三个 bat、启动 PowerShell 和测试、独立 Compose、空凭据示例、迁移清单、`.local` 忽略规则、开发 API 地址及 `README.local.md`。迁移清单引用 43，须在第一组之后形成提交；密钥配置、测试账户令牌和构建产物不纳入。
3. 本次文档整理单独提交：采用已核实的 04 README 草稿、三张本地 H5 演示截图、更新两份同步报告及新增历史凭据来源清单。它不修改业务实现，也不冒充新增业务验收。

完整逐文件清单保存在本地忽略文件 `.local/github-stage-scope-20261002.json`，两次提交结果在 `.local/reviewed-stage-commits-20261002.json`。前两组 49 个文件已提交，两次 `git diff --cached --check` 均通过；其他工作区内容未被重置或清理。拟推送末端为随后形成的文档提交，完整 SHA 必须提交后再确认，不自动推送。

### 保留、不纳入

- 根 README：04 草稿已完成，本次已核对链接、启动命令、来源及未验证边界后采用，旧工作区版本备份在忽略的 `.local/readme-before-thesis-20261002.md`；原来新增的本机启动说明已融入新版。原课程 README 保留于 Git 历史，不丢失。
- `mall-uniapp/project.config.json`、`mall-uniapp/project.private.config.json`：个人微信开发工具配置，保留本机，不作为已审查产品源码纳入。
- `.local`、`.tmp`、本地 `.env.thesis.local`、日志、令牌、构建目录和原工作区均保留，不复制到提交。
- 停用的 module-member 两个文件在 status 中出现，但实际 Git diff 没有源码变化，不纳入。后续任何新出现文件必须重新分类，不自动全部 add。

## 已公开历史凭据：仍阻塞推送

本次检查覆盖当前跟踪 / 未忽略文件及 HEAD 全部可达历史 blob，不限于当前源码。模式扫描加已授权本地凭据原值定向比较，当前文件零命中；历史仍有 **10 个疑似令牌 blob**。逐个对象比较确认：**10 / 10 在 origin/main 的 d6d72ae 可达历史中，且 10 / 10 直接存在于已公开快照**。

涉及禁用的 Anthropic、Gemini、OpenAI、SiliconFlow（聊天和图片）、DashScope（聊天和图片）、Midjourney、Stability、Bocha 测试。后续 `59bc24da` 已改为环境变量，当前对应文件不含原硬编码；其祖先中的原值依旧可读取。没有核实所有权、有效性或撤销状态，不使用这些值请求供应商。

**需要确认凭据持有人撤销／轮换和公开暴露处置。** 若选择历史处理，必须另行明确授权；现有公开 main 不能通过本轮普通追加推送消除泄露，也不能擅自强推或改写。详情见 `first-upload-preparation.md`。扫描不能证明所有凭据类型都不存在，不输出原值。对象级证据保存在忽略的 `.tmp/upload-secret-audit.json` 与 `.tmp/published-history-check.json`。

许可证 `backend/LICENSE`、`admin-ui/LICENSE`、`mall-uniapp/LICENSE` 和既有来源／贡献说明保留，没有将 yudao、团队基础宣称为个人独立开发。

## 阶段验证与 04 交接

已有测试记录：后端 30 项及打包通过、前端 12 项通过、既有购物适配器测试通过、H5 构建和微信开发编译通过、真实后端 / 模型 21 项通过，三种宽度的界面及历史跨浏览器恢复通过。02 最终定向复审无阻塞项，详见 `customer-ai-development.md`。本次仅做 Git / 历史检查，没有重复全量构建。

本次检查时四个专用本机服务健康。已验证的永久入口是 `start-local.bat` / `rebuild-local.bat` / `stop-local.bat`，地址后端 28080、后台 28081，停止保留数据；rebuild 不编译小程序。小程序在 `mall-uniapp` 设置 `SHOPRO_DEV_BASE_URL=http://127.0.0.1:28080` 与 `SHOPRO_TENANT_ID=163`，运行 `npm run dev:mp-weixin`，导入 `dist/dev/mp-weixin`，启动 `pages/pharmacy/index`；H5 对应 `npm run dev:h5`。当前 4189 只是本轮预览进程端口，不是永久启动入口保证的地址。

已将这些步骤和功能边界发给 **04 毕设开题初稿**；草稿编写期间未修改根 README，完成后依据用户授权核实采用。真实微信登录、原生头像、微信实际 SSE、真机和远程发布尚未验证；模拟支付默认关闭，真实支付不存在，医生咨询模拟，管理者 AI 库存预警与采购闭环不能扩大为本轮已验证。具体以 `README.local.md` 和三份业务交付记录为准。

**当前结论：阶段源码具备整理提交的证据，但 GitHub 推送仍受已公开历史凭据处置阻塞；等待用户审阅和明确授权，不执行推送。**
