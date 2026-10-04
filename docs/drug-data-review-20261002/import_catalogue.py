"""Local, incremental catalogue-only batch. Default action is read-only check."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'catalogue30-work.json'
DB = 'firstsun_thesis_local'
TENANT = 163
STORE = 407
BATCH = 'THESIS-DRUG-20261002-A'
CONTEXT = 'desktop-linux'
CONTAINER = 'firstsun-thesis-local-mysql-1'
CAT_CODE = 'TH26A-VITMED'
BACKUPS = ROOT / 'batch-backups'
MANIFEST = BACKUPS / 'applied.json'


def run(args, sql=None):
    result = subprocess.run(args, input=sql, encoding='utf-8', capture_output=True)
    if result.returncode:
        # Never include command/environment or raw container output in errors.
        raise RuntimeError('Local command failed; stop and inspect batch state before retrying. ' +
                           ('SQL guard or constraint failed.' if sql else 'Check local Docker availability.'))
    return result.stdout.strip()


def local_guard():
    endpoint = run(['docker', 'context', 'inspect', CONTEXT, '--format', '{{.Endpoints.docker.Host}}'])
    if endpoint != 'npipe:////./pipe/dockerDesktopLinuxEngine':
        raise RuntimeError('Docker endpoint is not the expected local named pipe.')
    ports = run(['docker', '--context', CONTEXT, 'port', CONTAINER, '3306/tcp'])
    if ports != '127.0.0.1:23327':
        raise RuntimeError('Local MySQL port mapping mismatch.')


def mysql(sql):
    return run(['docker', '--context', CONTEXT, 'exec', '-i', CONTAINER, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw '
                '--skip-column-names --default-character-set=utf8mb4 ' + DB], sql)


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, int):
        return str(value)
    return "CONVERT(0x" + str(value).encode('utf-8').hex() + " USING utf8mb4) COLLATE utf8mb4_unicode_ci"


def load_catalogue():
    data = json.loads(DATA.read_text(encoding='utf-8'))
    assert (data['expected_database'], data['tenant_id'], data['store_id'], data['batch_id']) == (DB, TENANT, STORE, BATCH)
    rows = data['samples']
    assert len(rows) == 30 and sum(r['is_rx'] for r in rows) == 10
    assert len({r['drug_code'].casefold() for r in rows}) == 30
    assert len({r['approval_no'].casefold() for r in rows}) == 30
    lengths = {'drug_code': 32, 'generic_name': 64, 'trade_name': 64, 'specification': 64,
               'dosage_form': 16, 'manufacturer': 128, 'approval_no': 64, 'unit': 8, 'instructions_url': 512}
    for r in rows:
        assert r['import_allowed'] and r['tenant_id'] == TENANT
        assert r['price'] is None and r['inventory'] is None and r['image_path'] is None
        assert r['barcode'] == '来源未提供'
        assert r['drug_type'] in (0, 1, 2) and r['is_rx'] == int(r['drug_type'] == 0)
        assert re.fullmatch(r'国药准字[HZ]\d{8}', r['approval_no'])
        assert r['product_url'].startswith('https://') and r['instructions_url'].startswith('https://')
        assert r['query_date'] == '2026-10-02' and r['classification_basis'] and r['verification']
        assert set(r['medical_review']) == {'indications', 'dosage', 'contraindications', 'adverse_reactions', 'precautions', 'storage'}
        assert all(r['medical_review'].values())
        for key, limit in lengths.items():
            assert 0 < len(r[key]) <= limit, (r['drug_code'], key)
    return rows


def snapshot_sql(table, where):
    columns = mysql('SHOW COLUMNS FROM `' + table + '`;').splitlines()
    pairs = []
    for line in columns:
        name, kind = line.split('\t')[:2]
        value = '`' + name + '`' + ('+0' if kind.startswith('bit') else '')
        pairs.extend([literal(name), value])
    return 'SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(' + ','.join(pairs) + ")),JSON_ARRAY()) FROM `" + table + '` WHERE ' + where + ';'


def snapshots():
    queries = [snapshot_sql(t, 'tenant_id=163') for t in ('ph_drug', 'ph_category', 'ph_drug_barcode')]
    output = mysql('SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT;\n' +
                   '\n'.join(queries) + '\nCOMMIT;').splitlines()
    return {t: sorted(json.loads(s), key=lambda x: x['id'])
            for t, s in zip(('ph_drug', 'ph_category', 'ph_drug_barcode'), output, strict=True)}


def target_guard():
    result = mysql("SELECT JSON_OBJECT('db',DATABASE(),'charset',@@character_set_database,'store',"
                   "(SELECT COUNT(*) FROM ph_store WHERE id=407 AND tenant_id=163 AND deleted=0 AND status=1));")
    target = json.loads(result)
    assert target == {'db': DB, 'charset': 'utf8mb4', 'store': 1}, 'Database/tenant/store mismatch'
    bad = mysql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                "AND TABLE_NAME IN ('ph_drug','ph_category','ph_drug_barcode') AND COLLATION_NAME IS NOT NULL "
                "AND COLLATION_NAME NOT LIKE 'utf8mb4%';")
    assert bad == '0', 'Catalogue encoding mismatch'


def description(r):
    labels = {'indications': '适应症/功能主治', 'dosage': '用法用量', 'contraindications': '禁忌',
              'adverse_reactions': '不良反应', 'precautions': '注意事项', 'storage': '储存条件'}
    lines = ['毕设目录资料核验摘要；不能替代完整说明书或医师/药师指导。',
             '处方属性：' + r['prescription_class'],
             '核验依据：' + r['classification_basis'],
             '上市许可持有人：' + r['marketing_authorization_holder'],
             '条码：来源未提供（未建条码记录）']
    lines += [labels[k] + '：' + r['medical_review'][k] for k in labels]
    lines += ['产品来源：' + r['product_url'], '对应说明书：' + r['instructions_url'],
              '说明书修订日期：' + r['instructions_revision_date'], '查询日期：' + r['query_date'],
              '核验记录：' + r['verification'], '资料使用：' + r['source_use'],
              '演示经营数据：未定价、未入库、图片为空；审核未通过、未启用。',
              '系统必填默认：储存枚举及医保字段不作为厂家温度或医保结论，业务启用前须复核。']
    return '\n'.join(lines)


def expected(r):
    category = r['proposed_category']['id']
    return dict(drug_code=r['drug_code'], generic_name=r['generic_name'],
                trade_name=None if r['trade_name']=='来源未提供' else r['trade_name'],
                specification=r['specification'], dosage_form=r['dosage_form'], manufacturer=r['manufacturer'],
                approval_no=r['approval_no'], drug_type=r['drug_type'], is_rx=r['is_rx'], unit=r['unit'],
                storage_cond=r.get('storage_cond', 0), retail_price=0, member_price=None, cost_price=None,
                min_sale_price=None, status=0, saleable_online=0, approve_status=0, audit_by=None, audit_at=None,
                audit_opinion=None, image_url=None, images=None, tenant_id=TENANT, deleted=0,
                description=description(r), instructions_url=r['instructions_url'],
                remark=BATCH + ';资料批次;UNPRICED=未定价;NO_STOCK=未入库;NO_IMAGE=无图片;'
                       '目录待审核且停用。强度缺项保留来源未提供；储存/医保默认枚举待业务复核。',
                creator=BATCH, updater=BATCH, category_id=category)


def check(rows, snap):
    codes = ','.join(literal(r['drug_code']) for r in rows)
    approvals = ','.join(literal(r['approval_no']) for r in rows)
    # No tenant/deleted filter: global constraints and logically deleted collisions both count.
    collisions = json.loads(mysql('SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(\'id\',id,\'drug_code\',drug_code,'
                                  "'approval_no',approval_no,'tenant_id',tenant_id,'deleted',deleted+0,'creator',creator)),JSON_ARRAY()) "
                                  'FROM ph_drug WHERE drug_code IN (' + codes + ') OR approval_no IN (' + approvals + ');'))
    existing = {r['drug_code']: r for r in snap['ph_drug']}
    for c in collisions:
        assert c['drug_code'] in {r['drug_code'] for r in rows} and c['tenant_id'] == TENANT and c['deleted'] == 0 and c['creator'] == BATCH, 'Global/soft-deleted drug collision'
    cats = json.loads(mysql("SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id',id,'tenant_id',tenant_id,'deleted',deleted+0,'cat_type',cat_type,'parent_id',parent_id,'status',status,'creator',creator)),JSON_ARRAY()) FROM ph_category WHERE cat_code=" + literal(CAT_CODE) + ';'))
    assert len(cats) <= 1
    if cats:
        assert all(cats[0][k] == v for k, v in dict(tenant_id=163,deleted=0,cat_type=0,parent_id=163001,status=1,creator=BATCH).items()), 'Global/soft-deleted category collision'
    by_cat = {c['id']: c for c in snap['ph_category']}
    for r in rows:
        cid = r['proposed_category']['id'] or 163001
        assert cid in by_cat and by_cat[cid]['deleted'] == 0 and by_cat[cid]['status'] == 1 and by_cat[cid]['cat_type'] == 0
        if r['drug_code'] in existing:
            e = expected(r)
            e['category_id'] = r['proposed_category']['id'] or cats[0]['id']
            assert all(existing[r['drug_code']][k] == v for k,v in e.items()), 'Existing batch row differs; refusing overwrite'
    return [r for r in rows if r['drug_code'] not in existing], bool(cats)


def write_json(path, data):
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def apply(rows, snap):
    missing, cat_exists = check(rows, snap)
    if not missing:
        assert MANIFEST.exists(), 'Missing original batch backup/manifest'
        verify(rows, snap)
        print('Idempotent: 0 added; 30 existing batch rows match exactly.')
        return
    assert len(missing) == 30 and not MANIFEST.exists(), 'Partial batch detected; do not overwrite or repair automatically'
    backup_path = BACKUPS / ('before-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S') + '.json')
    write_json(backup_path, {'batch':BATCH,'db':DB,'tenant':TENANT,'store':STORE,'tables':snap})
    guards = ['CREATE TEMPORARY TABLE thesis_batch_guard (ok INT NOT NULL CHECK (ok=1));',
              'START TRANSACTION;',
              'INSERT INTO thesis_batch_guard VALUES(IF(DATABASE()=' + literal(DB) + ',1,0));',
              'INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_store WHERE id=407 AND tenant_id=163 AND deleted=0 AND status=1)=1,1,0));']
    codes = ','.join(literal(r['drug_code']) for r in rows)
    approvals = ','.join(literal(r['approval_no']) for r in rows)
    guards += ['SELECT id FROM ph_drug WHERE drug_code IN (' + codes + ') OR approval_no IN (' + approvals + ') FOR UPDATE;',
               'INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_drug WHERE drug_code IN (' + codes + ') OR approval_no IN (' + approvals + '))=0,1,0));']
    for cid in sorted({r['proposed_category']['id'] or 163001 for r in rows}):
        guards += [f'INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_category WHERE id={cid} AND tenant_id=163 AND deleted=0 AND status=1 AND cat_type=0)=1,1,0));']
    if not cat_exists:
        guards += ['INSERT INTO ph_category (cat_code,cat_name,parent_id,cat_type,sort,status,tenant_id,creator,updater) VALUES (' +
                   ','.join(map(literal,[CAT_CODE,'维生素类药品',163001,0,35,1,TENANT,BATCH,BATCH])) + ');']
    guards += ['SET @vit_category=(SELECT id FROM ph_category WHERE cat_code=' + literal(CAT_CODE) + ' AND tenant_id=163 AND deleted=0 AND cat_type=0);']
    for r in rows:
        e = expected(r)
        values = [('@vit_category' if k=='category_id' and v is None else literal(v)) for k,v in e.items()]
        guards += ['INSERT INTO ph_drug (' + ','.join(e) + ') VALUES (' + ','.join(values) + ');']
    guards += ['INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_drug WHERE creator=' + literal(BATCH) + ' AND tenant_id=163 AND deleted=0)=30,1,0));', 'COMMIT;']
    mysql('\n'.join(guards))
    after = snapshots()
    added = [r for r in after['ph_drug'] if r['creator']==BATCH]
    added_cats = [c for c in after['ph_category'] if c['creator']==BATCH]
    write_json(MANIFEST, {'batch':BATCH, 'catalogue_sha256':hashlib.sha256(DATA.read_bytes()).hexdigest(),
                         'backup':backup_path.name,'added_rows':added,'added_categories':added_cats})
    verify(rows, after)
    print('Added 30 drug dossiers and 1 medicine category; backup: ' + backup_path.name)


def verify(rows, snap):
    missing,_ = check(rows,snap)
    assert not missing
    m=json.loads(MANIFEST.read_text(encoding='utf-8'))
    assert m['catalogue_sha256']==hashlib.sha256(DATA.read_bytes()).hexdigest(), 'Batch input changed after import'
    before=json.loads((BACKUPS/m['backup']).read_text(encoding='utf-8'))['tables']
    for table in before:
        current={r['id']:r for r in snap[table]}
        assert all(current.get(r['id'])==r for r in before[table]), 'Existing records changed'
    assert snap['ph_drug_barcode']==before['ph_drug_barcode'], 'Barcode table changed'
    ids=','.join(str(r['id']) for r in m['added_rows'])
    for table in ['ph_inv_batch','ph_inv_flow','ph_inv_location_stock']:
        assert mysql('SELECT COUNT(*) FROM '+table+' WHERE drug_id IN ('+ids+');') == '0', 'Batch has inventory records'
    print('Verified: 30 added (20 OTC/10 Rx), original directory rows unchanged; images/audits empty; disabled, unpriced, no inventory writes.')


def rollback(snap):
    m=json.loads(MANIFEST.read_text(encoding='utf-8'))
    current={r['id']:r for r in snap['ph_drug']}
    assert all(current.get(r['id'])==r for r in m['added_rows']), 'Batch has later changes; reconcile manually before rollback'
    ids=','.join(str(r['id']) for r in m['added_rows'])
    # Soft-delete only this batch. Never remove any business rows.
    sql=['CREATE TEMPORARY TABLE thesis_batch_guard (ok INT NOT NULL CHECK (ok=1));','START TRANSACTION;',
         'SELECT id FROM ph_drug WHERE id IN ('+ids+') FOR UPDATE;',
         'INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_drug WHERE id IN ('+ids+') AND creator='+literal(BATCH)+' AND tenant_id=163 AND status=0 AND saleable_online=0 AND approve_status=0 AND deleted=0)=30,1,0));',
         'INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_drug_barcode WHERE drug_id IN ('+ids+'))=0,1,0));']
    for table in ['ph_inv_batch','ph_inv_flow','ph_inv_location_stock']:
        sql += ['INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM '+table+' WHERE drug_id IN ('+ids+'))=0,1,0));']
    sql += ['UPDATE ph_drug SET deleted=1,updater='+literal(BATCH+'-ROLLBACK')+' WHERE id IN ('+ids+') AND tenant_id=163 AND creator='+literal(BATCH)+';']
    for c in m['added_categories']:
        assert next(x for x in snap['ph_category'] if x['id']==c['id'])==c
        sql += [f'INSERT INTO thesis_batch_guard VALUES(IF((SELECT COUNT(*) FROM ph_drug WHERE category_id={c["id"]} AND deleted=0)=0,1,0));',
                f'UPDATE ph_category SET deleted=1,status=0 WHERE id={c["id"]} AND creator='+literal(BATCH)+' AND tenant_id=163;']
    sql += ['COMMIT;']
    mysql('\n'.join(sql))
    print('Soft-deleted this batch only. Unique codes remain reserved; do not rerun apply after rollback.')


def main():
    if not __debug__:
        raise RuntimeError('Run without -O: validation assertions must remain enabled.')
    parser=argparse.ArgumentParser()
    parser.add_argument('action',nargs='?',choices=['check','apply','verify','rollback'],default='check')
    args=parser.parse_args()
    rows=load_catalogue()
    local_guard();target_guard();snap=snapshots()
    if args.action=='check':
        missing,_=check(rows,snap)
        print(f'Preflight passed: target verified; {len(missing)} new rows; global and deleted collisions checked; no barcodes will be inserted.')
    elif args.action=='apply': apply(rows,snap)
    elif args.action=='verify': verify(rows,snap)
    else: rollback(snap)


if __name__=='__main__':
    main()
