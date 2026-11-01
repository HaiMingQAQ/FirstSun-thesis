import contextlib
import copy
import io
import json
import re
import subprocess
import sys
import unittest
from unittest.mock import patch

import verify


def fixture(offset=50000):
    before = {table: [] for table in verify.TABLES}
    before['system_menu'] = [
        {'id': offset, 'component_name': 'PharmacyConsultation', 'permission': '', 'deleted': 0, 'status': 0, 'visible': 1},
        {'id': offset + 1, 'permission': 'pharmacy:consultation:query', 'deleted': 0, 'status': 0, 'visible': 1},
        {'id': offset + 2, 'permission': 'pharmacy:consultation:reply', 'deleted': 0, 'status': 0, 'visible': 1},
        {'id': 22300, 'component_name': 'Purchase', 'permission': 'pharmacy:purchase:query', 'deleted': 0, 'status': 0, 'visible': 1},
        {'id': 22349, 'permission': 'pharmacy:ai:chat', 'deleted': 0, 'status': 0, 'visible': 1},
    ]
    for i, name in enumerate(verify.PAGES):
        before['system_menu'].append({'id': offset + 10 + i, 'component_name': name, 'permission': '', 'deleted': 0, 'status': 0, 'visible': 1})
    for i, permission in enumerate(verify.ACTIONS):
        before['system_menu'].append({'id': offset + 30 + i, 'permission': permission, 'deleted': 0, 'status': 0, 'visible': 1})
    before['system_tenant_package'] = [{'id': 114, 'menu_ids': '[22300,22349]'}, {'id': 115, 'menu_ids': '[9]'}]
    before['system_role_menu'] = [{'id': 1, 'role_id': 167, 'tenant_id': 163, 'menu_id': 22300, 'deleted': 0}]
    before['system_dict_type'] = [{'id': 100, 'type': 'pharmacy_member_status', 'deleted': 0, 'remark': 'old'}]
    before['system_dict_data'] = [
        {'id': 101, 'dict_type': 'pharmacy_member_status', 'deleted': 0, 'value': '0', 'label': '禁用', 'sort': 2, 'list_class': 'info', 'remark': '会员禁用'},
        {'id': 102, 'dict_type': 'pharmacy_member_status', 'deleted': 0, 'value': '1', 'label': '启用', 'sort': 1, 'list_class': 'success', 'remark': '会员启用'},
        {'id': 103, 'dict_type': 'unrelated', 'deleted': 0, 'value': '0', 'label': '其他'},
    ]
    for table in verify.PROTECTED:
        before[table] = [{'id': 1, 'state': 'must stay exact'}]
    after = copy.deepcopy(before)
    for row in after['system_menu']:
        if row.get('component_name') in verify.PAGES or row['permission'] in verify.ACTIONS:
            row.update(status=1, visible=0)
    after['system_tenant_package'][0]['menu_ids'] = json.dumps([22300, 22349, offset, offset + 1, offset + 2])
    after['system_role_menu'] += [{'id': i + 2, 'role_id': 167, 'tenant_id': 163, 'menu_id': offset + i, 'deleted': 0} for i in range(3)]
    after['system_dict_type'][0]['remark'] = '会员状态：0启用 / 1禁用'
    after['system_dict_data'][0].update(label='启用', sort=1, list_class='success', remark='会员启用')
    after['system_dict_data'][1].update(label='禁用', sort=2, list_class='info', remark='会员禁用')
    return before, after


class ReadinessTests(unittest.TestCase):
    def test_default_is_offline_and_never_spawns_mysql(self):
        with patch.object(sys, 'argv', ['verify.py']), patch.object(verify.subprocess, 'run', side_effect=AssertionError('must not execute')), contextlib.redirect_stdout(io.StringIO()) as output:
            verify.main()
        self.assertEqual(json.loads(output.getvalue())['connections'], 0)

    def test_apply_refuses_source_schema_before_client_creation(self):
        argv = ['verify.py', '--apply', '--mysql', 'mysql', '--client-file', 'private', '--database', 'firstsun_followup_20261012', '--expected-uuid', 'expected', '--private-evidence-dir', 'private']
        with patch.object(sys, 'argv', argv), patch.object(verify, 'Mysql') as mysql:
            with self.assertRaisesRegex(ValueError, 'Isolated schema prefix'):
                verify.main()
            mysql.assert_not_called()

    def test_delta_verifier_accepts_unrelated_numeric_menu_ids_and_replay(self):
        for offset in [50000, 712345]:
            before, after = fixture(offset)
            result = verify.compare(before, after)
            self.assertEqual(result['consultationMenuIds'], [offset, offset + 1, offset + 2])
            self.assertEqual(len(result['aiTargetIds']), 18)
            self.assertEqual(verify.compare(after, copy.deepcopy(after))['newRoleGrants'], 0)

    def test_protected_business_or_model_rows_cannot_change(self):
        for table in verify.PROTECTED:
            before, after = fixture()
            after[table][0]['state'] = 'unexpected'
            with self.assertRaisesRegex(ValueError, 'Protected table changed'):
                verify.compare(before, after)

    def test_other_menu_or_package_or_dictionary_is_untouched(self):
        for mutate in [lambda a: a['system_menu'][3].update(status=1),
                       lambda a: a['system_tenant_package'][1].update(menu_ids='[]'),
                       lambda a: a['system_dict_data'][2].update(label='changed')]:
            before, after = fixture()
            mutate(after)
            with self.assertRaisesRegex(ValueError, 'Unrelated row'):
                verify.compare(before, after)

    def test_existing_grants_cannot_be_deleted_or_new_grants_cross_tenant(self):
        for mutate in [lambda a: a['system_role_menu'].pop(0),
                       lambda a: a['system_role_menu'][-1].update(tenant_id=1),
                       lambda a: a['system_role_menu'][-1].update(role_id=1)]:
            before, after = fixture()
            mutate(after)
            with self.assertRaises(ValueError):
                verify.compare(before, after)

    def test_package_append_cannot_erase_existing_grants_or_duplicate_ids(self):
        for ids in [[50000, 50001, 50002], [22300, 22349, 50000, 50001, 50002, 50002]]:
            before, after = fixture()
            after['system_tenant_package'][0]['menu_ids'] = json.dumps(ids)
            with self.assertRaisesRegex(ValueError, 'Package grants'):
                verify.compare(before, after)

    def test_dictionary_polarity_and_all_target_states_are_checked(self):
        for mutate in [lambda a: a['system_dict_data'][0].update(label='禁用'),
                       lambda a: a['system_menu'][5].update(status=0)]:
            before, after = fixture()
            mutate(after)
            with self.assertRaises(ValueError):
                verify.compare(before, after)

    def test_original_44_migrations_and_manifest_prefix_are_byte_identical(self):
        old = json.loads(subprocess.check_output(['git', 'show', 'd955f9a75bf26de082667bf55607edfb9ba6ed34:deploy/local-migrations.json'], cwd=verify.ROOT))
        current = json.loads((verify.ROOT / 'deploy/local-migrations.json').read_text(encoding='utf8'))
        self.assertEqual(current[:44], old)
        self.assertEqual([m['id'] for m in current], list(range(1, 48)))
        for entry in old:
            expected = subprocess.check_output(['git', 'show', 'd955f9a75bf26de082667bf55607edfb9ba6ed34:' + entry['path']], cwd=verify.ROOT)
            # Git's working-tree CRLF conversion is normal; compare its clean canonical blob.
            actual = subprocess.check_output(['git', 'hash-object', '--path=' + entry['path'], entry['path']], cwd=verify.ROOT).strip()
            blob = subprocess.check_output(['git', 'hash-object', '--stdin'], input=expected, cwd=verify.ROOT).strip()
            self.assertEqual(actual, blob, entry['path'])

    def test_new_sql_write_scope_and_mysql_temporary_table_reopen_guard(self):
        allowed = [{'system_tenant_package', 'system_role_menu'}, {'system_dict_type', 'system_dict_data'}, {'system_menu'}]
        for number, (file, scope) in enumerate(zip(verify.MIGRATIONS, allowed), 45):
            sql = re.sub(r'--[^\n]*', '', file.read_text(encoding='utf8'))
            temps = set(re.findall(r'CREATE TEMPORARY TABLE (\w+)', sql, re.I))
            writes = set(re.findall(r'(?:INSERT INTO|UPDATE|DELETE FROM)\s+(\w+)', sql, re.I))
            self.assertEqual(writes - temps, scope)
            self.assertNotRegex(sql, r'(?i)\b(?:TRUNCATE|REPLACE|GRANT|REVOKE|USE)\b')
            self.assertNotRegex(sql, r'(?i)CREATE\s+(?!TEMPORARY)TABLE')
            for statement in sql.split(';'):
                for table in temps:
                    self.assertLessEqual(len(re.findall(r'\b' + table + r'\b', statement)), 1, 'MySQL cannot reopen one TEMPORARY table twice per statement')
                if any(re.search(r'(?i)\b(?:INSERT INTO|UPDATE)\s+' + t + r'\b', statement) for t in scope):
                    self.assertIn('@fs' + str(number) + '_ok', statement)
            self.assertIn('IF(@fs' + str(number) + '_ok,1,NULL)', sql)


if __name__ == '__main__':
    unittest.main()
