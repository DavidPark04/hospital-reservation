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


def page(number, size, total, identities, region='110000'):
    return {'page': number, 'requestedSize': size, 'reportedSize': size, 'total': total,
            'fetchedAt': MODULE.timestamp(),
            'rows': [{'ykiho': identity, 'yadmNm': 'fixture-' + identity, 'sidoCd': region, 'extra': 'retained'} for identity in identities]}


def xml_page(number, size, total, identities, region='110000'):
    root = MODULE.ET.Element('response')
    MODULE.ET.SubElement(MODULE.ET.SubElement(root, 'header'), 'resultCode').text = '00'
    body = MODULE.ET.SubElement(root, 'body')
    for key, value in {'totalCount': total, 'pageNo': number, 'numOfRows': size}.items():
        MODULE.ET.SubElement(body, key).text = str(value)
    items = MODULE.ET.SubElement(body, 'items')
    for row in page(number, size, total, identities, region)['rows']:
        item = MODULE.ET.SubElement(items, 'item')
        for key, value in row.items():
            MODULE.ET.SubElement(item, key).text = value
    return MODULE.ET.tostring(root)


def error_xml(code, gateway=False):
    root = MODULE.ET.Element('OpenAPI_ServiceResponse' if gateway else 'response')
    header = MODULE.ET.SubElement(root, 'cmmMsgHeader' if gateway else 'header')
    MODULE.ET.SubElement(header, 'returnReasonCode' if gateway else 'resultCode').text = code
    MODULE.ET.SubElement(header, 'returnAuthMsg' if gateway else 'resultMsg').text = 'request https://example.invalid/?serviceKey=fixture-secret'
    return MODULE.ET.tostring(root)


class Response:
    status = 200

    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, size):
        return self.payload[:size]


class FakeClient:
    def __init__(self, pages):
        import threading
        self.pages = pages
        self.cancel = threading.Event()
        self.requests = []

    def page(self, code, number, size, region):
        self.requests.append((code, number, size, region))
        if size == 1:
            value = dict(self.pages[1])
            value.update(page=number, requestedSize=size, reportedSize=size)
            value['rows'] = value['rows'][:1]
            return value
        return self.pages[number]


class GridClient:
    def __init__(self):
        import threading
        self.cancel = threading.Event()
        self.requests = []
        self.calls = 0

    def page(self, code, number, size, region):
        if region not in MODULE.REGIONS:
            raise AssertionError('every request must have a HIRA province filter')
        self.calls += 1
        self.requests.append((code, number, size, region))
        identities = [region + '-A', region + '-B', region + '-C'] if not code else [region + '-A'] if code == '01' else []
        start = (number - 1) * size
        return page(number, size, len(identities), identities[start:start + size], region)


def regional_fixture(regions=None):
    selected = MODULE.normalize_regions(regions)
    data, times, memberships, region_scopes = {}, {}, {}, {}
    basic_time, department_time = MODULE.timestamp(), MODULE.timestamp()
    for region in selected:
        identity = region + '-A'
        data[identity] = {'ykiho': identity, 'yadmNm': 'Hospital', 'sidoCd': region, 'allFields': 'kept'}
        times[identity] = basic_time
        memberships[identity] = {'01'}
        counts = {'basic': 1, **{code: 1 if code == '01' else 0 for code in MODULE.DEPARTMENTS}}
        scope_times = {code: {'firstFetchedAt': department_time, 'lastFetchedAt': department_time} for code in MODULE.DEPARTMENTS}
        scope_times['basic'] = {'firstFetchedAt': basic_time, 'lastFetchedAt': basic_time}
        region_scopes[region] = {'scopeCounts': counts, 'scopeTimes': scope_times}
    all_counts = {code: count * len(selected) for code, count in counts.items()}
    return data, times, memberships, all_counts, scope_times, region_scopes


class CollectorTests(unittest.TestCase):
    def setUp(self):
        # Large region grids are fixtures; keep their page progress out of test logs.
        progress = patch.object(MODULE, 'emit')
        self.addCleanup(progress.stop)
        progress.start()

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
            data, times, scope_times = MODULE.collect_scope(client, directory, '', 2, 2, '110000')
            self.assertEqual(set(data), {'A', 'B', 'C'})
            self.assertEqual(set(times), set(data))
            self.assertEqual(scope_times['firstFetchedAt'], min(times.values()))
            resumed = FakeClient(client.pages)
            MODULE.collect_scope(resumed, directory, '', 2, 2, '110000')
            self.assertEqual(resumed.requests, [('', 1, 1, '110000')])
            saved = directory / 'regions' / '110000' / 'basic' / 'page-2.json'
            saved.write_text(saved.read_text().replace('fixture-C', 'changed'), encoding='utf-8')
            with self.assertRaisesRegex(MODULE.CollectionError, 'page_checksum_mismatch'):
                MODULE.collect_scope(resumed, directory, '', 2, 2, '110000')
        with tempfile.TemporaryDirectory() as folder:
            duplicate = FakeClient({1: page(1, 2, 3, ['A', 'B']), 2: page(2, 2, 3, ['A'])})
            with self.assertRaisesRegex(MODULE.CollectionError, 'duplicate_hospital_across_pages'):
                MODULE.collect_scope(duplicate, Path(folder), '', 2, 2, '110000')

    def test_empty_department_and_consistently_empty_basic_region_are_valid(self):
        with tempfile.TemporaryDirectory() as folder:
            client = FakeClient({1: page(1, 100, 0, [])})
            self.assertEqual(MODULE.collect_scope(client, Path(folder), '49', 100, 1, '110000')[0], {})
            with patch.object(MODULE.time, 'sleep'):
                self.assertEqual(MODULE.collect_scope(client, Path(folder), '', 100, 1, '110000')[0], {})
            self.assertEqual(len([request for request in client.requests if request[0] == '']), 2)

    def test_changed_final_count_does_not_publish_scope(self):
        class ChangedClient(FakeClient):
            def page(self, code, number, size, region):
                result = super().page(code, number, size, region)
                if size == 1:
                    result['total'] += 1
                return result
        with tempfile.TemporaryDirectory() as folder:
            client = ChangedClient({1: page(1, 2, 2, ['A', 'B'])})
            with self.assertRaisesRegex(MODULE.CollectionError, 'source_total_changed_after_collection'):
                MODULE.collect_scope(client, Path(folder), '', 2, 1, '110000')

    def test_export_has_all_scopes_and_checksum_without_secrets(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            data, times, memberships, counts, scope_times, regions = regional_fixture()
            manifest = MODULE.export_catalog(directory, data, times, memberships, counts, 17, scope_times, regions)
            payload = (directory / 'catalog.jsonl').read_bytes()
            self.assertEqual(manifest['sha256'], hashlib.sha256(payload).hexdigest())
            row = json.loads(payload.splitlines()[0])
            self.assertEqual(row['apiFields'], data['110000-A'])
            self.assertEqual(row['departmentCodes'], ['01'])
            self.assertEqual(row['fetchedAt'], times['110000-A'])
            self.assertEqual(manifest['lastFetchedAt'], scope_times['01']['lastFetchedAt'])
            self.assertEqual(manifest['schemaVersion'], 2)
            self.assertEqual(manifest['regionScopes'], regions)
            self.assertNotIn('serviceKey', payload.decode())

    def test_stale_department_checkpoint_stops_export_even_with_fresh_basic(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            fresh = MODULE.timestamp()
            old = (MODULE.dt.datetime.now(MODULE.dt.timezone.utc) - MODULE.dt.timedelta(hours=49)).isoformat()
            times = {code: {'firstFetchedAt': fresh, 'lastFetchedAt': fresh} for code in ['basic', *MODULE.DEPARTMENTS]}
            times['01'] = {'firstFetchedAt': old, 'lastFetchedAt': old}
            with self.assertRaisesRegex(MODULE.CollectionError, 'stale_or_future_scope_checkpoint'):
                MODULE.export_catalog(directory, {'A': {'ykiho': 'A', 'yadmNm': 'Hospital'}}, {'A': fresh}, {'A': set()}, {}, 0, times, {})
            self.assertFalse((directory / 'catalog-manifest.json').exists())

    def test_request_budget_stops_before_network(self):
        with tempfile.TemporaryDirectory() as folder:
            client = MODULE.Client('fake-never-sent', Path(folder), maximum_calls=1)
            client.calls = 1
            with patch('urllib.request.urlopen') as network:
                with self.assertRaisesRegex(MODULE.CollectionError, 'local_daily_request_budget_reached'):
                    client.page('', 1, 100, '110000')
                network.assert_not_called()


    def test_quota_errors_never_trigger_page_size_fallback(self):
        with tempfile.TemporaryDirectory() as folder:
            client = MODULE.Client('fake-never-sent', Path(folder))
            with patch.object(client, '_request_page', side_effect=MODULE.CollectionError('upstream_call_limit')) as request:
                with self.assertRaisesRegex(MODULE.CollectionError, 'upstream_call_limit'):
                    client.page('', 1, 1000, '110000')
                self.assertEqual(request.call_count, 1)




    def test_auth_quota_and_unknown_results_fail_without_retry_or_secret_output(self):
        for code in ('22', '23', '30', '31', '32', '10', '12', '98', 'fixture-secret', '９９'):
            for gateway in (False, True):
                with self.subTest(code=code, gateway=gateway), tempfile.TemporaryDirectory() as folder:
                    client = MODULE.Client('fixture-secret', Path(folder))
                    expected = 'upstream_call_limit' if code in ('22', '23') else 'upstream_result_not_success'
                    with patch('urllib.request.urlopen', return_value=Response(error_xml(code, gateway))) as network, patch.object(MODULE.time, 'sleep'), patch.object(MODULE, 'emit') as events:
                        with self.assertRaisesRegex(MODULE.CollectionError, expected) as failure:
                            client.page('', 1, 1000, '110000')
                    self.assertEqual(network.call_count, 1)
                    self.assertEqual(client.calls, 1)
                    events.assert_not_called()
                    fields = failure.exception.diagnostic_fields()
                    self.assertEqual(fields, {'reason': expected, **({'resultCode': code} if code.isascii() and code.isdigit() else {})})
                    self.assertNotIn('fixture-secret', json.dumps(fields))
                    self.assertNotIn('example.invalid', json.dumps(fields))

    def test_malformed_success_response_is_not_retried_as_service_failure(self):
        for payload, expected in ((b'not xml', 'invalid_xml'), (b'<response><header><resultCode>00</resultCode></header></response>', 'missing_body')):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as folder:
                client = MODULE.Client('fixture-secret', Path(folder))
                with patch('urllib.request.urlopen', return_value=Response(payload)) as network:
                    with self.assertRaisesRegex(MODULE.CollectionError, expected):
                        client.page('', 1, 1000, '110000')
                self.assertEqual(network.call_count, 1)

    def test_regions_use_separate_checkpoints_and_reject_wrong_region_rows(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            client = GridClient()
            seoul = MODULE.collect_scope(client, directory, '', 2, 2, '110000')[0]
            busan = MODULE.collect_scope(client, directory, '', 2, 2, '210000')[0]
            self.assertEqual(len(seoul), 3)
            self.assertEqual(len(busan), 3)
            self.assertFalse(set(seoul) & set(busan))
            self.assertTrue((directory / 'regions' / '110000' / 'basic' / 'page-2.json').exists())
            self.assertTrue((directory / 'regions' / '210000' / 'basic' / 'page-2.json').exists())
            client.requests.clear()
            MODULE.collect_scope(client, directory, '', 2, 2, '110000')
            self.assertEqual(client.requests, [('', 1, 1, '110000')])
        with tempfile.TemporaryDirectory() as folder:
            wrong_region = FakeClient({1: page(1, 2, 1, ['A'], '210000')})
            with self.assertRaisesRegex(MODULE.CollectionError, 'hospital_region_mismatch'):
                MODULE.collect_scope(wrong_region, Path(folder), '', 2, 1, '110000')

    def test_complete_regional_grid_exports_nationwide_data_without_unfiltered_requests(self):
        class EmptyGwangjuClient(GridClient):
            def page(self, code, number, size, region):
                result = super().page(code, number, size, region)
                if region == '240000':
                    return page(number, size, 0, [], region)
                return result
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE.time, 'sleep'):
            directory, client = Path(folder), EmptyGwangjuClient()
            data, times, memberships, counts, scope_times, regions = MODULE.collect_regional_catalog(client, directory, 2, 2)
            manifest = MODULE.export_catalog(directory, data, times, memberships, counts, client.calls, scope_times, regions)
            self.assertEqual(manifest['schemaVersion'], 2)
            self.assertEqual(manifest['scope'], 'nationwide-with-department-memberships')
            self.assertNotIn('regions', manifest)
            self.assertEqual(manifest['rows'], 48)
            self.assertEqual(manifest['scopeCounts']['01'], 16)
            self.assertEqual(set(regions), set(MODULE.REGIONS))
            self.assertEqual(regions['240000']['scopeCounts']['basic'], 0)
            self.assertEqual(regions['240000']['scopeCounts']['01'], 0)
            self.assertTrue(all(request[3] in MODULE.REGIONS for request in client.requests))
            self.assertEqual(client.requests[-17:], [('', 1, 1, region) for region in MODULE.REGIONS])
            self.assertEqual(len((directory / 'catalog.jsonl').read_text(encoding='utf-8').splitlines()), 48)

    def test_collection_rejects_cross_region_duplicates_and_membership_from_another_region(self):
        class DuplicateClient(GridClient):
            def page(self, code, number, size, region):
                result = super().page(code, number, size, region)
                if not code or code == '01':
                    for row in result['rows']:
                        if row['ykiho'].endswith('-A'):
                            row['ykiho'] = 'same-hospital'
                return result
        class ForeignMembershipClient(GridClient):
            def page(self, code, number, size, region):
                result = super().page(code, number, size, region)
                if code == '01' and region == '210000':
                    result['rows'][0]['ykiho'] = '110000-A'
                return result
        for client, error in [(DuplicateClient(), 'duplicate_hospital_across_regions'),
                              (ForeignMembershipClient(), 'department_hospital_missing_from_regional_basic_snapshot')]:
            with tempfile.TemporaryDirectory() as folder:
                with self.assertRaisesRegex(MODULE.CollectionError, error):
                    MODULE.collect_regional_catalog(client, Path(folder), 2, 2)
                self.assertFalse((Path(folder) / 'catalog-manifest.json').exists())

    def test_export_refuses_missing_region_or_wrong_region_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            data, times, memberships, counts, scope_times, regions = regional_fixture()
            incomplete = dict(regions)
            incomplete.pop('410000')
            with self.assertRaisesRegex(MODULE.CollectionError, 'incomplete_region_scopes'):
                MODULE.export_catalog(Path(folder), data, times, memberships, counts, 0, scope_times, incomplete)
            regions['110000']['scopeCounts']['basic'] += 1
            with self.assertRaisesRegex(MODULE.CollectionError, 'incomplete_region_scopes'):
                MODULE.export_catalog(Path(folder), data, times, memberships, counts, 0, scope_times, regions)
            self.assertFalse((Path(folder) / 'catalog-manifest.json').exists())

    def test_final_regional_total_change_prevents_publication(self):
        class ChangedClient(GridClient):
            def page(self, code, number, size, region):
                result = super().page(code, number, size, region)
                if code == '' and region == '110000' and size == 1:
                    previous = [request for request in self.requests if request == ('', 1, 1, region)]
                    if len(previous) > 1:
                        result['total'] += 1
                return result
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(MODULE.CollectionError, 'regional_total_changed_during_classification'):
                MODULE.collect_regional_catalog(ChangedClient(), Path(folder), 2, 2)
            self.assertFalse((Path(folder) / 'catalog-manifest.json').exists())

    def test_zero_total_for_all_regions_stops_export(self):
        class EmptyClient(GridClient):
            def page(self, code, number, size, region):
                super().page(code, number, size, region)
                return page(number, size, 0, [], region)
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE.time, 'sleep'):
            with self.assertRaisesRegex(MODULE.CollectionError, 'empty_regional_catalog'):
                MODULE.collect_regional_catalog(EmptyClient(), Path(folder), 2, 2)

    def test_cached_basic_rows_are_partitioned_and_validated_by_region(self):
        with tempfile.TemporaryDirectory() as folder:
            client = GridClient()
            cached_rows, cached_times = {}, {}
            for region in MODULE.REGIONS:
                for row in page(1, 3, 3, [region + '-A', region + '-B', region + '-C'], region)['rows']:
                    cached_rows[row['ykiho']] = row
                    cached_times[row['ykiho']] = MODULE.timestamp()
            with patch.object(MODULE, 'import_basic_cache', return_value=(cached_rows, cached_times)):
                result = MODULE.collect_regional_catalog(client, Path(folder), 2, 2, Path('unused-fixture-cache'))
            self.assertEqual(result[0], cached_rows)
            self.assertEqual(len([request for request in client.requests if not request[0]]), 34)
            self.assertTrue(all(request[2] == 1 for request in client.requests if not request[0]))


    def test_invalid_explicit_region_selection_fails_before_any_request_or_file(self):
        invalid = ([], [''], ['110000', '110000'], ['990000'], ['110000', '990000'], '110000', [None], [[]])
        with tempfile.TemporaryDirectory() as folder:
            directory, client = Path(folder), GridClient()
            for regions in invalid:
                with self.subTest(regions=regions), self.assertRaisesRegex(MODULE.CollectionError, 'invalid_region_selection'):
                    MODULE.collect_regional_catalog(client, directory, 2, 2, regions=regions)
            self.assertEqual(client.requests, [])
            self.assertEqual(list(directory.iterdir()), [])
            for value in ('', '110000,', '110000,110000', '110000,990000'):
                arguments = ['collect-hospital-catalog.py', '--directory', folder, '--regions', value, '--day', 'monday', '--week-start', '2026-11-23']
                with self.subTest(value=value), patch.object(MODULE.sys, 'argv', arguments), patch.object(MODULE, 'Client') as create_client:
                    with self.assertRaisesRegex(MODULE.CollectionError, 'invalid_region_selection'):
                        MODULE.main()
                    create_client.assert_not_called()
            self.assertEqual(list(directory.iterdir()), [])

    def test_explicit_all_regions_uses_scoped_schema_and_canonical_order(self):
        with tempfile.TemporaryDirectory() as folder:
            data, times, memberships, counts, scope_times, region_scopes = regional_fixture()
            manifest = MODULE.export_catalog(Path(folder), data, times, memberships, counts, 0, scope_times,
                                             region_scopes, regions=list(reversed(MODULE.REGIONS)))
            self.assertEqual(manifest['schemaVersion'], 3)
            self.assertEqual(manifest['regions'], list(MODULE.REGIONS))
            self.assertEqual(manifest['rows'], 17)

    def test_empty_selected_region_exports_valid_zero_rows_but_empty_nation_is_rejected(self):
        class EmptyClient(GridClient):
            def page(self, code, number, size, region):
                super().page(code, number, size, region)
                return page(number, size, 0, [], region)
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE.time, 'sleep'):
            directory, client = Path(folder), EmptyClient()
            values = MODULE.collect_regional_catalog(client, directory, 2, 2, regions=['240000'])
            data, times, memberships, counts, scope_times, region_scopes = values
            manifest = MODULE.export_catalog(directory, data, times, memberships, counts, client.calls,
                                             scope_times, region_scopes, regions=['240000'])
            self.assertEqual(manifest['rows'], 0)
            self.assertEqual(manifest['regions'], ['240000'])
            self.assertEqual(set(manifest['scopeCounts'].values()), {0})
            self.assertEqual((directory / 'catalog.jsonl').read_bytes(), b'')
            self.assertEqual(manifest['sha256'], hashlib.sha256(b'').hexdigest())
            self.assertEqual(set(request[3] for request in client.requests), {'240000'})
        with tempfile.TemporaryDirectory() as folder, patch.object(MODULE.time, 'sleep'):
            with self.assertRaisesRegex(MODULE.CollectionError, 'empty_regional_catalog'):
                MODULE.collect_regional_catalog(EmptyClient(), Path(folder), 2, 2, regions=list(MODULE.REGIONS))
            empty_scopes = {region: {'scopeCounts': counts, 'scopeTimes': scope_times} for region in MODULE.REGIONS}
            with self.assertRaisesRegex(MODULE.CollectionError, 'incomplete_region_scopes'):
                MODULE.export_catalog(Path(folder), {}, {}, {}, counts, 0, scope_times, empty_scopes, regions=list(MODULE.REGIONS))
            self.assertFalse((Path(folder) / 'catalog-manifest.json').exists())

    def test_selected_export_rejects_extra_region_foreign_rows_and_inconsistent_counts(self):
        selected = ['110000', '210000']
        for corruption, reason in (
            ('extra_region', 'incomplete_region_scopes'), ('missing_region', 'incomplete_region_scopes'),
            ('foreign_row', 'invalid_regional_hospital_identity'), ('regional_count', 'incomplete_region_scopes'),
            ('total_count', 'regional_scope_summary_mismatch'), ('extra_count', 'regional_scope_summary_mismatch'),
        ):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as folder:
                data, times, memberships, counts, scope_times, region_scopes = regional_fixture(selected)
                if corruption == 'extra_region':
                    region_scopes['310000'] = region_scopes['110000']
                elif corruption == 'missing_region':
                    region_scopes.pop('210000')
                elif corruption == 'foreign_row':
                    data['110000-A']['sidoCd'] = '310000'
                elif corruption == 'regional_count':
                    region_scopes['110000']['scopeCounts']['basic'] += 1
                elif corruption == 'total_count':
                    counts['basic'] += 1
                else:
                    counts['unknown'] = 0
                with self.assertRaisesRegex(MODULE.CollectionError, reason):
                    MODULE.export_catalog(Path(folder), data, times, memberships, counts, 0, scope_times,
                                          region_scopes, regions=selected)
                self.assertFalse((Path(folder) / 'catalog-manifest.json').exists())

    def test_selected_cache_ignores_unselected_valid_rows_and_only_confirms_selected_totals(self):
        with tempfile.TemporaryDirectory() as folder:
            client, selected = GridClient(), ['110000', '310000']
            cached_rows, cached_times = {}, {}
            for region in MODULE.REGIONS:
                for row in page(1, 3, 3, [region + '-A', region + '-B', region + '-C'], region)['rows']:
                    cached_rows[row['ykiho']] = row
                    cached_times[row['ykiho']] = MODULE.timestamp()
            with patch.object(MODULE, 'import_basic_cache', return_value=(cached_rows, cached_times)):
                result = MODULE.collect_regional_catalog(client, Path(folder), 2, 2, Path('unused-fixture-cache'), regions=selected)
            self.assertEqual(set(result[0]), {region + '-' + suffix for region in selected for suffix in ('A', 'B', 'C')})
            self.assertEqual(set(result[1]), set(result[0]))
            self.assertEqual(set(result[2]), set(result[0]))
            self.assertEqual(set(request[3] for request in client.requests), set(selected))
            self.assertEqual(len([request for request in client.requests if not request[0]]), 4)
            self.assertTrue(all(request[2] == 1 for request in client.requests if not request[0]))

    def test_network_request_keeps_both_region_and_department_filters(self):
        payload = b'<response><header><resultCode>00</resultCode></header><body><totalCount>0</totalCount><pageNo>1</pageNo><numOfRows>1</numOfRows><items/></body></response>'
        class Response:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self, size):
                return payload
        with tempfile.TemporaryDirectory() as folder:
            client = MODULE.Client('fixture-key', Path(folder))
            with patch('urllib.request.urlopen', return_value=Response()) as network:
                client.page('05', 1, 1, '210000')
                query = MODULE.urllib.parse.parse_qs(MODULE.urllib.parse.urlparse(network.call_args.args[0]).query)
                self.assertEqual(query['sidoCd'], ['210000'])
                self.assertEqual(query['dgsbjtCd'], ['05'])
            with patch('urllib.request.urlopen') as network:
                with self.assertRaisesRegex(MODULE.CollectionError, 'invalid_region_code'):
                    client.page('', 1, 1000, '')
                network.assert_not_called()


if __name__ == '__main__':
    unittest.main()
