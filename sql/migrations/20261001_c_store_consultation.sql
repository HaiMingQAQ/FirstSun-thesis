SET NAMES utf8mb4;
-- 42: text consultation; no synthetic messages, accounts or automatic role grants.
CREATE TABLE IF NOT EXISTS ph_consultation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL, staff_id BIGINT NULL, last_message_id BIGINT NOT NULL DEFAULT 0,
 member_read_id BIGINT NOT NULL DEFAULT 0, staff_read_id BIGINT NOT NULL DEFAULT 0,
 tenant_id BIGINT NOT NULL, creator VARCHAR(64) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater VARCHAR(64) NOT NULL DEFAULT '', update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0',
 UNIQUE KEY uk_consult_member_store(tenant_id,member_id,store_id,kind),
 KEY ix_consult_queue(tenant_id,store_id,staff_id,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ph_consultation_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL, sender_type VARCHAR(8) NOT NULL, sender_name VARCHAR(64) NOT NULL,
 client_request_id VARCHAR(64) NOT NULL, content VARCHAR(1000) NOT NULL, content_hash CHAR(64) NOT NULL,
 tenant_id BIGINT NOT NULL, creator VARCHAR(64) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater VARCHAR(64) NOT NULL DEFAULT '', update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0',
 UNIQUE KEY uk_consult_send(tenant_id,conversation_id,sender_type,sender_id,client_request_id),
 KEY ix_consult_messages(tenant_id,conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reuse pharmacy root. Operators explicitly grant query/reply permissions to selected employees.
SET @ph_root := (SELECT id FROM system_menu WHERE path='/pharmacy-base' AND deleted=b'0' LIMIT 1);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show)
SELECT '门店文字咨询','',2,95,@ph_root,'consultation','ep:chat-dot-round','pharmacy/consultation/index','PharmacyConsultation',0,b'1',b'1',b'1'
WHERE @ph_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM system_menu WHERE component_name='PharmacyConsultation' AND deleted=b'0');
SET @consult_menu := (SELECT id FROM system_menu WHERE component_name='PharmacyConsultation' AND deleted=b'0' LIMIT 1);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show)
SELECT '咨询查询','pharmacy:consultation:query',3,1,@consult_menu,'',0,b'1',b'1',b'1'
WHERE @consult_menu IS NOT NULL AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='pharmacy:consultation:query' AND deleted=b'0');
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show)
SELECT '咨询接待回复','pharmacy:consultation:reply',3,2,@consult_menu,'',0,b'1',b'1',b'1'
WHERE @consult_menu IS NOT NULL AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='pharmacy:consultation:reply' AND deleted=b'0');
