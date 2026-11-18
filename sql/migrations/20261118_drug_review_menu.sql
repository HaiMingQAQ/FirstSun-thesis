-- 新增审核工作台；仅沿用已具备药品查询和审核权限的角色，不授予新操作权限。
START TRANSACTION;
INSERT INTO system_menu (name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,create_time,updater,update_time,deleted)
SELECT '药品审核','',2,m.sort+1,m.parent_id,'drug-review','ep:document-checked','pharmacy/base/drug/review','PharmacyDrugReview',0,b'1',b'1',b'1','1',NOW(),'1',NOW(),b'0'
FROM system_menu m WHERE m.component='pharmacy/base/drug/index' AND m.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM (SELECT component,deleted FROM system_menu) x WHERE x.component='pharmacy/base/drug/review' AND x.deleted=b'0') LIMIT 1;
SET @fs_review_id=(SELECT id FROM system_menu WHERE component='pharmacy/base/drug/review' AND deleted=b'0' LIMIT 1);
INSERT INTO system_role_menu (role_id,menu_id,creator,create_time,updater,update_time,deleted,tenant_id)
SELECT DISTINCT rm.role_id,@fs_review_id,'1',NOW(),'1',NOW(),b'0',rm.tenant_id FROM system_role_menu rm
JOIN system_menu m ON m.id=rm.menu_id AND m.component='pharmacy/base/drug/index' AND m.deleted=b'0'
WHERE rm.deleted=b'0' AND @fs_review_id IS NOT NULL
AND EXISTS (SELECT 1 FROM system_role_menu r JOIN system_menu p ON p.id=r.menu_id WHERE r.role_id=rm.role_id AND r.tenant_id=rm.tenant_id AND r.deleted=b'0' AND p.deleted=b'0' AND p.permission IN ('pharmacy:base:drug:approve','*:*:*'))
AND EXISTS (SELECT 1 FROM system_role_menu r JOIN system_menu p ON p.id=r.menu_id WHERE r.role_id=rm.role_id AND r.tenant_id=rm.tenant_id AND r.deleted=b'0' AND p.deleted=b'0' AND p.permission IN ('pharmacy:base:drug:query','*:*:*'))
AND NOT EXISTS (SELECT 1 FROM (SELECT role_id,menu_id,deleted,tenant_id FROM system_role_menu) x WHERE x.role_id=rm.role_id AND x.tenant_id=rm.tenant_id AND x.menu_id=@fs_review_id AND x.deleted=b'0');
-- 只追加到已包含药品档案及审核权限的套餐，保留全部原菜单。
UPDATE system_tenant_package t SET t.menu_ids=JSON_ARRAY_APPEND(t.menu_ids,'$',@fs_review_id),t.update_time=NOW()
WHERE t.deleted=b'0' AND @fs_review_id IS NOT NULL AND JSON_VALID(t.menu_ids)
AND NOT JSON_CONTAINS(t.menu_ids,CAST(@fs_review_id AS JSON))
AND EXISTS (SELECT 1 FROM system_menu m WHERE m.component='pharmacy/base/drug/index' AND m.deleted=b'0' AND JSON_CONTAINS(t.menu_ids,CAST(m.id AS JSON)))
AND EXISTS (SELECT 1 FROM system_menu m WHERE m.permission IN ('pharmacy:base:drug:approve','*:*:*') AND m.deleted=b'0' AND JSON_CONTAINS(t.menu_ids,CAST(m.id AS JSON)));
COMMIT;
