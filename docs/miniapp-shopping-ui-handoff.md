# FirstSun 顾客端基础购物 UI 交付记录（待审查）

## 工作区与范围

- 日期：2026-10-01。仓库 `D:\github-3\FirstSun-thesis`。
- 分支保持 `codex/miniapp-ui`；HEAD 保持 `d6d72ae10f40b509b78184dbbedc531c44b1b53b`。
- 开始时只有未跟踪的 `mall-uniapp/project.config.json`、`project.private.config.json`；两者保留，未编辑。
- 本轮 18 个已跟踪文件修改、2 个新文件（本记录及 UI 测试）。全部未暂存。没有提交、推送、部署、后端/数据库/云端/凭据修改或其他目录清理。
- 设计依据：`D:\github-3\FirstSun-ui-prototype\HANDOFF.md`、页面源码、品牌规范及确认截图。未复制原型业务数据或模拟流程。

## 修改文件与主要改动

以下路径均相对本仓库。

| 文件 | 改动 |
| --- | --- |
| `mall-uniapp/pages/pharmacy/index.vue` | Georgia FirstSun 字标、门店和搜索、分类捷径、明确未开放的助手展示、常备商品 |
| `mall-uniapp/pages/pharmacy/category.vue` | 全宽商品行、换行分类筛选、真实列表上的有货/非处方筛选，保留搜索历史 |
| `mall-uniapp/pages/pharmacy/detail.vue` | 包装图、价格、资料和安全区操作栏；处方受限展示，缺货/失效禁用 |
| `mall-uniapp/pages/pharmacy/cart.vue` | 商品、选择/数量/删除、金额与底栏；处方商品不可选购 |
| `mall-uniapp/pages/pharmacy/checkout.vue` | 自提/配送、紧凑清单、积分、备注、金额和提交状态；处方提交明确关闭 |
| `mall-uniapp/pages/pharmacy/user.vue` | 圆形头像、会员/积分、订单捷径；消息和 AI 标为未开放，处方入口如实说明 |
| `mall-uniapp/pages/pharmacy/order.vue` | 同一分隔和金额规范，保留状态筛选和订单操作 |
| `mall-uniapp/pages/pharmacy/order-detail.vue` | 同一订单清单、资料、状态和固定操作栏 |
| `mall-uniapp/pages/pharmacy/login.vue` | 文字品牌与统一视觉；保留真实微信登录和平台限制，纠正未接通上传的说明 |
| `mall-uniapp/pages/pharmacy/prescription-upload.vue` | 未接通的在线提交显示不可办理；保留订单关联记录查看，不产生上传/保存假成功 |
| `mall-uniapp/sheep/api/pharmacy/server.js` | 立即购买先复核再仅勾选当前商品；普通结算阻止处方；全选排除处方；历史订单价格取服务端订单行快照 |
| `mall-uniapp/sheep/components/s-pharmacy-{page,product,state,stepper,tabbar}/s-pharmacy-*.vue`（5文件） | 商品图片兜底与受限处方图、状态、44px 数量控件、四栏导航与选中标记，演示说明移至页尾 |
| `mall-uniapp/sheep/scss/pharmacy.scss` | 确认稿色彩、字体、间距、白色整宽区块、按钮、搜索及底栏安全区；地址页继承此样式，未改变地址业务 |
| `mall-uniapp/pages/pharmacy/tests/client.test.cjs` | 立即购买选择范围、处方阻止与无副作用、全选及订单价格快照回归 |
| `mall-uniapp/pages/pharmacy/tests/ui.test.cjs`（新增） | 独立浏览器拦截测试，复用已有测试夹具；禁止实际访问远程域名，无生产适配器切换 |
| `docs/miniapp-shopping-ui-handoff.md`（新增） | 本交付记录 |

仍使用 `PHARMACY_DEMO=false` 的真实适配器。登录、会员、地址、购物车行 ID、积分试算、订单状态及实际支付请求的服务端校验保持现有边界。

## 页面与接口

| 页面 | 现有接口能力 |
| --- | --- |
| 首页、分类、详情 | `pharmacy/store/list`、`pharmacy/drug/{page,get,category-list}`、`pharmacy/inventory/available` |
| 购物车 | `member/wx-cart/{list,add,update-qty,update-selected,delete}` |
| 结算 | 地址 list/选择、积分 summary/deduct-preview、`member/wx-order/create` |
| 会员、登录 | 现有会员 get、微信登录；H5 不伪造微信登录成功 |
| 订单 | `member/wx-order/{page,get,cancel,confirm-receive,mock-payment-available,simulate-pay}` |

## 原型对照与预览

- [原型与实现对照画廊](http://127.0.0.1:4190/comparison.html)：6 个主要原型页面并排对照，另附订单页、受限处方、长文本及异常状态。
- 画廊文件：`D:\github-3\FirstSun-thesis\.tmp\ui-shopping\comparison.html`。图片与结果位于同目录；原型截图由只读路径提供。
- 正常截图为 `{index,category,detail,cart,checkout,user,order,order-detail}-{320,375,430}.png`；`*-bottom-*` 为滚动到底部截图。数据明确为**独立合成 UI 测试数据**，不能作为真实购药联调证据。
- 原型截图含审查控件。未移植审查控件、系统状态栏、胶囊、桌面外框或伪造微信标题栏；本实现保留原生导航栏。
- 真实适配器预览：[本机首页](http://127.0.0.1:4188/pages/pharmacy/index)。后端不可用时会如实显示失败状态，未切换本地演示适配器。

启动：

```powershell
cd D:\github-3\FirstSun-thesis\mall-uniapp
npm run dev:h5 -- --host 127.0.0.1 --port 4188
```

该项目 H5 是 history 路由，使用 `/pages/pharmacy/index`，不使用 `#/pages/...`。后端开发地址仍为原有 `http://localhost:48080`。

画廊服务器（仅本机回环，不是部署）：

```powershell
cd D:\github-3\FirstSun-thesis
node .tmp/ui-shopping/serve.cjs
```

微信开发编译：

```powershell
cd D:\github-3\FirstSun-thesis\mall-uniapp
npm run dev:mp-weixin
```

- 准确导入目录：`D:\github-3\FirstSun-thesis\mall-uniapp\dist\dev\mp-weixin`。
- 启动路由：`pages/pharmacy/index`。
- 当前目录已核对 `app.json`、`app.js`、`app.wxss` 与全部 11 个药店页面的 JS/JSON/WXML。无独立 scoped 样式的确认订单、处方、地址页依赖全局 `app.wxss`，编译器不单独生成 WXSS，属正常输出。
- 使用本轮开发产物，未使用 `dist/build/mp-weixin` 旧发布产物作为验收依据。

## 验证结果

| 项目 | 结果与证据 |
| --- | --- |
| 小程序定向测试 | **通过**：`npm run test:pharmacy`；已有契约与新增立即购买、处方限制、快照金额回归。网络 stub 测试，不是实际 HTTP |
| 发布参数测试 | **通过**：`npm run test:build-config`，2 项；没有修改发布配置 |
| H5 构建 | **通过**：`npm run build:h5`；当前日志 `.tmp/ui-h5-build-final.log` |
| 微信开发模式编译 | **通过**：`npm run dev:mp-weixin`；`.tmp/ui-wechat-dev.log`，已核对本轮产物 |
| 浏览器布局 | **通过**：60 项，无横向溢出；33 项正常页面、12 项长药名/分类/大金额、3 项受限处方、12 项加载/空/网络失败/未登录状态，覆盖 320/375/430px |
| 截图人工查看 | 已实际查看三种宽度的主要页面，以及长文本、底部、处方和错误截图；修正了 320px 全选换行和错误状态图标。不以仅生成截图代替查看 |
| 跨页面 UI | **通过（合成 HTTP）**：搜索→详情→加购，分类与有货/非处方筛选；数量/勾选→结算→地址选择→提交→订单详情→确认取消 |
| 异常与门禁 | **通过（合成 HTTP）**：匿名加购跳登录，提交中禁用，缺货禁用，未知商品显示失败，坏图片兜底，支付关闭不渲染模拟支付按钮 |
| 浏览器脚本 | 0 个 pageerror；`.tmp/ui-shopping/results.json`，标明 `ISOLATED_SYNTHETIC_HTTP_UI_ONLY`。最后仅修复错误图标后另重查三宽度截图 |
| 真实后端联调 | **未验证**：Docker Linux 引擎管道不存在；`localhost:48080/app-api/pharmacy/store/list` 本机只读探测超时。未自动启动 Docker、未接云端、未向真实后端写数据 |
| 微信开发者工具实际运行、真实微信登录、真机 | **未验证**：本轮仅运行开发编译及 H5 浏览器检查；没有可用的原生工具操作验收。H5 截图不能替代微信验收 |
| Git 差异检查 | `git diff --check` 通过；暂存区为空，分支与 HEAD 未变 |

构建仍有既有 Sass 弃用、循环 chunk 和其他商城组件的 `img` 选择器警告。未为消除无关警告而升级依赖或重构其他模块。

复跑 UI 测试需要本机 Playwright 与浏览器（未添加依赖）：

```powershell
$env:PLAYWRIGHT_MODULE='C:/Users/22526/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'
$env:UI_BROWSER_PATH='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
cd D:\github-3\FirstSun-thesis
node mall-uniapp/pages/pharmacy/tests/ui.test.cjs
```

## 差异与后续清单

1. **四栏导航**：按 HANDOFF 保留首页/分类/购物车/我的；没有将无顾客接口的 AI 加入第五栏。AI 展示明确未开放。未伪造消息、通知数量或回复。
2. **数据与图片**：全部生产页面取既有接口，服务端运费当前为 0，积分不使用原型常量。图片使用实际 `imageUrl`/既有资源并有失败兜底，不以原型商品数据替换真实记录。
3. **处方能力**：`AppPrescRecordController.create` 当前明确拒绝登记；药店 AI 仅有管理端权限，顾客消息没有完整的药店会话/事件/未读契约。后续需要单独补齐私有上传与授权读取、顾客 API、消息持久化和权限，再开放对应入口。
4. **处方服务端差距**：`WxOrderServiceImpl` 已校验处方归属、门店、有效状态、审方通过以及处方药必须关联处方；没有完整证明患者、药品明细/数量/审核版本绑定和并发单次使用。当前前端关闭处方购买只是交付边界，**不是新增服务端授权保证**；本轮未改后端。处方详情包装/详细资料保持受限，未接通时不存在可用的审核后购买页。
5. **立即购买限制**：后端仍按购物车已选项创建订单。前端现已排除其他勾选项，但多个选择接口非原子操作；跨设备同时改购物车仍需后续服务端订单快照契约。失败可能已改变购物车勾选，不会删除其他商品。
6. **支付**：支付能力仍是实例级开关，页面查询失败时关闭；实际支付请求独立受服务端校验。没有真实微信支付、租户级支付配置或自动支付成功。仅单独创建的安全测试环境可人工启用模拟支付，不能把 profile 当作已隔离证明，也不得在现有云端演示实例启用。
7. **发布与验收**：真实微信登录、微信端安全区/字体放大/键盘、真机网络、远程 HTTPS 参数与合法域名、业务所有权/租户/门店真实联调仍需后续验证。商品档案有既有客户端缓存，最终价格/库存以实际服务端下单结果为准，本轮真实变化联调未验证。

本轮保持待审查状态。
