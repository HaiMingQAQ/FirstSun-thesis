-- Read asset bytes from deploy/demo-assets through scripts/init-local-demo.ps1.
SET NAMES utf8mb4;

CREATE TEMPORARY TABLE firstsun_demo_images (
  drug_id BIGINT PRIMARY KEY,
  drug_code VARCHAR(32) NOT NULL,
  path VARCHAR(80) NOT NULL UNIQUE,
  content MEDIUMBLOB NOT NULL
);
INSERT INTO firstsun_demo_images VALUES
  (9301, 'FS-DEMO-OTC', 'firstsun-demo/otc.png', FROM_BASE64(@demo_otc_png)),
  (9302, 'FS-DEMO-RX', 'firstsun-demo/rx.png', FROM_BASE64(@demo_rx_png));

START TRANSACTION;
SELECT id FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND deleted=b'0' FOR UPDATE;

CREATE TEMPORARY TABLE firstsun_demo_image_guard (ok TINYINT NOT NULL CHECK (ok = 1));
INSERT INTO firstsun_demo_image_guard VALUES (IF(
  DATABASE() REGEXP '^firstsun_demo_[a-z0-9_]+$'
  AND EXISTS (SELECT 1 FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND deleted=b'0')
  AND EXISTS (SELECT 1 FROM infra_file_config WHERE id=45000043 AND storage=1 AND deleted=b'0')
  AND @demo_asset_base REGEXP '^http://127[.]0[.]0[.]1:[0-9]{1,5}/admin-api/infra/file/45000043/get/$'
  AND NOT EXISTS (SELECT 1 FROM firstsun_demo_images WHERE HEX(LEFT(content,8)) <> '89504E470D0A1A0A'),
  1, 0));
INSERT INTO firstsun_demo_image_guard VALUES (IF(NOT EXISTS (
  SELECT 1 FROM infra_file_content f JOIN firstsun_demo_images a ON a.path=f.path
  WHERE f.config_id=45000043 AND f.deleted=b'0' AND COALESCE(f.creator,'')<>'firstsun-demo-seed'),
  1, 0));
INSERT INTO firstsun_demo_image_guard VALUES (IF(NOT EXISTS (
  SELECT 1 FROM infra_file f JOIN firstsun_demo_images a ON a.path=f.path
  WHERE f.config_id=45000043 AND f.deleted=b'0' AND COALESCE(f.creator,'')<>'firstsun-demo-seed'),
  1, 0));
DROP TEMPORARY TABLE firstsun_demo_image_guard;

INSERT INTO infra_file_content (config_id, path, content, creator)
SELECT 45000043, a.path, a.content, 'firstsun-demo-seed'
FROM firstsun_demo_images a
WHERE NOT EXISTS (SELECT 1 FROM infra_file_content f WHERE f.config_id=45000043 AND f.path=a.path AND f.deleted=b'0');

UPDATE infra_file_content f JOIN firstsun_demo_images a ON a.path=f.path
SET f.content=a.content
WHERE f.config_id=45000043 AND f.deleted=b'0' AND f.creator='firstsun-demo-seed' AND f.content<>a.content;

INSERT INTO infra_file (config_id, name, path, url, type, size, creator)
SELECT 45000043, SUBSTRING_INDEX(a.path,'/',-1), a.path, CONCAT(@demo_asset_base,a.path),
       'image/png', OCTET_LENGTH(a.content), 'firstsun-demo-seed'
FROM firstsun_demo_images a
WHERE NOT EXISTS (SELECT 1 FROM infra_file f WHERE f.config_id=45000043 AND f.path=a.path AND f.deleted=b'0');

UPDATE infra_file f JOIN firstsun_demo_images a ON a.path=f.path
SET f.url=CONCAT(@demo_asset_base,a.path), f.size=OCTET_LENGTH(a.content)
WHERE f.config_id=45000043 AND f.deleted=b'0' AND f.creator='firstsun-demo-seed'
  AND (CAST(f.url AS BINARY)<>CAST(CONCAT(@demo_asset_base,a.path) AS BINARY) OR f.size<>OCTET_LENGTH(a.content));

UPDATE ph_drug d JOIN firstsun_demo_images a ON a.drug_id=d.id AND a.drug_code=d.drug_code
SET d.image_url=CONCAT(@demo_asset_base,a.path)
WHERE d.tenant_id=901 AND d.deleted=b'0'
  AND (COALESCE(d.image_url,'')='' OR (
    CAST(d.image_url AS BINARY)<>CAST(CONCAT(@demo_asset_base,a.path) AS BINARY)
    AND d.image_url REGEXP '^http://127[.]0[.]0[.]1:[0-9]{1,5}/admin-api/infra/file/45000043/get/firstsun-demo/(otc|rx)[.]png$'
  ));

COMMIT;
DROP TEMPORARY TABLE firstsun_demo_images;
