-- 43: opt-in member AI topics. No credentials, demo data or HTTP test seeds.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS ph_customer_ai_topic (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL, member_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 title VARCHAR(40) NOT NULL DEFAULT '新话题',
 creator VARCHAR(64) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater VARCHAR(64) NOT NULL DEFAULT '', update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0',
 KEY ix_customer_ai_topics(tenant_id,member_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ph_customer_ai_consult' AND column_name='topic_id'), 'SELECT 1', 'ALTER TABLE ph_customer_ai_consult ADD COLUMN topic_id BIGINT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ph_customer_ai_consult' AND column_name='question'), 'SELECT 1', 'ALTER TABLE ph_customer_ai_consult ADD COLUMN question VARCHAR(500) NULL COMMENT ''Member question saved only for explicitly created history topics''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ph_customer_ai_consult' AND index_name='ix_customer_ai_topic_turns'), 'SELECT 1', 'ALTER TABLE ph_customer_ai_consult ADD INDEX ix_customer_ai_topic_turns(tenant_id,member_id,topic_id,id)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
