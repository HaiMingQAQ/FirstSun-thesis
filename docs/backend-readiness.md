# 后端配套：离线交付与隔离验证入口

本轮不执行数据库或HTTP。新增45–47只追加到`deploy/local-migrations.json`；01–44和旧根Compose挂载保持。已有本地迁移工具使用清单，只有通过00许可后才能执行；不直接重放旧初始化，也不在未知实例上运行。

## 45：明确选择的咨询授权

`20261102_a_firstsun_consultation_grant.sql`只选择FirstSun/163、药店套餐114和tenant_admin/167。租户名称、套餐关联、角色租户/代码/名称/状态、唯一性、菜单的组件/permission/父子结构、已有药店根授权和JSON格式都必须符合；套餐被其他活跃租户共用或角色关系异属、重复时拒绝。没有这些演示对象的部署应停止并由运营重新选择对象，不自动向别的租户或角色推广。

从`PharmacyConsultation`和`pharmacy:consultation:query/reply`解析真实ID，仅向套餐数组追加缺失ID、向角色167追加缺失关系。现存其他授权保持，没有覆盖整个套餐或删除角色授权。三项菜单不使用自增值定位；父目录本来必须可用。42“显式授权”的原则保持，门店/员工/药师验证不变，其他员工须由正常后台明确选人授权。

SQL必须同一连接顺序执行、不带`--force`，维护窗口串行执行。每个迁移以严格模式下临时守卫表的NOT NULL错误拒绝缺失/冲突，全部持久DML还带对应`@fs*_ok`条件；失败立即断开该连接回滚未提交内容，不继续后续版本。临时表只属于该会话，不创建永久辅助表/存储过程。

## 46：只修字典展示

`20261102_b_member_status_labels.sql`通过dict_type/value唯一定位：0启用、1禁用，更新标签、排序、展示颜色和字典说明。后端MemberUserServiceImpl的STATUS_ENABLE=0保持，不写任何会员记录或其他字典。缺失/重复字典项拒绝，重复执行不改行或时间戳。

## 47：四类通用AI菜单精确停用

`20261102_c_unrelated_ai_menus_disable.sql`按8个确切component_name、组件路径、/ai→console祖先和10个确切操作permission交叉核对，才设置18行status=1、visible=false。组件可带或不带`.vue`后缀。组件重名/路径冲突、缺失菜单、权限被挂到别页或存在未识别子项，均拒绝而非按AI根或模糊前缀批量停用。

保留药店AI、pharmacy:ai:chat/22349、采购22300、AI根/console、模型/API密钥配置和全部原角色/套餐关系。不删AI表、接口、模块或供应商SDK。

语义已核：`routerHelper.ts`的visible转换为hidden，只隐藏导航。AuthController.getPermissionInfo调用MenuService.filterDisableMenus后，禁用页面/按钮及禁用祖先下的节点不再进入新返回的菜单树和前端权限集合。**PermissionService.hasAnyPermission的菜单permission查询没有status过滤，且超级管理员有单独放行；保留角色关系时，不能承诺直接调用这些旧API一定被服务端拒绝。** 这是菜单配套收口，不是后端能力删除或新的安全边界。

SQL直写不会自动调用应用缓存失效。后续执行由00安排维护窗口及现有正常缓存刷新/重新登录过程；仅重新登录不保证所有服务端Redis权限缓存都失效。此阶段不操作Redis、不改缓存/认证策略，也不通过删除接口或授权扩大范围。

## 独立合成登录用户绑定已有演示药师（默认干跑）

现有正常接口足够，不新增Java或后门。下述为**明确步骤**而非自动执行脚本；默认只核读和输出脱敏计划。00批准隔离HTTP执行前，不创建用户、角色、绑定或登录。

参数契约：隔离API基址、tenant-id=163、employeeId=163012、empNo=FS-PHA-01、storeId=407，以及已核隔离schema/UUID。管理员令牌仅在受限runtime内存获取；随机username/password仅在获准执行时生成，password遵守UserSaveReqVO的4–16字符限制，username为4–30字母数字。不得把令牌、密码写进SQL/说明/公开日志/进程参数，不能借用0407/407作为药师。

1. 通过正常`GET /admin-api/system/tenant/get?id=163`（需system:tenant:query）核名称FirstSun、packageId=114和有效期限；用用户分页总数核当前accountCount仍容许创建一个用户，满额则停止，不改套餐/限额。无此管理权限可由获准操作者只读证明，不提升账号权限。先GET药师详情`/admin-api/pharmacy/base/employee/get?id=163012`（pharmacy:base:employee:query），核empNo、storeId、position=2、status=1、非空演示资质和未过期日期。保存受限指纹；userId必须为空。若已有绑定，停止人工复核，不能覆盖或重建员工；不改执业证号、有效期、姓名、门店或岗位。
2. 正常GET角色、菜单及套餐可选项，解析三项咨询菜单和处方页面、`pharmacy:prescription:query`/`review`及其必要祖先（实际permission以源码为准）；先核套餐114已包含要选的既有审方菜单。没有权限或缺少资质时停止，不能抄全套餐、授super_admin或用租户管理员167作为新药师角色。步骤1的操作员可有管理权限，新药师用户不应继承管理权限。
3. 后续缩写端点均相对`/admin-api`。确认新随机username和新专用role.code均不存在（`GET /system/user/page`、`/system/role/page`，tenant-id固定163）。正常`POST /system/role/create`创建新自定义启用角色（name/code/sort/status=0/remark标明隔离合成用途）；用`POST /system/permission/assign-role-menu`只分配步骤2解析出的最小集合。原RoleService新建角色默认dataScope=ALL，因此在绑定任何用户前须正常`POST /system/permission/assign-role-data-scope`将**该新角色**设为`dataScope=5`（SELF）、`dataScopeDeptIds=[]`并回读确认；不更改其他角色范围。此菜单赋权接口是替换式，**只用于新角色**；提交前重新读角色菜单，非空且不属于本次时停止，不能覆盖旧角色。
4. 正常`POST /system/user/create`创建独立合成用户（username、运行时随机password、nickname、remark；无真实mobile/email，不发送短信邮件）。UserService默认启用；GET `/system/user/get?id=<返回ID>`验证status=0和用户名。只给这个本次新用户通过`POST /system/permission/assign-user-role`赋予新最小角色；先读`list-user-roles`确认没有异属已有角色，不给167/超级管理员。创建响应不确定时只读核对并停止，不盲重试创建。
5. 重读药师详情，核userId仍为空且步骤1的其他字段/资质/岗位/日期指纹未变；必要时人工暂停管理并发修改。正常`PUT /pharmacy/base/employee/update`发送EmployeeSaveReqVO完整现有字段（id/empNo/empName/phone/storeId/position/pharmacistNo/licenseExpire/healthCertExpire/hireDate/status），**仅新增userId=<本次新用户ID>**，不直接SQL写ph_employee。系统关联用户校验与同租户唯一关联仍由原EmployeeService执行。接口没有CAS，若存在并发编辑风险则停止，不宣称该流程提供原子比较替换。
6. 回读员工，核只新增userId、岗位/资质/日期/门店等保持；核新用户只有最小角色。后续获准时才用新合成凭据走正常登录验证，API/服务端仍执行药师与门店校验。不调用临床/处方业务来补造资质；失败保留新用户/角色供复核，不自动删改原用户、资质或0407/407。

必要操作者权限：system:user:create/query、system:role:create/query、system:menu:query、system:permission:assign-role-menu/assign-role-data-scope/assign-user-role、pharmacy:base:employee:query/update及有权核租户/套餐的操作员。不能把这些管理权限授给新药师。EmployeeService既有validateUserIdForSave执行用户存在、启用和员工关联唯一性；药师业务资格仍由原服务校验。实际HTTP未测，不能把本步骤写成已绑定或已登录。

## 给12的MySQL验证与重放

`python scripts/readiness/verify.py`默认只输出计划，连接数0。真实运行必须另获00许可、提供已准备好的独立副本schema（名称`firstsun_readiness_<唯一后缀>`，含01–44、既有演示记录与完整AI菜单；不是生产或原演示库），显式参数如下：

```text
python scripts/readiness/verify.py --apply --mysql <mysql.exe绝对路径> --client-file <受限client.cnf> --database firstsun_readiness_<唯一后缀> --expected-uuid <00已核UUID> --port <隔离实例回环端口> --private-evidence-dir <受限runtime下不存在的新目录>
```

验证器先核每次连接所选schema/UUID；只运行45–47，记录真实命令、退出码和私有stderr。内存比较所有菜单、所有角色/套餐授权、目标字典；会员/员工/系统用户/业务角色/租户和ai_model/ai_api_key全行必须不变（不把这些原始敏感行写出）。只允许3项新角色关系和套餐追加、目标字典展示元数据、18项AI状态/可见性及对应审计列改变；第二次执行必须包括update_time在内逐行完全一致。失败停止，不DROP或重导。

12还须在**另外由00准备的隔离副本**进行下列fixture，不在通过库/原库删改身份刷绿：

- 菜单ID漂移：只在测试副本同步重映射咨询三项、八AI页面/十按钮及父子/已有关系和套餐引用，换成与原数字无关的新ID；执行验证器正向模式仍应通过，不能改component/permission来绕过。
- 异属：另一副本令167属于别租户，或163不再名为FirstSun、114被其他租户共用；执行`--expect-rejection 45`，须非零退出且所有快照行不变。
- 冲突/缺失：另外副本复制咨询component_name/permission或错挂父级；执行`--expect-rejection 45`。删除/重复字典项用`--expect-rejection 46`；错挂AI权限、增加未知子项或缺失AI组件用`--expect-rejection 47`。每个fixture单独副本，不循环DROP重导。
- 正向回放必须保持其他角色/套餐/会员及药店AI、采购、模型和祖先原字节行数据。验证器结果只有脱敏ID/计数/摘要，实际SQL/错误保留受限目录。需使用有权执行这些配套的操作员client；权限不足停止，不升权。

离线单测只验证配套结构和差异判定器，不能替代MySQL语法/事务/JSON行为或HTTP登录/绑定实测。后续MySQL、缓存刷新、最小角色的正常登录/绑定验证均待00衔接。本阶段不构建后端、不运行旧989/245/29项或恢复旧E2E。
