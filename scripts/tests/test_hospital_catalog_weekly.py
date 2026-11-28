import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

from test_hospital_catalog import MODULE, Response, error_xml, page, xml_page


MONDAY = dt.datetime(2026, 11, 23, 0, 0, tzinfo=MODULE.KST)
SUNDAY = MONDAY + dt.timedelta(days=6)
WEEK = '2026-11-23'


class PlannedClient:
    def __init__(self, failures=(), counts=None):
        self.calls = 0
        self.requests = []
        self.failures = set(failures)
        self.counts = counts or {}

    def page(self, code, number, size, region):
        key = (region, code or 'basic', number, size)
        self.calls += 1
        self.requests.append(key)
        if key in self.failures:
            raise MODULE.CollectionError('upstream_service_unavailable', '99')
        total = self.counts.get((region, code or 'basic'), 5 if not code else 1 if code == '01' else 0)
        start = (number - 1) * size
        return page(number, size, total, [region + '-' + str(index) for index in range(start, min(start + size, total))], region)


class WeeklyCollectorTests(unittest.TestCase):
    def setUp(self):
        emitter = patch.object(MODULE, 'emit')
        self.events = emitter.start()
        self.addCleanup(emitter.stop)

    def batch(self, client, directory, mode='collect', regions=('110000',), day='monday', size=2):
        return MODULE.WeeklyBatch(client, directory, list(regions), day, WEEK, size, mode)

    def test_network_errors_receive_one_http_attempt_without_fallback(self):
        failures = [Response(error_xml(code)) for code in ('01', '02', '04', '05', '99', '22', '30')]
        failures += [urllib.error.URLError('fixture-secret'), urllib.error.HTTPError('https://example.invalid/?serviceKey=fixture-secret', 500, 'fixture-secret', {}, None)]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as folder:
                client = MODULE.Client('fixture-secret', Path(folder))
                kwargs = {'side_effect': failure} if isinstance(failure, Exception) else {'return_value': failure}
                with patch('urllib.request.urlopen', **kwargs) as network:
                    with self.assertRaises(MODULE.CollectionError):
                        client.page('', 1, 500, '110000')
                self.assertEqual(network.call_count, 1)
                self.assertEqual(client.calls, 1)
                self.assertFalse((Path(folder) / 'transport-state.json').exists())
        self.assertFalse(any(call.args[0] in ('request_retry', 'smaller_page_retry') for call in self.events.call_args_list))

    def test_weekday_failure_keeps_collecting_known_pages_and_scopes_without_retry(self):
        key = ('110000', 'basic', 2, 2)
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE, 'utc_now', return_value=MONDAY):
            directory, client = Path(folder), PlannedClient([key])
            result = self.batch(client, directory).run()
            self.assertEqual(client.requests.count(key), 1)
            self.assertIn(('110000', 'basic', 3, 2), client.requests)
            self.assertIn(('110000', '80', 1, 2), client.requests)
            self.assertEqual(result['completeRegions'], [])
            self.assertEqual(result['deferredRegions'], ['110000'])
            self.assertEqual(len(result['failedRequests']), 1)
            self.assertFalse((directory / 'ready' / '110000' / 'catalog-manifest.json').exists())
            rerun = PlannedClient()
            repeated = self.batch(rerun, directory).run()
            self.assertEqual(rerun.requests, [])
            self.assertEqual(repeated['requests'], 0)

    def test_sunday_retries_only_failed_query_and_exports_same_week_recovery(self):
        key = ('110000', 'basic', 2, 2)
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                self.batch(PlannedClient([key]), directory).run()
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                client = PlannedClient()
                result = self.batch(client, directory, 'retry').run()
                self.assertEqual(client.requests, [key])
                self.assertEqual(result['requests'], 1)
                self.assertEqual(result['completeRegions'], ['110000'])
                manifest = json.loads((directory / 'ready' / '110000' / 'catalog-manifest.json').read_text(encoding='utf-8'))
                self.assertEqual(manifest['schemaVersion'], 4)
                self.assertEqual(manifest['recovery'], {'mode': 'sunday-failed-requests', 'weekStartKst': WEEK,
                    'originalDay': 'monday', 'attempt': 1, 'recoveredAt': manifest['completedAt']})
                self.assertEqual(manifest['regions'], ['110000'])
                self.assertEqual(manifest['rows'], 5)
                self.assertEqual(json.loads((directory / 'batch.json').read_text())['sourceMode'], 'collect')
                replay = PlannedClient()
                self.batch(replay, directory, 'retry').run()
                self.assertEqual(replay.requests, [])

    def test_sunday_failure_is_skipped_and_never_retried_on_rerun(self):
        key = ('110000', 'basic', 2, 2)
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                self.batch(PlannedClient([key]), directory).run()
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                client = PlannedClient([key])
                result = self.batch(client, directory, 'retry').run()
                self.assertEqual(client.requests, [key])
                self.assertEqual(result['failedRequests'], [])
                self.assertEqual(len(result['skippedRequests']), 1)
                self.assertEqual(result['deferredRegions'], ['110000'])
                rerun = PlannedClient()
                self.batch(rerun, directory, 'retry').run()
                self.assertEqual(rerun.requests, [])

    def test_first_page_failure_uses_independent_count_to_plan_remaining_weekday_pages(self):
        key = ('110000', 'basic', 1, 2)
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                client = PlannedClient([key])
                self.batch(client, directory).run()
                self.assertEqual(client.requests[:4], [key, ('110000', 'basic', 1, 1), ('110000', 'basic', 2, 2), ('110000', 'basic', 3, 2)])
                self.assertEqual(client.requests.count(('110000', 'basic', 1, 1)), 1)
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                client = PlannedClient()
                result = self.batch(client, directory, 'retry').run()
                self.assertEqual(client.requests, [key])
                self.assertEqual(result['completeRegions'], ['110000'])

    def test_unknown_pages_are_not_new_sunday_requests(self):
        failures = [('110000', 'basic', 1, 2), ('110000', 'basic', 1, 1)]
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                self.batch(PlannedClient(failures), directory).run()
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                client = PlannedClient()
                result = self.batch(client, directory, 'retry').run()
                self.assertEqual(set(client.requests), set(failures))
                self.assertEqual(len(client.requests), 2)
                self.assertEqual(result['deferredRegions'], ['110000'])
                self.assertEqual(result['skippedRequests'][0]['reason'], 'not_requested_during_week')
                self.assertFalse((directory / 'ready' / '110000' / 'catalog-manifest.json').exists())

    def test_inconsistent_recovered_total_does_not_trigger_more_requests_or_publish(self):
        key = ('110000', 'basic', 2, 2)
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                self.batch(PlannedClient([key]), directory).run()
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                client = PlannedClient(counts={('110000', 'basic'): 6})
                result = self.batch(client, directory, 'retry').run()
                self.assertEqual(client.requests, [key])
                self.assertEqual(result['deferredRegions'], ['110000'])
                self.assertFalse((directory / 'ready' / '110000' / 'catalog-manifest.json').exists())

    def test_complete_regions_are_exported_independently_and_assembly_is_network_free(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE, 'utc_now', return_value=MONDAY + dt.timedelta(days=2)):
            directory = Path(folder)
            client = PlannedClient([('210000', 'basic', 2, 2)])
            batch = self.batch(client, directory, regions=('210000', '360000', '380000'), day='wednesday')
            result = batch.run()
            self.assertEqual(result['completeRegions'], ['360000', '380000'])
            self.assertEqual(result['deferredRegions'], ['210000'])
            with patch.object(client, 'page', side_effect=AssertionError('assembly made network request')):
                self.assertEqual(len(batch.assemble_region('360000')[0]), 5)
            for region in result['completeRegions']:
                manifest = json.loads((directory / 'ready' / region / 'catalog-manifest.json').read_text(encoding='utf-8'))
                self.assertEqual(manifest['schemaVersion'], 3)
                self.assertEqual(manifest['regions'], [region])

    def test_retry_requires_source_batch_same_week_sunday_and_unspent_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            directory, key = Path(folder), ('110000', 'basic', 2, 2)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                batch = self.batch(PlannedClient([key]), directory)
                batch.run()
                with self.assertRaisesRegex(MODULE.CollectionError, 'recovery_requires_same_week_sunday'):
                    self.batch(PlannedClient(), directory, 'retry')
                failed_path = batch.path(MODULE.request_key(*key))
                record = json.loads(failed_path.read_text())
                record.update(status='claimed', attempt=1)
                MODULE.atomic_json(failed_path, record)
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                client = PlannedClient()
                result = self.batch(client, directory, 'retry').run()
                self.assertEqual(client.requests, [])
                self.assertEqual(result['deferredRegions'], ['110000'])
                metadata_path = directory / 'batch.json'
                metadata = json.loads(metadata_path.read_text())
                metadata.pop('sourceMode')
                MODULE.atomic_json(metadata_path, metadata)
                with self.assertRaisesRegex(MODULE.CollectionError, 'batch_metadata_mismatch'):
                    self.batch(PlannedClient(), directory, 'retry')

    def test_direct_collect_on_sunday_is_rejected_before_files_or_http(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE, 'utc_now', return_value=SUNDAY):
            client = PlannedClient()
            with self.assertRaisesRegex(MODULE.CollectionError, 'collect_not_allowed_on_sunday'):
                self.batch(client, Path(folder))
            self.assertEqual(client.requests, [])
            self.assertEqual(list(Path(folder).iterdir()), [])

    def test_retry_crossing_midnight_skips_remaining_failed_queries_without_http(self):
        failures = [('110000', 'basic', 2, 2), ('110000', '01', 1, 2)]
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY):
                self.batch(PlannedClient(failures), directory).run()
            clock = [SUNDAY]
            class MidnightClient(PlannedClient):
                def page(self, *args):
                    document = super().page(*args)
                    clock[0] = SUNDAY + dt.timedelta(days=1)
                    return document
            with patch.object(MODULE, 'utc_now', side_effect=lambda: clock[0]):
                client = MidnightClient()
                result = self.batch(client, directory, 'retry').run()
            self.assertEqual(len(client.requests), 1)
            self.assertEqual(result['requests'], 1)
            self.assertEqual(result['failedRequests'], [])
            self.assertEqual(result['skippedRequests'][0]['reason'], 'outside_request_window')
            self.assertEqual(result['deferredRegions'], ['110000'])

    def test_local_budget_rejection_is_skipped_not_a_sunday_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with patch.object(MODULE, 'utc_now', return_value=MONDAY), patch.object(MODULE.time, 'sleep'):
                client = MODULE.Client('fixture-secret', directory, maximum_calls=1)
                with patch('urllib.request.urlopen', return_value=Response(xml_page(1, 500, 0, []))) as network:
                    result = self.batch(client, directory, size=500).run()
                self.assertEqual(network.call_count, 1)
                self.assertEqual(result['requests'], 1)
                self.assertEqual(result['failedRequests'], [])
                self.assertTrue(result['skippedRequests'])
                self.assertEqual({item['reason'] for item in result['skippedRequests']}, {'local_daily_request_budget_reached'})
            with patch.object(MODULE, 'utc_now', return_value=SUNDAY):
                recovery_client = PlannedClient()
                self.batch(recovery_client, directory, 'retry', size=500).run()
                self.assertEqual(recovery_client.requests, [])

    def test_cli_uses_500_rows_and_persists_no_credentials_or_raw_errors(self):
        def network(url, timeout):
            query = MODULE.urllib.parse.parse_qs(MODULE.urllib.parse.urlparse(url).query)
            size, number = int(query['numOfRows'][0]), int(query['pageNo'][0])
            if query.get('dgsbjtCd') == ['01']:
                return Response(error_xml('99'))
            return Response(xml_page(number, size, 0, [], '110000'))
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE, 'utc_now', return_value=MONDAY), patch.object(MODULE.time, 'sleep'):
            arguments = ['collect-hospital-catalog.py', '--directory', folder, '--regions', '110000', '--day', 'monday', '--week-start', WEEK]
            with patch.object(MODULE.sys, 'argv', arguments), patch.dict(MODULE.os.environ, {'HIRA_HOSPITAL_KEY': 'fixture-secret'}), patch('urllib.request.urlopen', side_effect=network) as request:
                MODULE.main()
            self.assertEqual(request.call_count, 34)
            metadata = json.loads((Path(folder) / 'batch.json').read_text())
            self.assertEqual(metadata['pageSize'], 500)
            self.assertEqual(metadata['sourceMode'], 'collect')
            payload = '\n'.join(path.read_text(encoding='utf-8') for path in Path(folder).rglob('*.json'))
            self.assertNotIn('fixture-secret', payload)
            self.assertNotIn('example.invalid', payload)
            self.assertNotIn('serviceKey', payload)
            self.assertNotIn('fixture-secret', repr(self.events.call_args_list))


if __name__ == '__main__':
    unittest.main()
