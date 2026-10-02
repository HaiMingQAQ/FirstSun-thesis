# 继承的疑似凭据来源清单（2026-10-02）

本清单只列来源和处置状态，不记录原值、部分原值或可用于调用的凭据。10 项表示 10 个测试文件 / 历史 blob，不意味着 10 个独立、有效或属于本项目的密钥。

## 已核实的来源与暴露范围

- 全部命中对象由 `cd097b0ee486d581e78e5ce7520c5f86e9810296`（2026-09-08，“导入 yudao 项目”）进入本仓库。提交作者标为 HaiMingQAQ；这只证明本仓库导入记录，不能证明凭据持有人身份、原始上游出处或真实有效性。
- 全部 10 个对象存在于已公开的 `d6d72ae10f40b509b78184dbbedc531c44b1b53b` 文件快照，且仍属于后续分支的可达历史。
- `59bc24da2b09f1a6127362cb9c87f5989df216c4` 已将当前测试中的硬编码改为 `FIRSTSUN_TEST_*_API_KEY` 环境变量，保留禁用测试状态。后续没有移除历史对象。
- 未调用服务商验证继承值，未取得撤销 / 轮换证据，未重写历史。模式匹配不是对有效性的判断，也不能保证没有其他类型凭据。

## 文件与服务商

以下路径均位于 `backend/yudao-module-ai/src/test/java/cn/iocoder/yudao/module/ai/framework/ai/core/`，是禁用的框架集成测试，不是当前顾客 AI 的运行配置。

| 文件（相对上述目录） | 代码指向的服务 | 当前硬编码 | 持有人 / 撤销状态 |
| --- | --- | --- | --- |
| `model/chat/AnthropicChatModelTest.java` | Anthropic | 已改环境变量 | 未确认 |
| `model/chat/GeminiChatModelTests.java` | Google Gemini | 已改环境变量 | 未确认 |
| `model/chat/OpenAIChatModelTests.java` | OpenAI | 已改环境变量 | 未确认 |
| `model/chat/SiliconFlowChatModelTests.java` | SiliconFlow 聊天 | 已改环境变量 | 未确认 |
| `model/chat/TongYiChatModelTests.java` | 通义 / DashScope | 已改环境变量 | 未确认 |
| `model/image/MidjourneyApiTests.java` | Midjourney 兼容代理接口 | 已改环境变量 | 未确认，不能据名称认定为官方账户 |
| `model/image/SiliconFlowImageModelTests.java` | SiliconFlow 图片 | 已改环境变量 | 未确认 |
| `model/image/StabilityAiImageModelTests.java` | Stability AI | 已改环境变量 | 未确认 |
| `model/image/TongYiImagesModelTest.java` | 通义 / DashScope 图片 | 已改环境变量 | 未确认 |
| `websearch/AiBoChaWebSearchClientTest.java` | Bocha 搜索 | 已改环境变量 | 未确认 |

对象级对照证据在 Git 忽略的 `.tmp/published-history-check.json` 与 `.tmp/upload-secret-audit.json`，包含文件路径、对象 ID 和类型，不含原值。当前实际部署的授权 AI / 微信密钥另在忽略的本机配置中，不能将其与这些继承测试值混为一谈。

## 需要持有人完成的确认

1. 根据测试文件及导入来源确认原始提供者。不要通过调用旧值、发送邮件或公开贴值猜测所有权。
2. 若是真实凭据，由有权限的持有人在对应服务账户撤销旧凭据并生成新凭据；新值只写本机忽略配置。确认是否有异常调用及费用。
3. 若是已失效值或样例，记录可核实依据；不能仅因测试被禁用、旧文档或当前源码已删除就认定安全。
4. 仅记录服务、持有人确认、撤销日期和“已撤销 / 已轮换 / 样例依据”结论，不把新旧凭据或账户截图中的秘密加入 Git。

这些步骤涉及账户权限，实施会话不能代持有人完成。目前仍全部标记未确认。

## 后续推送决策

先撤销 / 轮换，再由用户选择保留历史普通推送或另行授权历史处理。保留历史时旧值仍可读取；清理历史不能撤销密钥，也不能删除已经下载或缓存的公开副本。对已公开 main 的改写、强推或新发布副本均需单独授权，本轮不执行。业务本地提交可以继续，但不据此自动推送。
