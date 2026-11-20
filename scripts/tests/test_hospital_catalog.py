import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('catalog_collector', Path(__file__).parents[1] / 'collect-hospital-catalog.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def page(number, size, total, identities):
    return {'page': number, 'requestedSize': size, 'reportedSize': size, 'total': total,
            'fetchedAt': MODULE.timestamp(),
            'rows': [{'ykiho': identity, 'yadmNm': 'fixture-' + identity, 'extra': 'retained'} for identity in identities]}


class FakeClient:
    def __init__(self, pages):
        import threading
        self.pages = pages
        self.cancel = threading.Event()
        self.requests = []

    def page(self, code, number, size):
        self.requests.append((code, number, size))
        if size == 1:
            value = dict(self.pages[1])
            value['rows'] = value['rows'][:1]
            return value
        return self.pages[number]


class CollectorTests(unittest.TestCase):
    def test_preserves_raw_fields_and_rejects_upstream_errors(self):
        payload = b'<response><header><resultCode>00</resultCode></header><body><totalCount>1</totalCount><pageNo>1</pageNo><numOfRows>100</numOfRows><items><item><ykiho>A</ykiho><yadmNm>Hospital</yadmNm><custom>original</custom></item></items></body></response>'
        result = MODULE.parse_page(payload, 1, 100)
        self.assertEqual(result['rows'][0]['custom'], 'original')
        with self.assertRaisesRegex(MODULE.CollectionError, 'upstream_call_limit'):
            MODULE.parse_page(payload.replace(b'<resultCode>00', b'<resultCode>22'), 1, 100)
        with self.assertRaisesRegex(MODULE.CollectionError, 'invalid_xml_size_or_declaration'):
            MODULE.parse_page(b'<!DOCTYPE response [<!ENTITY x "value">]>' + payload, 1, 100)

    def test_page_shape_and_total_changes_abort(self):
        MODULE.validate_page(page(1, 2, 3, ['A', 'B']), 1, 2, 3)
        with self.assertRaisesRegex(MODULE.CollectionError, 'source_total_changed'):
            MODULE.validate_page(page(1, 2, 4, ['A', 'B']), 1, 2, 3)
        with self.assertRaisesRegex(MODULE.CollectionError, 'page_row_count_mismatch'):
            MODULE.validate_page(page(1, 2, 3, ['A']), 1, 2, 3)

    def test_complete_collection_resume_and_duplicate_protection(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            client = FakeClient({1: page(1, 2, 3, ['A', 'B']), 2: page(2, 2, 3, ['C'])})
            data, times, scope_times = MODULE.collect_scope(client, directory, '', 2, 2)
            self.assertEqual(set(data), {'A', 'B', 'C'})
            self.assertEqual(set(times), set(data))
            self.assertEqual(scope_times['firstFetchedAt'], min(times.values()))
            resumed = FakeClient(client.pages)
            MODULE.collect_scope(resumed, directory, '', 2, 2)
            self.assertEqual(resumed.requests, [('', 1, 1)])
            saved = directory / 'basic' / 'page-2.json'
            saved.write_text(saved.read_text().replace('fixture-C', 'changed'), encoding='utf-8')
            with self.assertRaisesRegex(MODULE.CollectionError, 'page_checksum_mismatch'):
                MODULE.collect_scope(resumed, directory, '', 2, 2)
        with tempfile.TemporaryDirectory() as folder:
            duplicate = FakeClient({1: page(1, 2, 3, ['A', 'B']), 2: page(2, 2, 3, ['A'])})
            with self.assertRaisesRegex(MODULE.CollectionError, 'duplicate_hospital_across_pages'):
                MODULE.collect_scope(duplicate, Path(folder), '', 2, 2)

    def test_empty_department_is_valid_but_empty_basic_is_not(self):
        with tempfile.TemporaryDirectory() as folder:
            client = FakeClient({1: page(1, 100, 0, [])})
            self.assertEqual(MODULE.collect_scope(client, Path(folder), '49', 100, 1)[0], {})
            with self.assertRaisesRegex(MODULE.CollectionError, 'empty_nationwide_catalog'):
                MODULE.collect_scope(client, Path(folder), '', 100, 1)

    def test_changed_final_count_does_not_publish_scope(self):
        class ChangedClient(FakeClient):
            def page(self, code, number, size):
                result = super().page(code, number, size)
                if size == 1:
                    result['total'] += 1
                return result
        with tempfile.TemporaryDirectory() as folder:
            client = ChangedClient({1: page(1, 2, 2, ['A', 'B'])})
            with self.assertRaisesRegex(MODULE.CollectionError, 'source_total_changed_after_collection'):
                MODULE.collect_scope(client, Path(folder), '', 2, 1)

    def test_export_has_all_scopes_and_checksum_without_secrets(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            data = {'A': {'ykiho': 'A', 'yadmNm': 'Hospital', 'allFields': 'kept'}}
            counts = {'basic': 1, **{code: 1 if code == '01' else 0 for code in MODULE.DEPARTMENTS}}
            basic_time = MODULE.timestamp()
            department_time = MODULE.timestamp()
            times = {code: {'firstFetchedAt': department_time, 'lastFetchedAt': department_time} for code in MODULE.DEPARTMENTS}
            times['basic'] = {'firstFetchedAt': basic_time, 'lastFetchedAt': basic_time}
            manifest = MODULE.export_catalog(directory, data, {'A': basic_time}, {'A': {'01'}}, counts, 17, times)
            payload = (directory / 'catalog.jsonl').read_bytes()
            self.assertEqual(manifest['sha256'], hashlib.sha256(payload).hexdigest())
            row = json.loads(payload)
            self.assertEqual(row['apiFields'], data['A'])
            self.assertEqual(row['departmentCodes'], ['01'])
            self.assertEqual(row['fetchedAt'], basic_time)
            self.assertEqual(manifest['lastFetchedAt'], department_time)
            self.assertNotIn('serviceKey', payload.decode())

    def test_stale_department_checkpoint_stops_export_even_with_fresh_basic(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            fresh = MODULE.timestamp()
            old = (MODULE.dt.datetime.now(MODULE.dt.timezone.utc) - MODULE.dt.timedelta(hours=49)).isoformat()
            times = {code: {'firstFetchedAt': fresh, 'lastFetchedAt': fresh} for code in ['basic', *MODULE.DEPARTMENTS]}
            times['01'] = {'firstFetchedAt': old, 'lastFetchedAt': old}
            with self.assertRaisesRegex(MODULE.CollectionError, 'stale_or_future_scope_checkpoint'):
                MODULE.export_catalog(directory, {'A': {'ykiho': 'A', 'yadmNm': 'Hospital'}}, {'A': fresh}, {'A': set()}, {}, 0, times)
            self.assertFalse((directory / 'catalog-manifest.json').exists())

    def test_request_budget_stops_before_network(self):
        with tempfile.TemporaryDirectory() as folder:
            client = MODULE.Client('fake-never-sent', Path(folder), maximum_calls=1)
            client.calls = 1
            with patch('urllib.request.urlopen') as network:
                with self.assertRaisesRegex(MODULE.CollectionError, 'local_daily_request_budget_reached'):
                    client.page('', 1, 100)
                network.assert_not_called()

    def test_small_page_fallback_keeps_original_logical_offset(self):
        with tempfile.TemporaryDirectory() as folder:
            client = MODULE.Client('fake-never-sent', Path(folder))
            requests = []
            def request(code, number, size):
                requests.append((number, size))
                if size == 1000:
                    raise MODULE.CollectionError('upstream_transport_failed')
                start = (number - 1) * size
                return page(number, size, 2050, [str(value) for value in range(start, min(start + size, 2050))])
            client._request_page = request
            result = client.page('', 2, 1000)
            self.assertEqual(requests[1], (11, 100))
            self.assertEqual(result['rows'][0]['ykiho'], '1000')
            self.assertEqual(result['rows'][-1]['ykiho'], '1999')
            self.assertEqual(len(result['rows']), 1000)
            final = client.page('', 3, 1000)
            self.assertEqual(len(final['rows']), 50)
            self.assertEqual(final['rows'][0]['ykiho'], '2000')
            self.assertEqual(sum(size == 1000 for _, size in requests), 1)
            resumed = MODULE.Client('fake-never-sent', Path(folder))
            resumed._request_page = request
            resumed.page('', 3, 1000)
            self.assertEqual(sum(size == 1000 for _, size in requests), 1)

    def test_quota_errors_never_trigger_page_size_fallback(self):
        with tempfile.TemporaryDirectory() as folder:
            client = MODULE.Client('fake-never-sent', Path(folder))
            with patch.object(client, '_request_page', side_effect=MODULE.CollectionError('upstream_call_limit')) as request:
                with self.assertRaisesRegex(MODULE.CollectionError, 'upstream_call_limit'):
                    client.page('', 1, 1000)
                self.assertEqual(request.call_count, 1)


if __name__ == '__main__':
    unittest.main()
