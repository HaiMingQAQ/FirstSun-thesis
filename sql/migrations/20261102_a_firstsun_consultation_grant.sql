-- 45: Explicitly selected demo tenant/package/role only; migration 42 remains unchanged.
-- MySQL 8, one connection, stop on error (never --force), exclusive migration window.
SET NAMES utf8mb4;
SET @fs45_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@fs45_mode, ''), 'STRICT_ALL_TABLES');
DROP TEMPORARY TABLE IF EXISTS fs45_guard;
CREATE TEMPORARY TABLE fs45_guard (ok TINYINT NOT NULL);
START TRANSACTION;
SET @fs45_root = (SELECT MIN(id) FROM system_menu WHERE path='/pharmacy-base' AND deleted=b'0');
SET @fs45_page = (SELECT MIN(id) FROM system_menu WHERE component_name='PharmacyConsultation' AND deleted=b'0');
SET @fs45_query = (SELECT MIN(id) FROM system_menu WHERE permission='pharmacy:consultation:query' AND deleted=b'0');
SET @fs45_reply = (SELECT MIN(id) FROM system_menu WHERE permission='pharmacy:consultation:reply' AND deleted=b'0');
SET @fs45_ok = (
  (SELECT COUNT(*) FROM system_tenant WHERE id=163 AND name='FirstSun' AND package_id=114 AND status=0 AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_tenant WHERE name='FirstSun' AND deleted=b'0')=1
  AND NOT EXISTS(SELECT 1 FROM system_tenant WHERE id<>163 AND package_id=114 AND deleted=b'0')
  AND (SELECT COUNT(*) FROM system_tenant_package WHERE id=114 AND name='药店' AND status=0 AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_tenant_package WHERE name='药店' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_role WHERE id=167 AND tenant_id=163 AND code='tenant_admin' AND name='租户管理员' AND status=0 AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_role WHERE tenant_id=163 AND code='tenant_admin' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_role WHERE tenant_id=163 AND name='租户管理员' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_menu WHERE path='/pharmacy-base' AND deleted=b'0')=1
  AND EXISTS(SELECT 1 FROM system_menu WHERE id=@fs45_root AND type=1 AND parent_id=0 AND status=0 AND visible=b'1')
  AND (SELECT COUNT(*) FROM system_menu WHERE component_name='PharmacyConsultation' AND deleted=b'0')=1
  AND EXISTS(SELECT 1 FROM system_menu WHERE id=@fs45_page AND parent_id=@fs45_root AND type=2 AND component IN ('pharmacy/consultation/index','pharmacy/consultation/index.vue') AND path='consultation' AND permission='' AND status=0 AND visible=b'1')
  AND (SELECT COUNT(*) FROM system_menu WHERE parent_id=@fs45_root AND path='consultation' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_menu WHERE permission='pharmacy:consultation:query' AND deleted=b'0')=1
  AND EXISTS(SELECT 1 FROM system_menu WHERE id=@fs45_query AND parent_id=@fs45_page AND type=3 AND status=0)
  AND (SELECT COUNT(*) FROM system_menu WHERE permission='pharmacy:consultation:reply' AND deleted=b'0')=1
  AND EXISTS(SELECT 1 FROM system_menu WHERE id=@fs45_reply AND parent_id=@fs45_page AND type=3 AND status=0)
  AND NOT EXISTS(SELECT 1 FROM system_role_menu WHERE role_id=167 AND tenant_id<>163 AND deleted=b'0')
  AND NOT EXISTS(SELECT menu_id FROM system_role_menu WHERE role_id=167 AND tenant_id=163 AND deleted=b'0' GROUP BY menu_id HAVING COUNT(*)>1)
  AND (SELECT JSON_VALID(menu_ids) FROM system_tenant_package WHERE id=114)=1
);
-- JSON operations are separated from the first guard so malformed JSON cannot be accepted.
INSERT INTO fs45_guard VALUES (IF(@fs45_ok,1,NULL));
SET @fs45_ok = @fs45_ok AND (
  (SELECT JSON_TYPE(CAST(menu_ids AS JSON)) FROM system_tenant_package WHERE id=114)='ARRAY'
  AND NOT EXISTS(SELECT 1 FROM system_tenant_package p, JSON_TABLE(p.menu_ids,'$[*]' COLUMNS (n FOR ORDINALITY)) j
    WHERE p.id=114 AND JSON_TYPE(JSON_EXTRACT(p.menu_ids,CONCAT('$[',j.n-1,']')))<>'INTEGER')
  AND (SELECT JSON_CONTAINS(menu_ids,CAST(@fs45_root AS JSON)) FROM system_tenant_package WHERE id=114)=1
  AND EXISTS(SELECT 1 FROM system_role_menu WHERE role_id=167 AND tenant_id=163 AND menu_id=@fs45_root AND deleted=b'0')
);
INSERT INTO fs45_guard VALUES (IF(@fs45_ok,1,NULL));
-- Append missing IDs without recomputing the package or removing any existing grants.
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',@fs45_page),updater='readiness-45',update_time=NOW()
WHERE @fs45_ok AND id=114 AND NOT JSON_CONTAINS(menu_ids,CAST(@fs45_page AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',@fs45_query),updater='readiness-45',update_time=NOW()
WHERE @fs45_ok AND id=114 AND NOT JSON_CONTAINS(menu_ids,CAST(@fs45_query AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',@fs45_reply),updater='readiness-45',update_time=NOW()
WHERE @fs45_ok AND id=114 AND NOT JSON_CONTAINS(menu_ids,CAST(@fs45_reply AS JSON));
INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,create_time,updater,update_time,deleted)
SELECT 167,m.id,163,'readiness-45',NOW(),'readiness-45',NOW(),b'0' FROM system_menu m
WHERE @fs45_ok AND m.id IN (@fs45_page,@fs45_query,@fs45_reply)
AND NOT EXISTS(SELECT 1 FROM system_role_menu r WHERE r.role_id=167 AND r.tenant_id=163 AND r.menu_id=m.id AND r.deleted=b'0');
COMMIT;
DROP TEMPORARY TABLE fs45_guard;
SET SESSION sql_mode = @fs45_mode;
