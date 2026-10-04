"""Offline preflight rejection tests; never connects to a database."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import import_catalogue as batch


class CatalogueGuards(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(batch.DATA.read_text(encoding='utf-8'))

    def rejects(self, mutate):
        data = copy.deepcopy(self.data)
        mutate(data)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'data.json'
            path.write_text(json.dumps(data), encoding='utf-8')
            with patch.object(batch, 'DATA', path), self.assertRaises(AssertionError):
                batch.load_catalogue()

    def test_wrong_target(self):
        for key, value in [('expected_database', 'production'), ('tenant_id', 164), ('store_id', 408)]:
            with self.subTest(key=key):
                self.rejects(lambda d: d.update({key: value}))

    def test_duplicate_approval(self):
        self.rejects(lambda d: d['samples'][1].update(approval_no=d['samples'][0]['approval_no']))

    def test_duplicate_code(self):
        self.rejects(lambda d: d['samples'][1].update(drug_code=d['samples'][0]['drug_code']))

    def test_rx_mapping_mismatch(self):
        self.rejects(lambda d: d['samples'][0].update(drug_type=0))

    def test_images_and_business_data_rejected(self):
        for key, value in [('image_path', 'image.png'), ('price', 19), ('inventory', 100)]:
            with self.subTest(key=key):
                self.rejects(lambda d: d['samples'][0].update({key: value}))

    def test_unverified_barcode_rejected(self):
        self.rejects(lambda d: d['samples'][0].update(barcode='1234567890123'))

    def test_incomplete_medical_review_rejected(self):
        self.rejects(lambda d: d['samples'][0]['medical_review'].update(dosage=''))

    def test_case_insensitive_literal_collation(self):
        self.assertTrue(batch.literal("name'\\中文").endswith('COLLATE utf8mb4_unicode_ci'))
        self.assertNotIn("name'", batch.literal("name'\\中文"))


if __name__ == '__main__':
    unittest.main()
