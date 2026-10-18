# FirstSun 电脑端顾客网站 · 接入第一批

独立 Vue 页面，保留 `FirstSun-web-prototype` 的购药主页、AI 工作区和商品详情视觉。基于主仓库 `4fa6d528403c6a30dc4d11f532ac6049a0f9b5cd`，本批不需要后端源码或迁移改动。

## 本批可用

- 商品分类、名称搜索、分页、门店可售库存、真实商品 ID 详情。
- 顾客 AI 话题创建、历史列表/分页、历史重载、真实 POST SSE、错误重试、停止接收。
- 会员资料和退出；仅明确本机验收模式提供真实后端测试验证码登录。

购物车、订单、支付、处方、人工咨询只保留明确未接入入口；不会运行静态原型中的合成成功逻辑。Rx 包装、说明书和材料不加载。缺图明确显示资料未提供，后端的演示占位图也不冒充真实包装。

## 复用

直接 import `../mall-uniapp/sheep/api/pharmacy/server.js` 及其 API 包装器、`common.js`、`sse.js`，不复制 AI 检索、模型策略、会员或订单规则。Vite 将其 `@/sheep/request` 和 `@/sheep/config` 指向 `src/request.js` / `src/config.js`；`src/session.js` 提供本批用到的 uni 存储接口，保留现有 token、refresh-token、firstsun-session 键和后端令牌契约。登录提交与启动资料恢复同时检查操作归属及组件生命周期，当前身份快照和代次用于拦截换号后的迟到响应；私有对话仅在 Vue 内存及服务端保存。

AI 页的流状态、请求标识、有限上下文、FAILED 新标识重试、断网原标识重试、历史恢复与商品卡映射适配自现有 `pages/pharmacy/ai.vue`。SSE 直接使用其 H5 fetch 路径，遵守 done/error 事件，不在浏览器调用模型或保存模型密钥。停止仅表示取消前端接收，不保证上游处理或计费停止；可稍后重载服务端历史。真实停止测试只观察了2500ms内可见文本未变化，没有证明真实迟到delta到达，也不证明任意晚到均受阻。

`src/prototype.css` 复制自独立静态原型以保留视觉，`src/live.css` 只补真实状态与布局修正。Lucide 0.468.0 及 ISC 许可证来自原型本地素材。

主要会员调用由共享server适配器设置 `skipRefresh:true`，本批登录过期后重新登录；没有宣称会员自动续期。历史刷新/失败重试读取最新一页，只有明确的“更早话题”操作会追加旧页。

## 开发和构建

Node 依赖由 package-lock.json 锁定：

```powershell
npm ci
npm test
npm run build
npm run dev
```

默认构建没有测试登录表单或固定验证码，正式网页登录方式仍待确认。发布时由同源 `/app-api` 接入既有后端；本批没有配置远程部署。

## 独立本机验收

先确认后端是已授权的隔离回环环境。创建被忽略的 `.env.local-acceptance.local`，示例键见 `.env.local-acceptance.example`。随后：

```powershell
npm run dev:acceptance
```

地址 `http://127.0.0.1:4190/#catalog`，AI `http://127.0.0.1:4190/#ai`。测试入口要求 mode=local-acceptance、显式开关、HTTP 回环后端和浏览器回环地址同时成立，验证码由人工输入并走后端 send-sms-code / login-or-register 校验；没有默认验证码或预置令牌。该模式不代表正式顾客网页登录或微信登录通过。

配置只有后端地址、租户 ID 和开启标识，不含账号、验证码、令牌或模型密钥。切勿把验收模式构建当作发布版本。

## 本轮证据

`D:/github-3/FirstSun-design-notes/customer-web-batch1-20261019/REPORT.md` 记录运行/构建身份、隔离环境、真实 HTTP/模型与数据库证据、双会员隔离、截图及未验证项。验收结束停止本轮自建服务，保留数据；本目录未提交、推送或部署。
