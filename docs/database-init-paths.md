# 数据库初始化路径：快照恢复与仓库 SQL 初始化

海冥于 2026-11-03 已确认：隔离测试库统一采用**仓库 SQL + 迁移账本 `firstsun_local_migration`** 初始化，不再追查旧快照作为建库前置条件；当时清单为 01–44。当前初始化清单以 [deploy/local-migrations.json](../deploy/local-migrations.json) 为准，截至 2026-11-04 为 **01–47**。快照恢复保留为已有数据库的备份恢复方式。两条路径的数据来源、用途和验收口径不同。

## 两条路径的区别

| 路径 | 来源与用途 | 风险与适用边界 |
| --- | --- | --- |
| 快照恢复 | 对现有库用 `mysqldump` 导出，再导入目标库；用于备份恢复，或在获得授权后复现某一时点的数据状态 | 会携带来源库当时的业务数据、配置及潜在敏感信息；恢复完整性受导出内容、客户端处理和目标环境影响，不能仅凭导出成功认定恢复成功 |
| 仓库 SQL 初始化 | 按版本库清单执行（当前 01–47），配套记录迁移账本；用于新建隔离测试库 | 得到仓库定义的框架、业务结构及初始化/演示数据，不包含现有库的完整历史数据；导入后仍需单独验证权限、配置与业务流程 |

### 快照恢复及历史失败

快照恢复命令的形式如下，仅用于说明该路径。所有尖括号内容均为占位符；`--password` 交互输入对应密码，不把密码写入命令或文档。

```bash
mysqldump --host='<SOURCE_HOST>' --port='<SOURCE_PORT>' --user='<SOURCE_USER>' \
  --password --default-character-set=utf8mb4 --single-transaction \
  '<SOURCE_SCHEMA>' > '<PRIVATE_SNAPSHOT_PATH>'
mysql --host='<TARGET_HOST>' --port='<TARGET_PORT>' --user='<TARGET_USER>' \
  --password --default-character-set=utf8mb4 \
  '<RESTORE_SCHEMA>' < '<PRIVATE_SNAPSHOT_PATH>'
```

快照应保存在仓库外的受控位置。恢复前核对来源、目标与授权，恢复后另行核对表、数据及导入错误；不要把原库快照导入当成仓库 SQL 初始化。

[HANDOFF.md](../HANDOFF.md) 第 45–60 行附近记录的是历史**原库快照导入**失败：原库只读快照导出成功，新测试 schema 两次导入均在第 467 行出现 MySQL 客户端 `Unknown command`，添加 `--binary-mode` 后仍失败，只得到 **12/109 张表**的部分副本。随后停止尝试，没有启动该临时后端，也没有执行认证、下单、付款或履约。

该快照未纳入版本库，失败根因尚未确认。不能据此归因为字符集、CRLF、SQL 内容或业务逻辑问题，也不能把这里的 12/109 当作仓库 01–44 的导入结果。此处的 109 是历史快照记录中的目标表数，不能在缺少快照证据时推断其表组成。

隔离测试库需要可按版本重建的初始化基线。现已确认使用仓库 SQL 加账本，不再依赖旧快照或继续追查其失败来推进测试准备；这一选择不表示旧快照已经修复或成功恢复。

## 仓库 SQL 初始化的标准做法

文件范围和执行顺序以 [deploy/local-migrations.json](../deploy/local-migrations.json) 的编号为准，当前共 47 项、编号连续为 1–47。根 [docker-compose.yml](../docker-compose.yml) 仅引用 [deploy/docker-compose.local.yml](../deploy/docker-compose.local.yml)；后者的 MySQL 仅挂载现有数据卷，已无 `/docker-entrypoint-initdb.d` 挂载，不能再用 Compose 推导初始化清单。不要按文件名字典序执行，也不要把 `sql/migrations` 目录下所有文件一并导入。[迁移说明](../sql/migrations/README.md) 中的分组列表和历史手工命令不能替代这份完整清单。

01 为 `backend/sql/mysql/ruoyi-vue-pro.sql`，02 为 `sql/firstsun_pharmacy_init.sql`，其后按清单依次执行；44 为 `sql/migrations/20261018_a_wx_order_sale_posting.sql`，新增三项为 45 `sql/migrations/20261102_a_firstsun_consultation_grant.sql`、46 `sql/migrations/20261102_b_member_status_labels.sql`、47 `sql/migrations/20261102_c_unrelated_ai_menus_disable.sql`。可选的 `20260919_f_wx_miniapp_app_api.sql`、HTTP 验收 seed 不在这 47 份中。45–47 分别调整指定租户的咨询授权、会员状态展示字典、无关 AI 菜单；它们含目标数据校验，导入前需核对 [后端就绪说明](backend-readiness.md) 的前置条件，不能仅凭表数认定可执行成功。

1. 核对目标实例、端口及 schema，使用新建的专用空库，隔离于原库和演示实例。
2. 在导入业务 SQL 前建立迁移账本，结构与 [scripts/local-migration-helpers.ps1 第 9 行](../scripts/local-migration-helpers.ps1#L9) 的 `Migrate` 函数建表语句一致。
3. 逐文件按清单编号顺序导入（当前 01–47）。每份先记录 `RUNNING`，只有导入无错误才更新为 `APPLIED` 并写入执行时间；保存清单编号、路径及该文件原始字节的 SHA256。
4. 逐份检查错误及退出状态，再核对表数和账本。中断时停止，不用 `--force` 掩盖错误，不把未完成文件标为成功，也不反复导入演示种子。

Windows 日常启动与重建使用 [README.local.md](../README.local.md) 的 `start-local.bat` / `rebuild-local.bat` 入口；它们复用现有数据卷，不执行 SQL 或创建迁移账本。`scripts/local-environment.ps1` 现为入口转接脚本，旧迁移函数已拆入 [scripts/local-migration-helpers.ps1](../scripts/local-migration-helpers.ps1)，仅供离线检查或明确授权的迁移工具复用，日常入口不会调用。该文件第 2–46 行保留 `Migrate`：第 5–9 行检查无账本非空库并定义账本，第 11–32 行检查状态、编号与哈希，第 40–43 行记录 `RUNNING` / `APPLIED` 及执行时间。

现行 Compose 和日常入口均不负责初始化；专用空库须在授权范围内按清单和账本流程单独准备。不要将裸跑清单后得到的 108 张表误报为已经完成带账本的初始化，也不要把启动现有环境视为已执行迁移。

### Linux 手工执行示例

以下是文档命令模板，并非本次重新执行的日志。需安装 MySQL 客户端；从仓库根目录操作。将 `<ISOLATED_SCHEMA>` 等替换为隔离环境信息；`--password` 提示时输入 `<MYSQL_ROOT_PASSWORD>` 对应的本地配置值。

先创建新的空 schema 和账本；仅在确认目标 schema 尚不存在时执行：

```bash
mysql --host='<ISOLATED_HOST>' --port='<ISOLATED_PORT>' --user=root \
  --password --default-character-set=utf8mb4
```

在该客户端中执行：

```sql
CREATE DATABASE `<ISOLATED_SCHEMA>` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `<ISOLATED_SCHEMA>`;
CREATE TABLE firstsun_local_migration (
  id INT PRIMARY KEY,
  path VARCHAR(255) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  applied_at DATETIME NULL
);
```

对清单中的每一份 SQL，先用 `sha256sum '<SQL_PATH_FROM_MANIFEST>'` 获取原始文件哈希，并在目标库记录本次迁移：

```sql
INSERT INTO firstsun_local_migration (id, path, sha256, status)
VALUES (<ID>, '<SQL_PATH_FROM_MANIFEST>', '<SHA256>', 'RUNNING');
```

然后在 shell 中经 stdin 导入该文件，检查退出状态与错误输出：

```bash
mysql --host='<ISOLATED_HOST>' --port='<ISOLATED_PORT>' --user=root \
  --password --default-character-set=utf8mb4 \
  '<ISOLATED_SCHEMA>' < '<SQL_PATH_FROM_MANIFEST>'
```

只有该文件无错误导入成功后，才在目标库执行：

```sql
UPDATE firstsun_local_migration
SET status = 'APPLIED', applied_at = NOW()
WHERE id = <ID> AND status = 'RUNNING';
```

按清单重复至最后一项（当前为 47）。手工流程也必须核对已应用文件的哈希和编号连续性；发生中断先保留证据、人工核对，MySQL DDL 不保证事务回滚。示例不用于已有库的覆盖或重放。

## 108 与 109 的验收口径

| 口径 | 计算方式 | 含义 |
| --- | --- | --- |
| 108 张 BASE TABLE | 当前 47 份仓库 SQL 中共 108 条非临时 `CREATE TABLE`、108 个不同的持久表名 | 仓库 SQL 创建的业务表与框架表合计，不含迁移账本和临时表 |
| 109 张 BASE TABLE | 108 + 1 张 `firstsun_local_migration` | 仓库 SQL 加迁移账本的完整初始化口径 |

本次按清单读取 01–47 全部 SQL，排除注释和字符串内容后分别统计普通与临时建表语句：普通 `CREATE TABLE` 共 108 条、不同持久表名 108 个，且不含 `firstsun_local_migration`。其中 01 为 48 条、02 为 47 条，18/27/31/39/40/43 各 1 条，36 为 3 条，41/42 各 2 条，其余为 0 条。

45–47 不新增持久表；它们分别含 1、1、4 条 `CREATE TEMPORARY TABLE`，合计 6 条、6 个不同临时表名（`fs45_guard`、`fs46_guard`、`fs47_expected`、`fs47_actions`、`fs47_ids`、`fs47_guard`），正常结束时均显式删除。若连临时建表也统计，建表语句合计为 114 条、不同表名 114 个；这不是持久 `BASE TABLE` 数。因此当前仍为 **108 + 1 = 109**。

47 是 SQL 文件/迁移记录数，不是表数。账本若先创建，完整成功导入当前 47 份后，持久表数期望为 109；108 是不计账本的对照口径。这是对当前 01–47 的静态核对结论，不是新增迁移的数据库实测，不应机械套用于增加了其他表的环境。

在目标实例执行以下核对 SQL，替换 schema 占位符；只统计 `BASE TABLE`，不把视图计入：

```sql
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = '<ISOLATED_SCHEMA>' AND table_type = 'BASE TABLE';
```

标准初始化期望结果为 **109**。再检查账本记录数及编号与当前清单一致（当前应有 47 条、编号 1–47）、状态均为 `APPLIED`、执行时间非空，且路径及 SHA256 与清单和文件逐一一致：

```sql
SELECT id, path, sha256, status, applied_at
FROM `<ISOLATED_SCHEMA>`.firstsun_local_migration
ORDER BY id;
```

表数与账本是初始化验收项；相同表数本身不能证明表结构、数据或业务行为完全正确。

## 2026-11-03 实测记录与证据边界

以下数据库实测原文保留原样，均对应当时的 **01–44**。当前 01–47 的静态统计与入口行为见上文，不能据此把当时实测扩展为 45–47 已通过导入。

以下数据库执行结果来自海冥确认的共享 Linux 环境实测，本次文档任务未重新运行数据库导入：

- 环境为 **MySQL 8.0.46**；标准导入方式为 mysql 客户端指定 `--default-character-set=utf8mb4`，经 stdin 按清单逐文件导入。
- 44 份 SQL 导入零错误，得到 **108 张 BASE TABLE**；加迁移账本后为 **109 张**。
- 默认客户端方式、显式 utf8mb4、binary-mode、force、去 CRLF 各方式的表数结果均为 **108**。这些对照结果不表示日常应使用 `--force` 或改写原始文件，也不能解释旧快照的失败原因。

本次文档更新仅静态核对当前 01–47 清单、SQL 和脚本，未重新导入数据库；统计依据及迁移账本的现行脚本位置见上文。

这些证据支持仓库 SQL 的建库结果和 108/109 计数口径，**不代表业务闭环通过**，也不代表认证、下单、支付、履约、库存、积分、权限或页面联调已经验收。业务测试仍须在初始化完成后按各自范围单独执行并记录结果。
