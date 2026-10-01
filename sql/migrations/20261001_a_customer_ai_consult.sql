-- Incremental migration 40. No model credentials, sample models or test seeds.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS ph_customer_ai_consult (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  client_message_id VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL COMMENT 'Hash only; raw health/question text is not stored',
  status VARCHAR(16) NOT NULL,
  result_json MEDIUMTEXT NULL COMMENT 'Server catalogue response, no raw model text',
  expires_at DATETIME NOT NULL,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL DEFAULT '',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY(id),
  UNIQUE KEY uk_customer_ai_request(tenant_id, member_id, client_message_id),
  KEY idx_customer_ai_member(tenant_id, member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
