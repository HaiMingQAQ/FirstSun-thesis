# 微信小程序构建与测试支付边界

## 本机模拟器开发

在 `mall-uniapp` 目录运行 `npm ci --ignore-scripts`，再运行 `npm run dev:mp-weixin`。开发模式继续读取 `.env.development` 中的本机地址；本机模拟器可按现有方式联调。此命令生成的开发产物不得作为远程发布包。

## 微信体验版和正式版构建

`npm run build:mp-weixin` 在编译前检查以下四个部署参数；它们在 `.env.production` 中默认留空，也可用同名环境变量提供。缺失、非 HTTPS、含用户名或密码、`localhost`（含末尾点及子域）或任何 IP 字面量（包括 `127.0.0.0/8`、IPv6 回环）都会使构建立即失败并列出参数名。此处只校验 URL 结构，不证明域名真实可达或已加入微信合法域名。

| 参数 | 用途 |
| --- | --- |
| `SHOPRO_BASE_URL` | 正式版后端 API 基址 |
| `SHOPRO_TRIAL_BASE_URL` | 体验版后端 API 基址 |
| `SHOPRO_STATIC_URL` | 静态资源基址 |
| `SHOPRO_H5_URL` | H5 页面基址 |

先由部署负责人提供实际可达的 HTTPS 地址，配置这四项，再运行 `npm run test:pharmacy` 和 `npm run build:mp-weixin`。请核对构建产物中的地址及微信平台的 request、uploadFile、downloadFile 合法域名配置，并用微信开发者工具和真机验证登录、商品、下单与资源加载。**真机连接尚未验证。**当前仓库不提供可直接发布的地址，也不包含真实微信支付实现。

## 测试模拟支付

服务端 `firstsun.miniapp.mock-payment-enabled` 是**实例级**开关，不按租户划分；开启后该实例所有租户共用同一开关状态。它默认关闭，团队开发 Compose 和现有云端演示 Compose 均显式设为 `false`。`local`、`dev`、`test` profile 只是必要条件，不证明实例隔离；现有云端演示实例即使用 `local` profile，也不得开启该开关。

只有单独创建、与演示和真实业务环境分离的测试实例，经过人工确认测试数据、数据库、Redis、访问入口和凭据均隔离后，才可人工设为 `true`。页面从服务端读取当前能力，失败时隐藏测试支付入口；点击时会再次查询。能力查询仅控制入口显示，实际支付请求仍由服务端独立校验开关、profile、会员归属和订单状态；开关开放不代表每笔支付都能成功。测试模拟支付不会真实扣款，不得用于真实交易或对外付款承诺。
