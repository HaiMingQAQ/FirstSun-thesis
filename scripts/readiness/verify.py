"""Offline by default. --apply is exclusively for a separately approved isolated MySQL copy."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = [
    ROOT / 'sql/migrations/20261102_a_firstsun_consultation_grant.sql',
    ROOT / 'sql/migrations/20261102_b_member_status_labels.sql',
    ROOT / 'sql/migrations/20261102_c_unrelated_ai_menus_disable.sql',
]
PAGES = ['AiImage', 'AiImageManager', 'AiMusic', 'AiMusicManager', 'AiWrite',
         'AiWriteManager', 'AiMindMap', 'AiMindMapManager']
ACTIONS = ['ai:image:query', 'ai:image:update', 'ai:image:delete',
           'ai:music:query', 'ai:music:update', 'ai:music:delete',
           'ai:write:query', 'ai:write:delete', 'ai:mind-map:query', 'ai:mind-map:delete']
PROTECTED = ['system_tenant', 'system_role', 'system_users', 'system_user_role',
             'ph_employee', 'member_user', 'ai_model', 'ai_api_key']
TABLES = PROTECTED + ['system_menu', 'system_role_menu', 'system_tenant_package',
                      'system_dict_type', 'system_dict_data']
AUDIT = {'updater', 'update_time'}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def live(rows):
    return [r for r in rows if r['deleted'] == 0]


def one(rows, **keys):
    matches = [r for r in live(rows) if all(r.get(k) == v for k, v in keys.items())]
    check(len(matches) == 1, 'Missing/ambiguous identity: ' + ','.join(keys))
    return matches[0]


def unchanged_except(before, after, allowed):
    check(before.keys() == after.keys(), 'Row shape changed')
    check(all(before[k] == after[k] for k in before if k not in allowed),
          'Unrelated row field changed')


def compare(before, after):
    """Independent scope/delta checks; never writes raw rows or secrets to evidence."""
    for table in PROTECTED:
        check(before[table] == after[table], 'Protected table changed: ' + table)
    menus = before['system_menu']
    page = one(menus, component_name='PharmacyConsultation')
    required = [page['id']] + [one(menus, permission=p)['id'] for p in
                              ['pharmacy:consultation:query', 'pharmacy:consultation:reply']]
    targets = {one(menus, component_name=n)['id'] for n in PAGES}
    targets |= {one(menus, permission=p)['id'] for p in ACTIONS}
    check(len(targets) == 18, 'AI target identities overlap')
    check(not targets.intersection({22300, 22349}), 'Protected numeric identity collision')
    for table in ['system_menu', 'system_tenant_package', 'system_dict_type', 'system_dict_data']:
        b = {r['id']: r for r in before[table]}
        a = {r['id']: r for r in after[table]}
        check(b.keys() == a.keys(), 'Unexpected inserted/deleted rows: ' + table)
        for key, old in b.items():
            new = a[key]
            if table == 'system_menu' and key in targets:
                unchanged_except(old, new, {'status', 'visible'} | AUDIT)
                check(new['status'] == 1 and new['visible'] == 0, 'AI target still enabled')
            elif table == 'system_tenant_package' and key == 114:
                unchanged_except(old, new, {'menu_ids'} | AUDIT)
                previous = json.loads(old['menu_ids'])
                expected = previous + [mid for mid in required if mid not in previous]
                check(json.loads(new['menu_ids']) == expected, 'Package grants overwritten/duplicated')
            elif table == 'system_dict_type' and old['type'] == 'pharmacy_member_status' and old['deleted'] == 0:
                unchanged_except(old, new, {'remark'} | AUDIT)
                check(new['remark'] == '会员状态：0启用 / 1禁用', 'Dictionary type text incorrect')
            elif table == 'system_dict_data' and old['dict_type'] == 'pharmacy_member_status' and old['deleted'] == 0 and old['value'] in ['0', '1']:
                unchanged_except(old, new, {'label', 'sort', 'list_class', 'remark'} | AUDIT)
                expected = ('启用', 1, 'success', '会员启用') if old['value'] == '0' else ('禁用', 2, 'info', '会员禁用')
                check(tuple(new[k] for k in ['label', 'sort', 'list_class', 'remark']) == expected,
                      'Member dictionary polarity incorrect')
            else:
                check(old == new, 'Unrelated row changed in ' + table)
    previous = {r['id']: r for r in before['system_role_menu']}
    current = {r['id']: r for r in after['system_role_menu']}
    check(all(current.get(key) == row for key, row in previous.items()), 'Existing role grants changed')
    added = [row for key, row in current.items() if key not in previous]
    old_ids = {r['menu_id'] for r in live(list(previous.values())) if r['role_id'] == 167 and r['tenant_id'] == 163}
    expected = set(required) - old_ids
    check(len(added) == len(expected), 'Unexpected number of grants')
    check({r['menu_id'] for r in added} == expected, 'Wrong menu grants')
    check(all(r['role_id'] == 167 and r['tenant_id'] == 163 and r['deleted'] == 0
              for r in added), 'Grant escaped selected tenant/role')
    return {'consultationMenuIds': required, 'aiTargetIds': sorted(targets),
            'newRoleGrants': len(added), 'protectedTablesUnchanged': PROTECTED}


class Mysql:
    def __init__(self, args):
        self.args = args
        self.events = []
        self.directory = Path(args.private_evidence_dir)
        self.directory.mkdir(exist_ok=False)

    def run(self, sql, allow_failure=False):
        args = self.args
        command = [args.mysql, '--defaults-extra-file=' + str(Path(args.client_file).resolve()),
                   '--protocol=tcp', '--host=127.0.0.1', '--port=' + str(args.port),
                   '--default-character-set=utf8mb4', '--binary-mode=1', '--batch', '--raw',
                   '--skip-column-names', '--database=' + args.database]
        prefix = 'SELECT DATABASE(),@@server_uuid;\n'
        result = subprocess.run(command, input=(prefix + sql).encode(), capture_output=True,
                                timeout=120, check=False)
        index = len(self.events)
        (self.directory / f'{index:03}-stderr.log').write_bytes(result.stderr)
        self.events.append({'command': command, 'exitCode': result.returncode,
                            'sqlSHA256': hashlib.sha256(sql.encode()).hexdigest(),
                            'mysqlErrorCode': (re.search(rb'ERROR (\d+)', result.stderr).group(1).decode()
                                               if re.search(rb'ERROR (\d+)', result.stderr) else None)})
        # Prefix and statements execute on one connection; no --force, no password arguments.
        lines = result.stdout.decode('utf8').splitlines()
        check(lines and lines[0].split('\t') == [args.database, args.expected_uuid],
              'Database/UUID identity mismatch; stop')
        if result.returncode and not allow_failure:
            raise ValueError('MySQL command failed; private stderr retained; no retry')
        return lines[1:], result.returncode

    def identity(self):
        # Must be called before sending migration DML, not merely after its execution.
        self.run('SELECT 1;')

    def snapshot(self):
        result = {}
        for table in TABLES:
            columns, _ = self.run("SELECT COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS "
                                  "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + table +
                                  "' ORDER BY ORDINAL_POSITION;")
            check(columns, 'Required table missing: ' + table)
            fields = []
            for line in columns:
                name, data_type = line.split('\t')
                check(re.fullmatch('[a-zA-Z0-9_]+', name), 'Unexpected column identifier')
                expression = '`' + name + '`'
                if data_type == 'bit':
                    expression += '+0'
                elif data_type in ['binary', 'varbinary', 'blob', 'mediumblob', 'longblob', 'tinyblob']:
                    expression = 'HEX(' + expression + ')'
                fields.extend(["'" + name + "'", expression])
            rows, _ = self.run('SELECT JSON_OBJECT(' + ','.join(fields) + ') FROM `' + table + '` ORDER BY id;')
            result[table] = [json.loads(row) for row in rows]
        return result

    def migrate(self, file, allow_failure=False):
        self.identity()
        return self.run(file.read_text(encoding='utf8'), allow_failure)[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--mysql')
    parser.add_argument('--client-file')
    parser.add_argument('--database')
    parser.add_argument('--expected-uuid')
    parser.add_argument('--port', type=int, default=23347)
    parser.add_argument('--private-evidence-dir')
    parser.add_argument('--expect-rejection', type=int, choices=[45, 46, 47])
    args = parser.parse_args()
    if not args.apply:
        print(json.dumps({'dryRun': True, 'connections': 0,
                          'migrations': [str(p) for p in MIGRATIONS],
                          'plan': 'Approved isolated schema only: snapshot, apply, compare scope, replay, compare exact equality. Negative fixtures use --expect-rejection.'}))
        return
    check(all([args.mysql, args.client_file, args.database, args.expected_uuid,
               args.private_evidence_dir]), 'Missing explicit isolated execution parameters')
    check(re.fullmatch(r'firstsun_readiness_[a-z0-9_]+', args.database), 'Isolated schema prefix required')
    check(args.expected_uuid.strip(), 'Explicit expected UUID required')
    db = Mysql(args)
    try:
        db.identity()
        before = db.snapshot()
        if args.expect_rejection:
            code = db.migrate(MIGRATIONS[args.expect_rejection - 45], True)
            check(code != 0, 'Invalid fixture was not refused')
            check(db.events[-1]['mysqlErrorCode'] == '1048',
                  'Expected identity guard error 1048, not a syntax/permission/client failure')
            check(before == db.snapshot(), 'Rejected migration changed persistent rows')
            result = {'rejectedWithoutChanges': args.expect_rejection}
        else:
            for file in MIGRATIONS:
                db.migrate(file)
            after = db.snapshot()
            result = compare(before, after)
            for file in MIGRATIONS:
                db.migrate(file)
            check(after == db.snapshot(), 'Replay changed rows, grants or timestamps')
            result['replayExactEquality'] = True
        result.update({'database': args.database, 'beforeDigest': digest(before),
                       'events': db.events, 'rawRowsRetained': False})
        (db.directory / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf8')
        print(json.dumps({'passed': True, 'database': args.database, 'evidence': str(db.directory)}))
    except Exception:
        (db.directory / 'FAILED.json').write_text(json.dumps({'events': db.events, 'stopped': True}, indent=2), encoding='utf8')
        raise


if __name__ == '__main__':
    main()
