-- 47: Only four generic AI product families, including their manager pages/buttons.
-- This does not delete backend APIs or revoke role/package records. See readiness guide.
SET NAMES utf8mb4;
SET @fs47_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@fs47_mode, ''), 'STRICT_ALL_TABLES');
DROP TEMPORARY TABLE IF EXISTS fs47_expected;
DROP TEMPORARY TABLE IF EXISTS fs47_actions;
DROP TEMPORARY TABLE IF EXISTS fs47_ids;
DROP TEMPORARY TABLE IF EXISTS fs47_guard;
CREATE TEMPORARY TABLE fs47_expected (component_name VARCHAR(64) PRIMARY KEY, component VARCHAR(128), path VARCHAR(64), parent_path VARCHAR(64));
CREATE TEMPORARY TABLE fs47_actions (component_name VARCHAR(64), permission VARCHAR(64) PRIMARY KEY);
CREATE TEMPORARY TABLE fs47_ids (id BIGINT PRIMARY KEY);
CREATE TEMPORARY TABLE fs47_guard (ok TINYINT NOT NULL);
INSERT INTO fs47_expected VALUES
('AiImage','ai/image/index/index','image','/ai'),
('AiImageManager','ai/image/manager/index','image','console'),
('AiMusic','ai/music/index/index','music','/ai'),
('AiMusicManager','ai/music/manager/index','music','console'),
('AiWrite','ai/write/index/index','write','/ai'),
('AiWriteManager','ai/write/manager/index','write','console'),
('AiMindMap','ai/mindmap/index/index','mind-map','/ai'),
('AiMindMapManager','ai/mindmap/manager/index','mind-map','console');
INSERT INTO fs47_actions VALUES
('AiImageManager','ai:image:query'),('AiImageManager','ai:image:update'),('AiImageManager','ai:image:delete'),
('AiMusicManager','ai:music:query'),('AiMusicManager','ai:music:update'),('AiMusicManager','ai:music:delete'),
('AiWriteManager','ai:write:query'),('AiWriteManager','ai:write:delete'),
('AiMindMapManager','ai:mind-map:query'),('AiMindMapManager','ai:mind-map:delete');
START TRANSACTION;
SET @fs47_root = (SELECT MIN(id) FROM system_menu WHERE path='/ai' AND deleted=b'0');
SET @fs47_console = (SELECT MIN(id) FROM system_menu WHERE parent_id=@fs47_root AND path='console' AND deleted=b'0');
SET @fs47_ok = (
  (SELECT COUNT(*) FROM system_menu WHERE path='/ai' AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_menu WHERE parent_id=@fs47_root AND path='console' AND deleted=b'0')=1
  AND
  (SELECT COUNT(*) FROM system_menu WHERE path='/ai' AND type=1 AND parent_id=0 AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM system_menu WHERE parent_id=@fs47_root AND path='console' AND type=1 AND deleted=b'0')=1
  AND (SELECT COUNT(*) FROM fs47_expected e JOIN system_menu m ON m.component_name=e.component_name
    AND m.component IN (e.component,CONCAT(e.component,'.vue')) AND m.path=e.path AND m.type=2 AND m.permission=''
    AND m.parent_id=IF(e.parent_path='/ai',@fs47_root,@fs47_console) AND m.deleted=b'0')=8
);
INSERT INTO fs47_guard VALUES (IF(@fs47_ok,1,NULL));
SET @fs47_ok = @fs47_ok AND NOT EXISTS(SELECT 1 FROM fs47_expected e WHERE
  (SELECT COUNT(*) FROM system_menu m WHERE m.deleted=b'0' AND
    (m.component_name=e.component_name OR m.component IN (e.component,CONCAT(e.component,'.vue'))))<>1);
INSERT INTO fs47_guard VALUES (IF(@fs47_ok,1,NULL));
INSERT INTO fs47_ids SELECT m.id FROM fs47_expected e JOIN system_menu m ON m.component_name=e.component_name AND m.deleted=b'0' WHERE @fs47_ok;
SET @fs47_ok = @fs47_ok AND (
  (SELECT COUNT(*) FROM fs47_actions a JOIN system_menu p ON p.component_name=a.component_name AND p.deleted=b'0'
    JOIN system_menu m ON m.parent_id=p.id AND m.permission=a.permission AND m.type=3 AND m.deleted=b'0')=10
);
INSERT INTO fs47_guard VALUES (IF(@fs47_ok,1,NULL));
SET @fs47_ok = @fs47_ok AND NOT EXISTS(SELECT 1 FROM fs47_actions a
  WHERE (SELECT COUNT(*) FROM system_menu m WHERE m.permission=a.permission AND m.deleted=b'0')<>1);
INSERT INTO fs47_guard VALUES (IF(@fs47_ok,1,NULL));
INSERT INTO fs47_ids SELECT m.id FROM fs47_actions a JOIN system_menu m ON m.permission=a.permission AND m.deleted=b'0' WHERE @fs47_ok;
-- Unknown descendants or a colliding protected ID need manual review, never a broad subtree update.
SET @fs47_ok = @fs47_ok AND (
  (SELECT COUNT(*) FROM system_menu m JOIN fs47_ids t ON m.parent_id=t.id WHERE m.deleted=b'0')=10
);
INSERT INTO fs47_guard VALUES (IF(@fs47_ok,1,NULL));
SET @fs47_ok = @fs47_ok AND NOT EXISTS(SELECT 1 FROM fs47_ids t JOIN system_menu m ON m.id=t.id
  WHERE m.id IN (22300,22349) OR m.permission='pharmacy:ai:chat');
INSERT INTO fs47_guard VALUES (IF(@fs47_ok,1,NULL));
UPDATE system_menu m JOIN fs47_ids t ON t.id=m.id
SET m.status=1,m.visible=b'0',m.updater='readiness-47',m.update_time=NOW()
WHERE @fs47_ok AND m.deleted=b'0' AND (m.status<>1 OR m.visible<>b'0');
COMMIT;
DROP TEMPORARY TABLE fs47_guard;
DROP TEMPORARY TABLE fs47_ids;
DROP TEMPORARY TABLE fs47_actions;
DROP TEMPORARY TABLE fs47_expected;
SET SESSION sql_mode = @fs47_mode;
