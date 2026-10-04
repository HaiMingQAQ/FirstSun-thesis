"""Set synthetic graduation-demo retail prices only on this batch's zero-price rows."""
import json
from decimal import Decimal

import import_catalogue as batch


# CNY per recorded sales unit. Synthetic values, not researched market prices.
PRICES = [29.90, 24.90, 19.90, 16.90, 12.90, 15.90, 18.90, 26.90, 22.90, 12.90,
          24.90, 18.90, 9.90, 16.90, 32.90, 14.90, 28.90, 12.90, 26.90, 19.90,
          29.90, 36.90, 5.90, 14.90, 2.50, 24.90, 22.90, 49.90, 24.90, 19.90]
MARKER = 'THESIS-DEMO-PRICE-20261003'


def main():
    if not __debug__:
        raise RuntimeError('Run without -O: validation must remain enabled.')
    batch.local_guard()
    batch.target_guard()
    manifest = json.loads(batch.MANIFEST.read_text(encoding='utf-8'))
    expected = {r['id']: r for r in manifest['added_rows']}
    assert len(expected) == len(PRICES) == 30
    before = batch.snapshots()
    current = {r['id']: r for r in before['ph_drug']}
    rows = [current[i] for i in sorted(expected)]
    for r in rows:
        assert r['tenant_id'] == 163 and r['deleted'] == 0 and r['creator'] == batch.BATCH
        assert r['drug_code'] == expected[r['id']]['drug_code']
    missing = [r for r in rows if r['retail_price'] == 0]
    if not missing:
        print('No zero-price batch rows: 0 updated; existing prices preserved.')
        return
    backup = batch.BACKUPS / 'before-demo-prices-20261003.json'
    assert not backup.exists(), 'Existing backup: inspect partial execution before retrying'
    plan = []
    for r in missing:
        price = Decimal(str(PRICES[int(r['drug_code'][-3:]) - 1])).quantize(Decimal('0.01'))
        assert price > 0
        assert all(r[k] is None or Decimal(str(r[k])) <= price for k in ('member_price', 'min_sale_price'))
        remark = r['remark'].replace('UNPRICED=未定价', 'DEMO_PRICE=毕设演示价（非真实市场价格）')
        remark = remark.replace('目录待审核且停用。', '价格仅供毕设演示；审核与启用状态以业务字段为准。')
        assert 'DEMO_PRICE=' in remark and len(remark) <= 500
        lines = r['description'].splitlines()
        assert sum(line.startswith('演示经营数据：') for line in lines) == 1
        description = '\n'.join('演示经营数据：零售价为毕设合成演示价（非真实市场价格）；未入库、图片为空；审核状态以业务字段为准。'
                                if line.startswith('演示经营数据：') else line for line in lines)
        plan.append(dict(id=r['id'], code=r['drug_code'], name=r['generic_name'], unit=r['unit'],
                         price=str(price), remark=remark, description=description))
    batch.write_json(backup, {'db': batch.DB, 'tenant': 163, 'store': 407, 'tables': before})
    batch.write_json(batch.ROOT / 'demo-prices-20261003.json', {
        'kind': 'synthetic graduation-demo retail prices, not actual market prices', 'currency': 'CNY',
        'prices': [{k: v for k, v in p.items() if k not in ('remark', 'description')} for p in plan]})
    ids = ','.join(str(r['id']) for r in missing)
    sql = ['CREATE TEMPORARY TABLE thesis_price_guard(ok INT NOT NULL CHECK(ok=1));',
           'START TRANSACTION;', 'SELECT id FROM ph_drug WHERE id IN (' + ids + ') FOR UPDATE;',
           'INSERT INTO thesis_price_guard VALUES(IF(DATABASE()=' + batch.literal(batch.DB) + ',1,0));',
           'INSERT INTO thesis_price_guard VALUES(IF((SELECT COUNT(*) FROM ph_store WHERE id=407 AND tenant_id=163 AND status=1 AND deleted=0)=1,1,0));']
    for r, p in zip(missing, plan, strict=True):
        where = 'id=' + str(r['id']) + ' AND tenant_id=163 AND deleted=0 AND creator=' + batch.literal(batch.BATCH)
        # Optimistic checks preserve concurrent edits to the text fields being changed.
        sql += ['INSERT INTO thesis_price_guard VALUES(IF((SELECT COUNT(*) FROM ph_drug WHERE ' + where +
                ' AND drug_code=' + batch.literal(r['drug_code']) + ' AND retail_price=0 AND remark <=> ' +
                batch.literal(r['remark']) + ' AND description <=> ' + batch.literal(r['description']) + ')=1,1,0));',
                'UPDATE ph_drug SET retail_price=' + p['price'] + ',remark=' + batch.literal(p['remark']) +
                ',description=' + batch.literal(p['description']) + ',updater=' + batch.literal(MARKER) + ' WHERE ' + where + ';']
    sql += ['COMMIT;']
    batch.mysql('\n'.join(sql))
    after = batch.snapshots()
    now = {r['id']: r for r in after['ph_drug']}
    allowed = {'retail_price', 'remark', 'description', 'updater', 'update_time'}
    for r, p in zip(missing, plan, strict=True):
        actual = now[r['id']]
        assert Decimal(str(actual['retail_price'])) == Decimal(p['price'])
        assert actual['remark'] == p['remark'] and actual['description'] == p['description']
        assert all(actual[k] == v for k, v in r.items() if k not in allowed), 'Concurrent changes detected; inspect snapshot'
    changed_ids = {r['id'] for r in missing}
    for table in before:
        lookup = {r['id']: r for r in after[table]}
        assert all(lookup.get(r['id']) == r for r in before[table]
                   if table != 'ph_drug' or r['id'] not in changed_ids), 'Other records changed concurrently'
    batch.write_json(batch.BACKUPS / 'after-demo-prices-20261003.json', {
        'updated_rows': [now[i] for i in sorted(changed_ids)], 'backup': backup.name})
    print(f'Updated {len(missing)} zero-price rows; remaining batch zero prices: '
          f'{sum(now[i]["retail_price"] == 0 for i in expected)}. Audit, Rx, stock and online flags unchanged.')


if __name__ == '__main__':
    main()
