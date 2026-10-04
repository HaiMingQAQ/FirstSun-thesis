# 分类页分页修正（2026-10-02）

## 改动范围

- `mall-uniapp/sheep/api/pharmacy/server.js`：新增前端 `productPage` 契约，仍调用现有药品分页和库存只读接口，返回标准化商品列表及后端 `total`；原 `products` 数组契约保持。
- `mall-uniapp/sheep/api/pharmacy/demo.js`：保持显式演示适配器的分页契约，仅用于现有演示 / 测试，不切换真实运行配置。
- `mall-uniapp/pages/pharmacy/category.vue`：每页 20 条，用已请求页的偏移与后端 `total` 判断后续页，按钮及触底可继续加载；ID 去重、并发加载保护、迟到响应失效、搜索 / 分类 / 筛选从第一页重置。追加失败保留已有结果，重试同一页。
- 现有有货 / 非处方筛选仍在前端执行。被筛选隐藏的整页会继续遍历，直到发现新匹配项或后端目录末尾，遍历中显示加载状态，不提前显示空状态。匹配数量标为“已加载匹配”，不冒充全目录过滤后的总数。
- `mall-uniapp/pages/pharmacy/index.vue`：精选请求最多 5 条，保留 5 条展示上限及“查看全部”分类入口。
- `mall-uniapp/pages/pharmacy/tests/client.test.cjs`、新增 `category-pagination.test.cjs`：适配器分页参数 / total、超过 100 条、去重、隐藏页面、失败重试、条件重置与迟到请求测试。

没有修改后端接口、权限、购物流程、药品数据、数据库结构或导入 SQL；没有自动暂存、提交、推送或部署。原工作区变化保留。

## 验证

| 项目 | 结果与边界 |
| --- | --- |
| 分页定向测试 | `node --test pages/pharmacy/tests/category-pagination.test.cjs`：7 项通过。 |
| 既有适配器测试 | `npm run test:pharmacy` 通过；该测试使用 stub 网络，不称为真实联调。 |
| H5 构建 | 通过，`.local/category-pagination-h5-build.log`。 |
| 微信开发编译 | 完成并持续 watch，`.local/category-pagination-mp-dev.log`；本轮 `dist/dev/mp-weixin` 包含更新的分类 / 首页 JS 和 `app.json`。 |
| 真实后端 H5 | 本次只读接口 `total=15`；分类页显示 15 条，首页显示 5 条，查看全部入口存在。没有写数据库。真实多页目录未验证，因为此环境仅有 15 条。 |
| 独立合成 UI | 独立浏览器上下文仅拦截药品分页 / 库存接口，105 条合成商品能全部续载，前两页被过滤后仍找到第 41 条，搜索从第一页重置，脚本错误零；不计为真实后端多页验收。 |
| 界面 | 实际查看 320 / 375 / 430px 筛选页截图，无横向溢出；证据 `.local/category-pagination-ui/` 与 `report.json`。 |

现有循环 chunk、Sass 弃用和小程序 img 选择器警告保留。微信开发者工具内运行及真机仍未验证，H5 截图不能替代微信运行验收。

预览：`http://127.0.0.1:4189/pages/pharmacy/category`。微信导入：`D:\github-3\FirstSun-thesis\mall-uniapp\dist\dev\mp-weixin`，启动 `pages/pharmacy/index`，本机开发后端 `http://127.0.0.1:28080`。

采用现有后端偏移分页；若遍历期间目录增删，去重可消除重复 ID，但不保证同一时刻的目录快照。重新搜索或页面再次显示会从第一页刷新。库存 / 价格继续以实际下单时服务端校验为准。
