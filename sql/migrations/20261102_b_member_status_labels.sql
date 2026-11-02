-- 46: Display metadata only. member_user rows and backend status rules are untouched.
SET NAMES utf8mb4;
SET @fs46_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@fs46_mode, ''), 'STRICT_ALL_TABLES');
DROP TEMPORARY TABLE IF EXISTS fs46_guard;
CREATE TEMPORARY TABLE fs46_guard (ok TINYINT NOT NULL);
START TRANSACTION;
SET @fs46_ok = (
  (SELECT COUNT(*) FROM system_dict_type WHERE type='pharmacy_member_status' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_dict_data WHERE dict_type='pharmacy_member_status' AND value='0' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_dict_data WHERE dict_type='pharmacy_member_status' AND value='1' AND deleted=b'0')=1
);
INSERT INTO fs46_guard VALUES (IF(@fs46_ok,1,NULL));
UPDATE system_dict_type SET remark='会员状态：0启用 / 1禁用',updater='readiness-46',update_time=NOW()
WHERE @fs46_ok AND type='pharmacy_member_status' AND deleted=b'0' AND NOT (remark <=> '会员状态：0启用 / 1禁用');
UPDATE system_dict_data SET label='启用',sort=1,color_type='success',remark='会员启用',updater='readiness-46',update_time=NOW()
WHERE @fs46_ok AND dict_type='pharmacy_member_status' AND value='0' AND deleted=b'0'
AND NOT (label <=> '启用' AND sort <=> 1 AND color_type <=> 'success' AND remark <=> '会员启用');
UPDATE system_dict_data SET label='禁用',sort=2,color_type='info',remark='会员禁用',updater='readiness-46',update_time=NOW()
WHERE @fs46_ok AND dict_type='pharmacy_member_status' AND value='1' AND deleted=b'0'
AND NOT (label <=> '禁用' AND sort <=> 2 AND color_type <=> 'info' AND remark <=> '会员禁用');
COMMIT;
DROP TEMPORARY TABLE fs46_guard;
SET SESSION sql_mode = @fs46_mode;
