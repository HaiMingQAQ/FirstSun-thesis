-- FirstSun synthetic demo data for a dedicated firstsun_demo_* database.
-- Run via scripts/init-local-demo.ps1 after the repository migrations.
SET NAMES utf8mb4;

CREATE TEMPORARY TABLE firstsun_demo_guard (ok TINYINT NOT NULL CHECK (ok = 1));
INSERT INTO firstsun_demo_guard VALUES (IF(
  DATABASE() REGEXP '^firstsun_demo_[a-z0-9_]+$'
  AND NOT EXISTS (SELECT 1 FROM system_tenant WHERE id = 901 OR name = 'FirstSun-Followup')
  AND COALESCE(CHAR_LENGTH(@demo_manager_hash), 0) = 60
  AND COALESCE(CHAR_LENGTH(@demo_pharmacist_hash), 0) = 60
  AND COALESCE(CHAR_LENGTH(@demo_platform_hash), 0) = 60
  AND EXISTS (SELECT 1 FROM system_users WHERE id=1 AND tenant_id=1 AND username='admin' AND deleted=b'0'),
  1, 0));
DROP TEMPORARY TABLE firstsun_demo_guard;

START TRANSACTION;

UPDATE system_users SET password=@demo_platform_hash
WHERE id=1 AND tenant_id=1 AND username='admin' AND deleted=b'0';

INSERT INTO system_tenant (id, name, contact_name, status, websites, package_id, expire_time, account_count, creator)
VALUES (901, 'FirstSun-Followup', '合成演示', 0, '[]', 0, DATE_ADD(NOW(), INTERVAL 5 YEAR), 10, 'firstsun-demo-seed');

INSERT INTO system_users (id, username, password, nickname, status, tenant_id)
VALUES (95212, 'demomanager', @demo_manager_hash, '演示后台管理员', 0, 901),
       (95112, 'demopharmacist', @demo_pharmacist_hash, '演示药师', 0, 901);

INSERT INTO system_role (id, name, code, sort, data_scope, status, type, tenant_id)
VALUES (95103, '演示后台管理员', 'demo_manager', 1, 1, 0, 2, 901),
       (95101, '演示药师', 'demo_pharmacist', 2, 1, 0, 2, 901);
INSERT INTO system_user_role (user_id, role_id, tenant_id)
VALUES (95212, 95103, 901), (95112, 95101, 901);

-- Match the pharmacy tenant administrator menus and keep the pharmacist scoped to their workflows.
INSERT INTO system_role_menu (role_id, menu_id, tenant_id)
WITH RECURSIVE demo_menus (role_id, id, parent_id) AS (
  SELECT r.id, m.id, m.parent_id FROM system_role r JOIN system_menu m
  WHERE r.tenant_id = 901 AND m.deleted = b'0' AND m.status = 0
    AND (m.id IN (22020, 61252)
      OR m.permission IN ('pharmacy:base:store:query', 'pharmacy:consultation:query', 'pharmacy:consultation:reply')
      OR (r.id = 95103 AND (m.id = 22111 OR m.permission IN
          ('pharmacy:member:order:query', 'pharmacy:member:order:update', 'pharmacy:member:order:verify')))
      OR (r.id = 95103 AND EXISTS (
          SELECT 1 FROM system_role_menu rm
          WHERE rm.role_id=167 AND rm.tenant_id=163 AND rm.menu_id=m.id AND rm.deleted=b'0'
      ))
      OR (r.id = 95101 AND (m.id IN (61220, 61230) OR m.permission IN
          ('pharmacy:prescription:query', 'pharmacy:prescription:review'))))
  UNION DISTINCT
  SELECT child.role_id, parent.id, parent.parent_id
  FROM demo_menus child JOIN system_menu parent ON parent.id = child.parent_id
  WHERE parent.deleted = b'0' AND parent.status = 0
)
SELECT role_id, id, 901 FROM demo_menus;

INSERT INTO ph_store (id, store_code, store_name, address, status, tenant_id)
VALUES (9101, 'FS-LOCAL-DEMO', 'FirstSun 演示门店', '合成演示地址', 1, 901);
INSERT INTO ph_employee (id, emp_no, emp_name, store_id, user_id, position, status, pharmacist_no, license_expire, tenant_id)
VALUES (95231, 'DEMO-MANAGER', '演示后台管理员', 9101, 95212, 9, 1, NULL, NULL, 901),
       (95122, 'DEMO-PHARMACIST', '演示药师', 9101, 95112, 2, 1, 'DEMO-NON-CLINICAL', DATE_ADD(CURRENT_DATE, INTERVAL 5 YEAR), 901);

INSERT INTO member_level (id, name, level, experience, discount_percent, status, tenant_id)
VALUES (9001, '演示会员', 1, 0, 100, 0, 901);
INSERT INTO member_user (id, mobile, register_ip, nickname, status, point, level_id, tenant_id)
VALUES (90011, '19900009011', '127.0.0.1', '合成演示会员', 0, 0, 9001, 901);

INSERT INTO ph_warehouse (id, store_id, wh_code, wh_name, status, tenant_id)
VALUES (9201, 9101, 'FS-DEMO-W', '演示仓库', 1, 901);
INSERT INTO ph_location (id, warehouse_id, location_code, status, tenant_id)
VALUES (9202, 9201, 'DEMO-01', 1, 901);
INSERT INTO ph_category (id, cat_code, cat_name, status, tenant_id)
VALUES (9300, 'FS-DEMO-CAT', '合成演示商品', 1, 901);
INSERT INTO ph_drug (id, drug_code, category_id, generic_name, specification, drug_type, is_rx, unit,
                     retail_price, member_price, cost_price, status, approve_status, saleable_online, tenant_id)
VALUES (9301, 'FS-DEMO-OTC', 9300, '演示 OTC（仅供测试）', '合成规格', 1, 0, '盒', 12.34, 12.34, 5.00, 1, 1, 1, 901),
       (9302, 'FS-DEMO-RX', 9300, '演示处方品（仅供测试）', '合成规格', 1, 1, '盒', 15.00, 15.00, 6.00, 1, 1, 1, 901);

INSERT INTO ph_inv_batch (id, store_id, warehouse_id, drug_id, batch_no, expiry_date, source_type, source_no,
                          qty_total, qty_avail, qty_frozen, qty_sold, quality_status, cost_price, tenant_id)
VALUES (9401, 9101, 9201, 9301, 'DEMO-OTC-01', DATE_ADD(CURRENT_DATE, INTERVAL 5 YEAR), 3, 'DEMO-OPENING', 50, 50, 0, 0, 0, 5.00, 901),
       (9403, 9101, 9201, 9302, 'DEMO-RX-01', DATE_ADD(CURRENT_DATE, INTERVAL 5 YEAR), 3, 'DEMO-OPENING', 30, 30, 0, 0, 0, 6.00, 901);
INSERT INTO ph_inv_location_stock (id, batch_id, location_id, drug_id, qty, qty_frozen, tenant_id)
VALUES (9402, 9401, 9202, 9301, 50, 0, 901), (9404, 9403, 9202, 9302, 30, 0, 901);
INSERT INTO ph_inv_flow (store_id, batch_id, drug_id, batch_no, flow_type, in_qty, out_qty, balance_qty,
                         biz_type, biz_no, biz_line_id, flow_time, operator, location_id, unit_cost, tenant_id)
VALUES (9101, 9401, 9301, 'DEMO-OTC-01', 70, 50, 0, 50, 7, 'DEMO-OPENING', 9402, NOW(), 0, 9202, 5.00, 901),
       (9101, 9403, 9302, 'DEMO-RX-01', 70, 30, 0, 30, 7, 'DEMO-OPENING', 9404, NOW(), 0, 9202, 6.00, 901);

INSERT INTO pay_app (id, name, status, remark, order_notify_url, refund_notify_url, transfer_notify_url, tenant_id, app_key)
VALUES (9501, '演示模拟支付', 0, '仅供本机演示',
        'http://backend:48080/app-api/member/wx-order/payment-notify',
        'http://backend:48080/app-api/member/wx-order/refund-notify',
        'http://backend:48080/app-api/member/wx-order/transfer-notify', 901, 'firstsun-demo');
INSERT INTO pay_channel (id, code, status, remark, fee_rate, app_id, config, tenant_id)
VALUES (9502, 'mock', 0, '仅供本机演示', 0, 9501,
        '{"@class":"cn.iocoder.yudao.module.pay.framework.pay.core.client.impl.NonePayClientConfig"}', 901);

COMMIT;
