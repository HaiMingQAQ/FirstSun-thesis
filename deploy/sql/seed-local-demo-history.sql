-- Recreate the observed history with demo identities, synthetic content and new record numbers.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;
SELECT id FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND deleted=b'0' FOR UPDATE;

CREATE TEMPORARY TABLE firstsun_history_guard (ok TINYINT NOT NULL CHECK (ok=1));
INSERT INTO firstsun_history_guard VALUES (IF(
  DATABASE() REGEXP '^firstsun_demo_[a-z0-9_]+$'
  AND EXISTS (SELECT 1 FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND deleted=b'0')
  AND HEX(LEFT(FROM_BASE64(@demo_prescription_png),8))='89504E470D0A1A0A', 1, 0));
DROP TEMPORARY TABLE firstsun_history_guard;

SET @seed_history = NOT EXISTS (SELECT 1 FROM ph_wx_order WHERE tenant_id=901 AND creator='firstsun-demo-history');
SET @history_date = DATE_SUB(NOW(), INTERVAL 7 DAY);

-- Copy the completed and cancelled status counts without importing pending payments or reservations.
INSERT INTO ph_wx_order (id, order_no, member_id, store_id, order_type, goods_amount, payable_amount,
                         pay_status, paid_at, status, cancel_reason, finish_at, expire_at,
                         verify_by, verify_at, remark, create_time, tenant_id, creator)
WITH RECURSIVE sequence_numbers (n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM sequence_numbers WHERE n<27
)
SELECT 1900000+n, CONCAT('WX-DEMO-HISTORY-',LPAD(n,2,'0')), 90011, 9101, 0,
       d.retail_price, d.retail_price, IF(n<=22,1,0),
       IF(n<=22,DATE_ADD(@history_date,INTERVAL n MINUTE),NULL),
       IF(n<=22,4,-1), IF(n<=22,NULL,'演示取消记录'),
       IF(n<=22,DATE_ADD(@history_date,INTERVAL (n+30) MINUTE),NULL),
       DATE_ADD(@history_date,INTERVAL (n+30) MINUTE),
       IF(n<=22,95231,NULL), IF(n<=22,DATE_ADD(@history_date,INTERVAL (n+30) MINUTE),NULL),
       '去标识演示记录', @history_date, 901, 'firstsun-demo-history'
FROM sequence_numbers
JOIN ph_drug d ON d.tenant_id=901 AND d.drug_code=ELT(MOD(n,3)+1,'FS901-014','FS901-017','FS901-020')
WHERE @seed_history;

INSERT INTO ph_wx_order_line (wx_order_id, drug_id, qty, price, line_amount, picked_qty,
                              drug_name, specification, unit, create_time, tenant_id, creator)
SELECT o.id, d.id, 1, o.goods_amount, o.goods_amount, IF(o.status=4,1,0),
       d.generic_name, d.specification, d.unit, o.create_time, 901, 'firstsun-demo-history'
FROM ph_wx_order o
JOIN ph_drug d ON d.tenant_id=901 AND d.drug_code=ELT(MOD(o.id-1900000,3)+1,'FS901-014','FS901-017','FS901-020')
WHERE @seed_history AND o.tenant_id=901 AND o.creator='firstsun-demo-history';

-- Two members are needed because there can only be one conversation of each kind per member and store.
INSERT INTO member_user (id, register_ip, nickname, status, level_id, tenant_id, creator)
SELECT 90012, '127.0.0.1', '合成演示顾客二', 0, 9001, 901, 'firstsun-demo-history'
WHERE @seed_history;

INSERT INTO ph_consultation (id, member_id, store_id, kind, staff_id, last_message_id,
                             member_read_id, staff_read_id, tenant_id, creator, create_time, update_time)
SELECT c.id, c.member_id, 9101, c.kind, c.staff_id, c.last_id, c.last_id, c.staff_read_id,
       901, 'firstsun-demo-history', @history_date, @history_date
FROM (
  SELECT 1900101 id, 90011 member_id, 'PHARMACIST' kind, 95112 staff_id, 1900205 last_id, 1900205 staff_read_id
  UNION ALL SELECT 1900102, 90012, 'PHARMACIST', 95112, 1900208, 0
  UNION ALL SELECT 1900103, 90011, 'SERVICE', 95212, 1900211, 1900211
) c WHERE @seed_history;

INSERT INTO ph_consultation_message (id, conversation_id, sender_id, sender_type, sender_name,
                                     client_request_id, content, content_hash, tenant_id, creator, create_time)
SELECT m.id, c.id, IF(m.is_staff,c.staff_id,c.member_id), IF(m.is_staff,'STAFF','MEMBER'),
       IF(m.is_staff,IF(c.kind='PHARMACIST','演示药师','演示客服'),'演示顾客'),
       CONCAT('demo-history-',m.id), m.content, SHA2(m.content,256), 901, 'firstsun-demo-history', @history_date
FROM (
  SELECT 1900201 id, 1900101 consultation_id, 0 is_staff, '请问在哪里查看商品说明？' content
  UNION ALL SELECT 1900202, 1900101, 1, '可以在商品详情页打开说明书入口。'
  UNION ALL SELECT 1900203, 1900101, 0, '处方申请在哪里提交？'
  UNION ALL SELECT 1900204, 1900101, 0, '提交后在哪里看状态？'
  UNION ALL SELECT 1900205, 1900101, 1, '在处方购药入口提交，可在我的处方里查看状态。'
  UNION ALL SELECT 1900206, 1900102, 0, '请问处方审核需要什么材料？'
  UNION ALL SELECT 1900207, 1900102, 0, '上传入口在哪里？'
  UNION ALL SELECT 1900208, 1900102, 1, '可以从处方购药页面查看资料要求。'
  UNION ALL SELECT 1900209, 1900103, 0, '请问门店自提在哪里看？'
  UNION ALL SELECT 1900210, 1900103, 0, '订单状态在哪里查询？'
  UNION ALL SELECT 1900211, 1900103, 1, '请在我的订单中查看履约方式和状态。'
) m JOIN ph_consultation c ON c.id=m.consultation_id AND c.tenant_id=901
WHERE @seed_history;

INSERT INTO ph_private_material (id, member_id, store_id, sha256, mime_type, content, tenant_id, creator, create_time)
SELECT 1900301, 90011, 9101, SHA2(FROM_BASE64(@demo_prescription_png),256), 'image/png',
       FROM_BASE64(@demo_prescription_png), 901, 'firstsun-demo-history', @history_date
WHERE @seed_history;

INSERT INTO ph_presc_record (presc_no, store_id, source, patient_name, hospital, doctor_name,
                            review_status, pharmacist_id, review_at, review_opinion, wx_member_id,
                            images, prescribed_items, approved_items, approved_until, create_time, tenant_id, creator)
WITH RECURSIVE prescription_numbers (n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM prescription_numbers WHERE n<8
)
SELECT CONCAT('APP-DEMO-HISTORY-',LPAD(n,2,'0')), 9101, 0,
       '合成演示患者', '演示机构', '演示医师',
       CASE WHEN n<=5 THEN 1 WHEN n<=7 THEN 2 ELSE 0 END,
       IF(n<8,95122,NULL), IF(n<8,@history_date,NULL),
       CASE WHEN n<=5 THEN '演示审核通过' WHEN n<=7 THEN '演示资料不完整' ELSE NULL END,
       90011, JSON_ARRAY('private:1900301'),
       JSON_ARRAY(JSON_OBJECT('drugId',d.id,'drugName',d.generic_name,'qty',1,'specification',d.specification)),
       IF(n<=5,JSON_ARRAY(JSON_OBJECT('drugId',d.id,'drugName',d.generic_name,'qty',1,'specification',d.specification)),NULL),
       IF(n<=5,@history_date,NULL), @history_date, 901, 'firstsun-demo-history'
FROM prescription_numbers JOIN ph_drug d ON d.tenant_id=901 AND d.drug_code='AI25-D30'
WHERE @seed_history;

COMMIT;
