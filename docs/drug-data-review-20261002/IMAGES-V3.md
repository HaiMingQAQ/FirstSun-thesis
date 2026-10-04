# 五种样例图片预览 v3

更新日期：2026-10-02。采用1张可对应产品的许可实拍图和4张带名称的演示图，每种样例已单独配图；没有写入数据库或接入运行中的商品静态目录。药品字段及对应厂家说明书继续见 [五种资料样例](SAMPLES-V2.md)，最新版图片映射及完整提示词见 [samples-v3.json](samples-v3.json)。

|样例|图片|
|---|---|
|三九胃泰颗粒|名称演示图|
|温胃舒颗粒|名称演示图|
|温胃舒胶囊|名称演示图|
|维生素E软胶囊100mg×60粒|许可实拍图|
|苯磺酸氨氯地平片|名称演示图|

原来的胃药货架合照、阿莫西林、保济丸、格华止等许可照片继续保留在 [原照片预览](PREVIEW.md)。货架合照作为参考；后三种仍有资料完整性或版本匹配缺口，用户接受照片不等于解决医学资料缺口。

## S01 三九胃泰颗粒

20g/袋×10袋；华润三九医药股份有限公司；OTC甲类。

![三九胃泰颗粒名称演示图](D:/github-3/FirstSun-thesis/docs/drug-data-review-20261002/generated-demo-images/s01-sanjowei-name-v1.png)

内置image_gen生成；图内标明“演示插图，非实际商品包装”。名称已按资料清单逐字核对。没有绘制虚构包装、文号、条码、规格、功效或Rx/OTC图标；处方属性来自真实档案，不由图片推断。

## S02 温胃舒颗粒

10g/袋×10袋；合肥华润神鹿药业有限公司；OTC甲类。

![温胃舒颗粒名称演示图](D:/github-3/FirstSun-thesis/docs/drug-data-review-20261002/generated-demo-images/s02-wenweishu-granules-name-v1.png)

内置image_gen生成；图内标明“演示插图，非实际商品包装”。名称已按资料清单逐字核对。没有绘制虚构包装、文号、条码、规格、功效或Rx/OTC图标；处方属性来自真实档案，不由图片推断。

## S03 温胃舒胶囊

0.4g/粒×24粒；合肥华润神鹿药业有限公司；OTC甲类。

![温胃舒胶囊名称演示图](D:/github-3/FirstSun-thesis/docs/drug-data-review-20261002/generated-demo-images/s03-wenweishu-capsules-name-v1.png)

内置image_gen生成；图内标明“演示插图，非实际商品包装”。名称已按资料清单逐字核对。没有绘制虚构包装、文号、条码、规格、功效或Rx/OTC图标；处方属性来自真实档案，不由图片推断。

## S04 维生素E软胶囊

100mg/粒×60粒；国药控股星鲨制药（厦门）有限公司；OTC甲类。

![维生素E软胶囊实拍](D:/github-3/FirstSun-thesis/docs/drug-data-review-20261002/licensed-reference-images/star-shark-ve-reference.jpg)

作者：HualinXMN。[原始文件页](https://commons.wikimedia.org/wiki/File:Sinopharm_Star_Shark_VE_Soft_Capsules.jpg)；[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)；沿用未修改的原图。不表示作者或企业背书。正式商品展示时同样需要提供可见署名。

## S05 苯磺酸氨氯地平片

5mg/片（以氨氯地平计）×14片；国药集团容生制药有限公司；Rx。

![苯磺酸氨氯地平片名称演示图](D:/github-3/FirstSun-thesis/docs/drug-data-review-20261002/generated-demo-images/s05-amlodipine-name-v1.png)

内置image_gen生成；图内标明“演示插图，非实际商品包装”。名称已按资料清单逐字核对。没有绘制虚构包装、文号、条码、规格、功效或Rx/OTC图标；处方属性来自真实档案，不由图片推断。

## 使用记录

4张名称演示图均为1254×1254 PNG；维生素E沿用4624×3472 JPEG原图。所有文件实际解码、名称与标识目视检查以及SHA256核验已完成。提示词、文件大小、哈希、来源与许可在结构化清单中保存。照片许可与生成图性质分开记录。

当前文件是审核样例资源，不是图片运行态验收结果。没有修改01的UI、订单、AI或咨询文件；没有为了接图改小程序转换层，没有写数据库或库存。正式接入前仍需按已有静态机制设置两端可访问路径并验证真机加载，保留生成图标识与实拍图署名。5种样例的医学字段、价格、库存及导入确认要求不因图片方案改变而放宽。

