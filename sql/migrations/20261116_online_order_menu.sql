-- 幂等重命名，保留菜单编号及原角色授权。
UPDATE system_menu SET name = '线上订单' WHERE component = 'pharmacy/member/order' AND deleted = b'0';

-- 沿用仓库菜单角色可见范围；接口独立校验库存查询权限与门店。
INSERT INTO system_menu (name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,create_time,updater,update_time,deleted)
SELECT '库存查询','',2,5,m.parent_id,'query','ep:search','pharmacy/inventory/query/index','PharmacyInventoryQuery',0,b'1',b'1',b'1','1',NOW(),'1',NOW(),b'0'
FROM system_menu m WHERE m.component='pharmacy/inventory/warehouse/index' AND m.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM (SELECT component,deleted FROM system_menu) x WHERE x.component='pharmacy/inventory/query/index' AND x.deleted=b'0') LIMIT 1;
INSERT INTO system_role_menu (role_id,menu_id,creator,create_time,updater,update_time,deleted,tenant_id)
SELECT rm.role_id,q.id,'1',NOW(),'1',NOW(),b'0',rm.tenant_id FROM system_role_menu rm
JOIN system_menu w ON w.id=rm.menu_id AND w.component='pharmacy/inventory/warehouse/index' AND w.deleted=b'0'
JOIN system_menu q ON q.component='pharmacy/inventory/query/index' AND q.deleted=b'0'
WHERE rm.deleted=b'0'
AND EXISTS (SELECT 1 FROM system_role_menu granted JOIN system_menu permission_menu ON permission_menu.id=granted.menu_id
    WHERE granted.role_id=rm.role_id AND granted.tenant_id=rm.tenant_id AND granted.deleted=b'0'
    AND permission_menu.deleted=b'0' AND permission_menu.permission IN ('pharmacy:inventory-batch:query','*:*:*'))
AND NOT EXISTS (SELECT 1 FROM (SELECT role_id,menu_id,deleted,tenant_id FROM system_role_menu) x WHERE x.role_id=rm.role_id AND x.tenant_id=rm.tenant_id AND x.menu_id=q.id AND x.deleted=b'0');
