-- FirstSun RAG 基线数据（幂等，可重复执行）
--
-- 用途：新环境 / 另一台机器拉起代码后，补齐两处「不在仓库里、但缺了就跑不起来」的数据：
--   1. 本地向量化服务在 yudao 里的平台密钥与向量模型（type=5）；
--   2. 管理端「药店业务 → AI 助手」菜单入口（menu 22350）及角色绑定。
--
-- 执行：
--   docker exec -i firstsun-mysql-1 mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--     --default-character-set=utf8mb4 firstsun_followup_20261012 < deploy/sql/seed-rag-baseline.sql
--
-- 然后建索引：
--   curl -X POST http://127.0.0.1:28080/admin-api/pharmacy/ai/knowledge/reindex \
--        -H "Authorization: Bearer <token>" -H "tenant-id: 901"
--
-- 说明：
--   - ai_api_key.url 用 Spring 占位符，由容器环境变量 FIRSTSUN_EMBEDDING_URL 解析
--     （compose 默认 http://embedding:8000），因此同一份库在宿主机/容器/别的机器都不用改数据；
--   - api_key 只是占位，本地服务不校验，但不能留空（yudao 的 validateApiKey 会拒绝空值）；
--   - 菜单 id 22350、角色 95103(管理员)/95101(店员) 是 901 租户的既有权色，勿在别的租户照抄。

SET NAMES utf8mb4;

-- 1) 向量化平台密钥 + 向量模型
INSERT INTO `ai_api_key` (id, name, api_key, platform, url, status, creator, create_time, updater, update_time, deleted)
VALUES (29002, 'FirstSun 本地向量化服务', 'local-embedding', 'OpenAI', '${FIRSTSUN_EMBEDDING_URL}', 0,
        '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE name = VALUES(name), api_key = VALUES(api_key), platform = VALUES(platform),
                        url = VALUES(url), status = VALUES(status), update_time = NOW();

INSERT INTO `ai_model` (id, key_id, name, model, platform, type, sort, status, temperature, max_tokens,
                        max_contexts, creator, create_time, updater, update_time, deleted)
VALUES (29002, 29002, 'BGE 中文向量（本地）', 'bge-small-zh-v1.5', 'OpenAI', 5, 0, 0, NULL, NULL,
        NULL, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE key_id = VALUES(key_id), name = VALUES(name), model = VALUES(model),
                        platform = VALUES(platform), type = VALUES(type), status = VALUES(status),
                        update_time = NOW();

-- 2) 管理端 AI 助手菜单
-- 背景：admin-ui/src/views/pharmacy/ai/index.vue 与后端 /admin-api/pharmacy/ai/* 均已完成，
--       权限点 pharmacy:ai:chat（menu 22349）也已分配，但 system_menu 没有 type=2 的菜单项，
--       结果是「有权限、没入口」，页面上找不到 AI 助手。
INSERT INTO `system_menu`
  (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
   status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
VALUES
  (22350, 'AI 助手', 'pharmacy:ai:chat', 2, 91, 22000, 'ai', 'ep:magic-stick',
   'pharmacy/ai/index', 'PharmacyAiIndex', 0, b'1', b'1', b'1', 'seed-rag-baseline', NOW(), 'seed-rag-baseline', NOW(), b'0')
ON DUPLICATE KEY UPDATE name = VALUES(name), permission = VALUES(permission), type = VALUES(type),
                        sort = VALUES(sort), parent_id = VALUES(parent_id), path = VALUES(path),
                        icon = VALUES(icon), component = VALUES(component),
                        component_name = VALUES(component_name), status = VALUES(status),
                        visible = VALUES(visible), update_time = NOW();

-- 角色绑定
-- 注意：system_role_menu 只有自增主键 id，没有 (role_id, menu_id) 唯一键，
--       所以 INSERT IGNORE / ON DUPLICATE KEY 都不会去重，重复执行会堆出多行。
--       这里用 NOT EXISTS 显式判重（只认未删除的绑定）。
INSERT INTO `system_role_menu`
  (role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 95103, 22350, 'seed-rag-baseline', NOW(), 'seed-rag-baseline', NOW(), b'0', 901 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `system_role_menu`
                  WHERE role_id = 95103 AND menu_id = 22350 AND tenant_id = 901 AND deleted = b'0');

INSERT INTO `system_role_menu`
  (role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 95101, 22350, 'seed-rag-baseline', NOW(), 'seed-rag-baseline', NOW(), b'0', 901 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `system_role_menu`
                  WHERE role_id = 95101 AND menu_id = 22350 AND tenant_id = 901 AND deleted = b'0');

-- 3) 自检：应有两行 ai_model(id=29002) 与一行 system_menu(id=22350)
SELECT 'ai_model' AS t, COUNT(*) AS n FROM `ai_model` WHERE id = 29002
UNION ALL
SELECT 'system_menu', COUNT(*) FROM `system_menu` WHERE id = 22350
UNION ALL
SELECT 'role_menu', COUNT(*) FROM `system_role_menu` WHERE menu_id = 22350;
