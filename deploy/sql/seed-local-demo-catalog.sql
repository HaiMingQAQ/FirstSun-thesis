-- Import the public FirstSun catalog and its opening stock into the dedicated demo tenant.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;
SELECT id FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND deleted=b'0' FOR UPDATE;

CREATE TEMPORARY TABLE firstsun_catalog_guard (ok TINYINT NOT NULL CHECK (ok = 1));
INSERT INTO firstsun_catalog_guard VALUES (IF(
  DATABASE() REGEXP '^firstsun_demo_[a-z0-9_]+$'
  AND EXISTS (SELECT 1 FROM system_tenant WHERE id=901 AND creator='firstsun-demo-seed' AND deleted=b'0')
  AND JSON_LENGTH(@demo_catalog, '$.products')=45, 1, 0));
DROP TEMPORARY TABLE firstsun_catalog_guard;

CREATE TEMPORARY TABLE firstsun_catalog_categories AS
SELECT c.* FROM JSON_TABLE(@demo_catalog, '$.categories[*]' COLUMNS (
  id BIGINT PATH '$.id', cat_code VARCHAR(16) PATH '$.catCode', cat_name VARCHAR(32) PATH '$.catName',
  parent_id BIGINT PATH '$.parentId', cat_type TINYINT PATH '$.catType', sort_order INT PATH '$.sort'
)) c;

CREATE TEMPORARY TABLE firstsun_catalog_products AS
SELECT p.* FROM JSON_TABLE(@demo_catalog, '$.products[*]' COLUMNS (
  id BIGINT PATH '$.id', drug_code VARCHAR(32) PATH '$.drugCode', category_id BIGINT PATH '$.categoryId',
  generic_name VARCHAR(64) PATH '$.genericName', trade_name VARCHAR(64) PATH '$.tradeName',
  spell_code VARCHAR(32) PATH '$.spellCode', specification VARCHAR(64) PATH '$.specification',
  dosage_form VARCHAR(16) PATH '$.dosageForm', manufacturer VARCHAR(128) PATH '$.manufacturer',
  unit VARCHAR(8) PATH '$.unit', drug_type TINYINT PATH '$.drugType', is_rx TINYINT PATH '$.isRx',
  is_special TINYINT PATH '$.isSpecial', is_pseudoephedrine TINYINT PATH '$.isPseudoephedrine',
  is_cold_chain TINYINT PATH '$.isColdChain', storage_cond TINYINT PATH '$.storageCond',
  need_expiry TINYINT PATH '$.needExpiry', retail_price DECIMAL(18,2) PATH '$.retailPrice',
  member_price DECIMAL(18,2) PATH '$.memberPrice', instructions_url VARCHAR(512) PATH '$.instructionsUrl',
  description TEXT PATH '$.description', approval_no VARCHAR(64) PATH '$.approvalNo',
  qty_avail INT PATH '$.qtyAvail'
)) p;

SET @demo_first_catalog_install = NOT EXISTS (
  SELECT 1 FROM ph_drug WHERE tenant_id=901 AND creator='firstsun-catalog-seed'
);

INSERT INTO ph_category (id, cat_code, cat_name, parent_id, cat_type, sort, status, tenant_id, creator)
SELECT c.id + 1000000, c.cat_code, c.cat_name, 0, c.cat_type, c.sort_order, 1, 901, 'firstsun-catalog-seed'
FROM firstsun_catalog_categories c
WHERE NOT EXISTS (SELECT 1 FROM ph_category x WHERE x.cat_code=c.cat_code COLLATE utf8mb4_unicode_ci AND x.tenant_id=901);

INSERT INTO ph_drug (id, drug_code, category_id, generic_name, trade_name, spell_code, specification,
                     dosage_form, manufacturer, unit, drug_type, is_rx, is_special, is_pseudoephedrine,
                     is_cold_chain, storage_cond, need_expiry, retail_price, member_price, instructions_url,
                     description, approval_no, status, approve_status, saleable_online, tenant_id, creator)
SELECT p.id + 1000000, p.drug_code, c.id, p.generic_name, p.trade_name, p.spell_code, p.specification,
       p.dosage_form, p.manufacturer, p.unit, p.drug_type, p.is_rx, p.is_special, p.is_pseudoephedrine,
       p.is_cold_chain, p.storage_cond, p.need_expiry, p.retail_price, p.member_price, p.instructions_url,
       p.description, p.approval_no, 1, 1, 1, 901, 'firstsun-catalog-seed'
FROM firstsun_catalog_products p JOIN firstsun_catalog_categories pc ON pc.id=p.category_id
JOIN ph_category c ON c.cat_code=pc.cat_code COLLATE utf8mb4_unicode_ci AND c.tenant_id=901 AND c.deleted=b'0'
WHERE NOT EXISTS (SELECT 1 FROM ph_drug d WHERE d.drug_code=p.drug_code COLLATE utf8mb4_unicode_ci AND d.tenant_id=901);

INSERT INTO ph_inv_batch (store_id, warehouse_id, drug_id, batch_no, expiry_date, source_type, source_no,
                          qty_total, qty_avail, tenant_id, creator)
SELECT 9101, 9201, d.id, 'DEMO-CATALOG-OPEN', DATE_ADD(CURRENT_DATE, INTERVAL 5 YEAR), 3, 'DEMO-CATALOG',
       p.qty_avail, p.qty_avail, 901, 'firstsun-catalog-seed'
FROM firstsun_catalog_products p JOIN ph_drug d ON d.drug_code=p.drug_code COLLATE utf8mb4_unicode_ci AND d.tenant_id=901
WHERE p.qty_avail>0 AND d.creator='firstsun-catalog-seed' AND d.deleted=b'0'
  AND NOT EXISTS (SELECT 1 FROM ph_inv_batch b WHERE b.tenant_id=901 AND b.warehouse_id=9201
                   AND b.drug_id=d.id AND b.batch_no='DEMO-CATALOG-OPEN');

INSERT INTO ph_inv_location_stock (batch_id, location_id, drug_id, qty, tenant_id)
SELECT b.id, 9202, b.drug_id, b.qty_avail, 901 FROM ph_inv_batch b
WHERE b.tenant_id=901 AND b.warehouse_id=9201 AND b.creator='firstsun-catalog-seed' AND b.deleted=b'0'
  AND NOT EXISTS (SELECT 1 FROM ph_inv_location_stock s WHERE s.batch_id=b.id AND s.location_id=9202);

INSERT INTO ph_inv_flow (store_id, batch_id, drug_id, batch_no, flow_type, in_qty, out_qty, balance_qty,
                         biz_type, biz_no, biz_line_id, flow_time, operator, location_id, unit_cost, tenant_id)
SELECT 9101, b.id, b.drug_id, b.batch_no, 70, s.qty, 0, s.qty, 7, 'DEMO-CATALOG', s.id, NOW(), 0, 9202, 0, 901
FROM ph_inv_batch b JOIN ph_inv_location_stock s ON s.batch_id=b.id AND s.location_id=9202
WHERE b.tenant_id=901 AND b.warehouse_id=9201 AND b.creator='firstsun-catalog-seed' AND b.deleted=b'0'
  AND NOT EXISTS (SELECT 1 FROM ph_inv_flow f WHERE f.batch_id=b.id AND f.biz_no='DEMO-CATALOG' AND f.tenant_id=901);

-- Keep the original two workflow fixtures available in the back office and leave 45 storefront products.
UPDATE ph_drug SET saleable_online=0
WHERE @demo_first_catalog_install AND tenant_id=901
  AND ((id=9301 AND drug_code='FS-DEMO-OTC') OR (id=9302 AND drug_code='FS-DEMO-RX'));
UPDATE ph_category SET status=0
WHERE @demo_first_catalog_install AND tenant_id=901 AND id=9300 AND cat_code='FS-DEMO-CAT';

COMMIT;
DROP TEMPORARY TABLE firstsun_catalog_products;
DROP TEMPORARY TABLE firstsun_catalog_categories;
