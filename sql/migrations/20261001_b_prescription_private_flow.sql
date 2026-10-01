SET NAMES utf8mb4;
-- 41: private prescription materials and one-order usage audit.
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ph_presc_record' AND COLUMN_NAME='approved_items');
SET @ddl := IF(@has_col=0, 'ALTER TABLE ph_presc_record ADD COLUMN approved_items JSON NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ph_presc_record' AND COLUMN_NAME='approved_until');
SET @ddl := IF(@has_col=0, 'ALTER TABLE ph_presc_record ADD COLUMN approved_until DATETIME NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TABLE IF NOT EXISTS ph_private_material (
id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, member_id bigint NOT NULL,store_id bigint NOT NULL,
sha256 char(64) NOT NULL,mime_type varchar(32) NOT NULL,content mediumblob NOT NULL,tenant_id bigint NOT NULL, creator varchar(64) NOT NULL DEFAULT '',create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,updater varchar(64) NOT NULL DEFAULT '',update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,deleted bit(1) NOT NULL DEFAULT b'0',
UNIQUE KEY uk_material_owner_hash(tenant_id,member_id,store_id,sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ph_prescription_use (
id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,presc_id bigint NOT NULL,wx_order_id bigint NOT NULL,
status varchar(16) NOT NULL,items_snapshot json NOT NULL,release_reason varchar(64) NULL,tenant_id bigint NOT NULL, creator varchar(64) NOT NULL DEFAULT '',create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,updater varchar(64) NOT NULL DEFAULT '',update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,deleted bit(1) NOT NULL DEFAULT b'0',
active_presc_id bigint GENERATED ALWAYS AS (CASE WHEN status IN ('HELD','PAID') THEN presc_id ELSE NULL END) STORED,
UNIQUE KEY uk_presc_active(tenant_id,active_presc_id),UNIQUE KEY uk_presc_order(tenant_id,wx_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO system_notify_template(name,code,type,nickname,content,params,status,remark)
SELECT '处方业务通知','pharmacy_prescription_event',2,'FirstSun 药店','{title}','["title","prescId"]',0,'仅通知状态，不含医疗材料或患者信息'
WHERE NOT EXISTS (SELECT 1 FROM system_notify_template WHERE code='pharmacy_prescription_event' AND deleted=b'0');

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ph_presc_record' AND COLUMN_NAME='submission_hash');
SET @ddl := IF(@has_col=0, 'ALTER TABLE ph_presc_record ADD COLUMN submission_hash CHAR(64) NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
