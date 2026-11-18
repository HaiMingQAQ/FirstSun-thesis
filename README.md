# FirstSun · 融合 AI 助手的多端药店管理系统

FirstSun 是一个毕业设计项目，包含电脑顾客端、手机顾客端（H5 / 微信小程序）和药店管理端。顾客可以查商品、阅读说明书、提交处方申请或咨询门店。药店员工可以维护档案和库存、审核资料、处理订单，也可以让 AI 预填药品资料，核对后再提交审核。

[从零部署](docs/local-demo-install.md) · [演示账号](docs/local-demo-install.md#2-初始化空库) · [界面展示](#界面展示) · [开发文档](docs/README.md)

## 从零部署

1. 下载本仓库，在仓库根目录打开 PowerShell 7。准备 Docker Compose 和 Node.js **22.13.0 或以上**。
2. 按[从零搭建本机演示环境](docs/local-demo-install.md)复制本机配置、初始化空库并构建服务。初始化器会导入 **23 款商品及其包装图**，其中有 17 款非处方药、3 款处方药和 3 款医疗器械，也会创建演示账号和合成订单、咨询、处方记录。
3. 打开顾客端或管理端，按部署文档的账号表登录。若要体验 H5 或 AI 说明书检索，按同一文档启动 H5 或配置可选的 RAG 索引。

| 界面 | 首次部署的默认地址 |
| --- | --- |
| 电脑顾客端 | <http://127.0.0.1:5193> |
| 药店管理端 | <http://127.0.0.1:38081> |
| 手机 H5 | <http://127.0.0.1:4189>（按部署指南单独启动） |

已有日常环境按 [README.local.md](README.local.md)启动。演示目录和包装图随仓库交付，门店库存为初始化时的演示库存。处方药保留审核规则。[包装图来源](deploy/demo-assets/catalog/image-sources.md)

## 界面展示

### 电脑顾客端

顾客可按通用名或商品名查找当前门店商品，在详情页查看规格、库存和说明书。

[<img src="docs/images/readme/customer-product-leaflet.jpg" width="920" alt="电脑顾客端商品详情与说明书">](docs/images/readme/customer-product-leaflet.jpg)

### 手机顾客端（H5）

<table>
  <tr>
    <th>首页</th>
    <th>商品详情</th>
    <th>AI 查询商品</th>
  </tr>
  <tr>
    <td><a href="docs/images/readme/h5-home.jpg"><img src="docs/images/readme/h5-home.jpg" width="220" alt="H5 首页"></a></td>
    <td><a href="docs/images/readme/h5-product.jpg"><img src="docs/images/readme/h5-product.jpg" width="220" alt="H5 商品详情"></a></td>
    <td><a href="docs/images/readme/h5-ai-result.jpg"><img src="docs/images/readme/h5-ai-result.jpg" width="220" alt="H5 AI 助手返回含售价和库存的商品卡"></a></td>
  </tr>
</table>

### 药店管理端

[<img src="docs/images/readme/admin-drug-catalog.png" width="920" alt="管理端药品档案，显示包装图、规格和审核状态">](docs/images/readme/admin-drug-catalog.png)

AI 新增药品使用同一条答辩演示资料。员工核对预填内容并提交后，档案进入待审核队列，由有权限的员工处理。

| 输入资料，AI 预填 | 核对属性，人工提交 |
| --- | --- |
| [<img src="docs/images/readme/admin-ai-prefill.png" width="460" alt="输入演示资料并生成预填药品卡片">](docs/images/readme/admin-ai-prefill.png) | [<img src="docs/images/readme/admin-ai-confirm.png" width="460" alt="核对药品属性并点击提交">](docs/images/readme/admin-ai-confirm.png) |
| **档案已创建** | **在审核队列查回记录** |
| [<img src="docs/images/readme/admin-ai-created.png" width="460" alt="药品档案已创建并标记待审核">](docs/images/readme/admin-ai-created.png) | [<img src="docs/images/readme/admin-drug-review.png" width="460" alt="药品审核列表显示同一条待审记录">](docs/images/readme/admin-drug-review.png) |

截图中门店库存和审核数量为采集时的界面数据。

## 功能与技术

| 模块 | 功能 | 技术 |
| --- | --- | --- |
| 电脑顾客端 | 搜索、商品详情、购物车、订单、处方申请、邮箱会员登录和门店咨询 | Vue 3、Vite、Nginx |
| 手机顾客端 | H5 购物、邮箱注册与登录、找回密码、个人资料；提供微信小程序构建 | uni-app、Vue 3、Pinia |
| 药店管理端 | 药品档案与审核、门店库存、采购收货、处方审核、线上订单与 POS | Vue 3、TypeScript、Element Plus |
| AI 助手 | 顾客端查询门店商品和说明书依据；管理端查询库存、预填药品资料和生成采购草稿 | Spring AI、SSE、Qdrant、本地向量化服务 |
| 后端与部署 | 统一租户、会员、权限和业务接口 | Java 17、Spring Boot 3、MyBatis Plus、MySQL 8、Redis 7、Docker Compose |

支付采用本机模拟流程。微信小程序已提供构建入口，真机登录和当前版本的完整业务验收仍待验证。AI 用于商品与资料查询，处方由药师审核。

## 项目来源

毕设题目为《融合 AI 助手的多端药店管理系统设计与实现》。项目基于 yudao / RuoYi-Vue-Pro、yudao-ui-admin-vue3 与芋道商城 uni-app 二次开发。前期团队项目提供药品、库存、采购、POS、处方和会员等业务基础。个人毕设在此基础上完成电脑顾客端和顾客 AI、说明书 RAG、邮箱会员认证、私有处方材料、门店文字咨询及统一部署入口。

## 开发文档

- [本机日常环境](README.local.md) · [数据库初始化](docs/database-init-paths.md) · [可选 RAG 配置](docs/local-demo-install.md#可选-rag-配置)
- [文档导航](docs/README.md) · [项目交接](HANDOFF.md) · [历史验收报告](docs/acceptance/reports/)
- [顾客 AI](docs/customer-ai-development.md) · [处方与通知](docs/prescription-business-development.md) · [门店咨询](docs/store-consultation-development.md)

上游许可与版权声明保留在 [后端](backend/LICENSE)、[管理端](admin-ui/LICENSE) 和 [uni-app 顾客端](mall-uniapp/LICENSE)。
