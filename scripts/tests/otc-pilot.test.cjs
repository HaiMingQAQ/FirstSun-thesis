// Offline fixture verification. Run from any directory with Node.js; no service connections.
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { execFileSync } = require('node:child_process');

const root = resolve(__dirname, '../..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const baseline = (path) => JSON.parse(execFileSync('git', ['show', 'a6d232e1a188e872c4b5dc4a1c61c6ac4ea8627a:' + path], {
  cwd: root, encoding: 'utf8',
}));
const path = 'deploy/demo-assets/catalog/catalog.json';
const catalog = JSON.parse(read(path));
const old = baseline(path);
const corpusPath = 'deploy/knowledge/corpus.json';
const corpus = JSON.parse(read(corpusPath));
const oldCorpus = baseline(corpusPath);
const pilot = catalog.products.slice(old.products.length);

assert.equal(catalog.products.length, 59);
assert.deepEqual(catalog.categories, old.categories);
assert.deepEqual(catalog.products.slice(0, old.products.length), old.products);
assert.deepEqual(corpus.topics, oldCorpus.topics);
assert.deepEqual(corpus.drugs.slice(0, oldCorpus.drugs.length), oldCorpus.drugs);
assert.equal(corpus.drugs.length, oldCorpus.drugs.length + 14);
for (const field of ['id', 'drugCode']) {
  assert.equal(new Set(catalog.products.map((row) => row[field])).size, 59);
}
assert.equal(new Set(corpus.drugs.map((row) => row.drugCode)).size, corpus.drugs.length);
assert.deepEqual(pilot.map((p) => p.approvalNo), [
  '国药准字Z20073015', '国药准字H61020001', '国药准字H10930213', '国药准字H20043171', '国药准字H20000454',
  '国药准字Z53021107', '国药准字Z20013220', '国药准字H10910003', '国药准字H61022952', '国药准字H10930214',
  '国药准字Z20073016', '国药准字Z42021461', '国药准字Z53021615', '国药准字Z10950017',
]);
for (const [i, p] of pilot.entries()) {
  assert.equal(p.id, 163201 + i);
  assert.equal(p.drugCode, 'FS901-OTC-P' + String(i + 1).padStart(2, '0'));
  assert.equal(catalog.products.filter((r) => r.approvalNo === p.approvalNo).length, 1);
  assert.equal(p.drugType, [0, 5, 10, 13].includes(i) ? 2 : 1);
  for (const flag of ['isRx', 'isPseudoephedrine', 'isSpecial', 'isColdChain']) assert.equal(p[flag], 0);
  assert.ok(catalog.categories.some((c) => c.id === p.categoryId && c.catType === 0));
  assert.ok(p.retailPrice > 0 && p.qtyAvail === 24 && !p.image);
  assert.match(p.instructionsUrl, /^https:\/\/(www\.xian-janssen\.com\.cn|www\.yunnanbaiyao\.com\.cn|www\.crjz\.com|www\.mayinglong\.cn)\//);
  assert.match(p.description, /修订：\d{4}-\d{2}-\d{2}.*售价、开账库存均为演示值/);
  const d = corpus.drugs.find((item) => item.drugCode === p.drugCode);
  assert.equal(d.name, p.genericName);
  assert.deepEqual(d.sections.map((section) => section.key),
    ['indication', 'contraindication', 'caution', 'special', 'interaction']);
  for (const section of d.sections) {
    assert.ok(section.text.trim());
    assert.doesNotMatch(section.text, /每日|每次|[0-9０-９]+\s*(次|天|周|毫克|mg)/);
  }
}
const sql = read('deploy/sql/seed-local-demo-catalog.sql');
assert.match(sql, /JSON_LENGTH\(@demo_catalog, '\$\.products'\)=59/);
assert.match(sql, /WHERE drug_code LIKE 'FS901-OTC-P%'\)=14/);
assert.doesNotMatch(sql, /FS901-OTC-P0%/);
assert.match(sql, /p\.id\s*\+\s*1000000/);
assert.match(sql, /SET @demo_first_catalog_install = NOT EXISTS \(\s+SELECT 1 FROM ph_drug WHERE tenant_id=901 AND creator='firstsun-catalog-seed'\s+\);/);
assert.match(sql, /p\.approval_no,\s+IF\(@demo_first_catalog_install AND p\.drug_code NOT LIKE 'FS901-OTC-P%', 0, 1\),\s+1, IF\(p\.drug_code LIKE 'FS901-OTC-P%', 1, 0\), 901/);
assert.match(sql, /WHERE NOT EXISTS \(SELECT 1 FROM ph_drug d WHERE d\.drug_code=p\.drug_code COLLATE utf8mb4_unicode_ci AND d\.tenant_id=901\);/);
assert.match(sql, /UPDATE ph_drug SET status=0, saleable_online=0\s+WHERE @demo_first_catalog_install AND tenant_id=901\s+AND \(\(id=9301 AND drug_code='FS-DEMO-OTC'\) OR \(id=9302 AND drug_code='FS-DEMO-RX'\)\);/);
assert.equal((sql.match(/UPDATE ph_drug\b/gi) ?? []).length, 1);
const initializer = read('scripts/init-local-demo.ps1');
assert.match(initializer, /FROM ph_drug WHERE tenant_id=901 AND id IN \(9301,9302\) AND status IN \(0,1\) AND approve_status=1 AND deleted=b'0'/);
const statements = sql.replace(/^\s*--[^\r\n]*/gm, '').split(';');
for (const [i, statement] of statements.entries()) {
  const refs = statement.match(/\b(?:FROM|JOIN)\s+`?(firstsun_catalog_(?:products|categories|guard))\b/gi) ?? [];
  const tables = refs.map((ref) => ref.match(/firstsun_catalog_\w+/i)[0].toLowerCase());
  assert.equal(tables.length, new Set(tables).size,
    'MySQL 1137: repeated temporary table reference in statement ' + (i + 1) + ': ' + tables.join(', '));
}
for (const table of ['ph_drug', 'ph_inv_batch', 'ph_inv_location_stock', 'ph_inv_flow']) {
  assert.match(sql, new RegExp('NOT EXISTS \\(SELECT 1 FROM ' + table + '\\b'));
}
assert.doesNotMatch(sql, /(?:DELETE FROM|TRUNCATE TABLE|REPLACE INTO)\s+ph_/i);
console.log('PASS: 45 original fixtures preserved, 14 OTC identities, 70 linked sections, guarded seed structure.');
