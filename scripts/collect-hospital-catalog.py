"""Collect complete HIRA basic catalogues for selected regions, without detailed-information APIs."""

import argparse
import concurrent.futures
import contextlib
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


ENDPOINT = 'https://apis.data.go.kr/B551182/hospInfoServicev2/getHospBasisList'
DEPARTMENTS = {'01': '내과', '02': '신경과', '03': '정신건강의학과', '04': '외과',
               '05': '정형외과', '06': '신경외과', '10': '산부인과', '11': '소아청소년과',
               '12': '안과', '13': '이비인후과', '14': '피부과', '15': '비뇨의학과',
               '21': '재활의학과', '23': '가정의학과', '49': '치과', '80': '한방내과'}
# HIRA province codes, not the two-digit administrative address codes.
REGIONS = {'110000': '서울', '210000': '부산', '220000': '인천', '230000': '대구',
           '240000': '광주', '250000': '대전', '260000': '울산', '310000': '경기',
           '320000': '강원', '330000': '충북', '340000': '충남', '350000': '전북',
           '360000': '전남광주', '370000': '경북', '380000': '경남', '390000': '제주',
           '410000': '세종'}
KST = dt.timezone(dt.timedelta(hours=9))
TRANSIENT_RESULT_CODES = frozenset(('01', '02', '04', '05', '99'))
DAY_REGIONS = {
    'monday': ('110000',), 'tuesday': ('310000',),
    'wednesday': ('210000', '360000', '380000'),
    'thursday': ('220000', '230000', '370000'),
    'friday': ('250000', '340000', '350000'),
    'saturday': ('240000', '260000', '320000', '330000', '390000', '410000'),
}


class CollectionError(Exception):
    """Only fixed, credential-free error identifiers may be put in this exception."""

    def __init__(self, reason, result_code=None):
        super().__init__(reason)
        # Upstream messages can contain request details. Keep only a two-digit
        # ASCII result code for diagnostics, never the raw message or URL.
        self.result_code = result_code if isinstance(result_code, str) and len(result_code) == 2 and all('0' <= char <= '9' for char in result_code) else None

    def diagnostic_fields(self):
        return {'reason': str(self), **({'resultCode': self.result_code} if self.result_code is not None else {})}


def normalize_regions(regions=None):
    if regions is None:
        return tuple(REGIONS)
    if not isinstance(regions, (list, tuple)) or not regions or any(not isinstance(region, str) or region not in REGIONS for region in regions):
        raise CollectionError('invalid_region_selection')
    if len(set(regions)) != len(regions):
        raise CollectionError('invalid_region_selection')
    return tuple(region for region in REGIONS if region in regions)


def utc_now():
    return dt.datetime.now(dt.timezone.utc)


def timestamp():
    return utc_now().isoformat()


def scope_time_range(values, maximum_age_hours=48):
    try:
        dates = [dt.datetime.fromisoformat(value) for value in values]
        if not dates or any(value.tzinfo is None for value in dates):
            raise ValueError()
    except (ValueError, TypeError):
        raise CollectionError('invalid_scope_time') from None
    first, last = min(dates), max(dates)
    now = utc_now()
    if now - first > dt.timedelta(hours=maximum_age_hours) or last - now > dt.timedelta(minutes=5):
        raise CollectionError('stale_or_future_scope_checkpoint')
    return {'firstFetchedAt': first.isoformat(), 'lastFetchedAt': last.isoformat()}


def emit(event, **values):
    print(json.dumps({'event': event, **values}, ensure_ascii=True), flush=True)


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('wb') as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    return hashlib.sha256(payload).hexdigest()


@contextlib.contextmanager
def directory_lock(directory):
    directory.mkdir(parents=True, exist_ok=True)
    stream = (directory / 'collector.lock').open('a+b')
    stream.seek(0, os.SEEK_END)
    if stream.tell() == 0:
        stream.write(b'0')
        stream.flush()
    stream.seek(0)
    try:
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        stream.close()
        raise CollectionError('collection_already_running') from None
    try:
        yield
    finally:
        stream.close()


def positive_integer(text, name, allow_zero=False):
    if not isinstance(text, str) or not text.strip().isdigit():
        raise CollectionError('invalid_' + name)
    value = int(text.strip())
    if value < (0 if allow_zero else 1):
        raise CollectionError('invalid_' + name)
    return value


def parse_page(payload, page, size):
    if len(payload) > 8 * 1024 * 1024 or b'<!DOCTYPE' in payload.upper() or b'<!ENTITY' in payload.upper():
        raise CollectionError('invalid_xml_size_or_declaration')
    try:
        root = ET.fromstring(payload)
    except ET.ParseError:
        raise CollectionError('invalid_xml') from None
    code = (root.findtext('.//resultCode') or root.findtext('.//returnReasonCode') or '').strip()
    if code in ('22', '23'):
        raise CollectionError('upstream_call_limit', code)
    if code in TRANSIENT_RESULT_CODES:
        # An HTTP 200 service error is still a failed request, never an empty page.
        raise CollectionError('upstream_service_unavailable', code)
    if code != '00':
        raise CollectionError('upstream_result_not_success', code)
    body = root.find('.//body')
    if body is None:
        raise CollectionError('missing_body')
    total = positive_integer(body.findtext('totalCount'), 'total', True)
    if body.findtext('pageNo') and positive_integer(body.findtext('pageNo'), 'page') != page:
        raise CollectionError('page_number_mismatch')
    reported_size = positive_integer(body.findtext('numOfRows'), 'page_size') if body.findtext('numOfRows') else size
    rows = []
    for item in body.findall('./items/item'):
        row = {}
        for field in item:
            if len(field) or field.tag in row or '.' in field.tag or '$' in field.tag:
                raise CollectionError('invalid_raw_field')
            row[field.tag] = field.text or ''
        if not row.get('ykiho') or row['ykiho'].strip() != row['ykiho'] or not row.get('yadmNm', '').strip():
            raise CollectionError('missing_hospital_identity')
        rows.append(row)
    if len({row['ykiho'] for row in rows}) != len(rows):
        raise CollectionError('duplicate_hospital_in_page')
    return {'page': page, 'requestedSize': size, 'reportedSize': reported_size,
            'total': total, 'fetchedAt': timestamp(), 'rows': rows}


class Client:
    def __init__(self, key, directory, timeout=120, maximum_calls=10000):
        self.key = urllib.parse.unquote(key)
        self.directory = directory
        self.timeout = timeout
        self.maximum_calls = maximum_calls
        self.lock = threading.Lock()
        self.cancel = threading.Event()
        self.last_request = 0.0
        self.budget_file = directory / 'request-budget.json'
        self.budget_date = dt.datetime.now(KST).date().isoformat()
        self.calls = 0
        if self.budget_file.exists():
            saved = json.loads(self.budget_file.read_text(encoding='utf-8'))
            if saved.get('dateKst') == self.budget_date:
                self.calls = int(saved.get('calls', 0))

    def page(self, code, number, size, region):
        if region not in REGIONS:
            raise CollectionError('invalid_region_code')
        return self._request_page(code, number, size, region)

    def _request_page(self, code, number, size, region):
        if region not in REGIONS:
            raise CollectionError('invalid_region_code')
        parameters = {'serviceKey': self.key, 'pageNo': number, 'numOfRows': size, 'sidoCd': region}
        if code:
            parameters['dgsbjtCd'] = code
        url = ENDPOINT + '?' + urllib.parse.urlencode(parameters)
        with self.lock:
            if self.cancel.is_set():
                raise CollectionError('collection_canceled')
            if self.calls >= self.maximum_calls:
                raise CollectionError('local_daily_request_budget_reached')
            time.sleep(max(0, .5 - (time.monotonic() - self.last_request)))
            self.last_request = time.monotonic()
            self.calls += 1
            atomic_json(self.budget_file, {'dateKst': self.budget_date, 'calls': self.calls})
        try:
            with urllib.request.urlopen(url, timeout=self.timeout) as response:
                if response.status != 200:
                    raise CollectionError('unexpected_http_status')
                payload = response.read(8 * 1024 * 1024 + 1)
            return parse_page(payload, number, size)
        except urllib.error.HTTPError as error:
            reason = 'upstream_http_rejected' if error.code < 500 else 'upstream_transport_failed'
            error.close()
            raise CollectionError(reason) from None
        except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError):
            raise CollectionError('upstream_transport_failed') from None


def validate_page(document, page, size, total, region=None):
    if document.get('page') != page or document.get('requestedSize') != size or document.get('reportedSize') != size:
        raise CollectionError('page_parameters_mismatch')
    if document.get('total') != total:
        raise CollectionError('source_total_changed')
    expected_rows = max(0, min(size, total - (page - 1) * size))
    if len(document.get('rows', [])) != expected_rows:
        raise CollectionError('page_row_count_mismatch')
    identities = set()
    for row in document['rows']:
        if not isinstance(row, dict) or not row.get('ykiho') or not row.get('yadmNm'):
            raise CollectionError('invalid_saved_row')
        if any(not isinstance(key, str) or not isinstance(value, str) for key, value in row.items()):
            raise CollectionError('invalid_saved_fields')
        if region is not None and row.get('sidoCd') != region:
            raise CollectionError('hospital_region_mismatch')
        if row['ykiho'] in identities:
            raise CollectionError('duplicate_hospital_in_page')
        identities.add(row['ykiho'])


def collect_scope(client, directory, code, initial_size, workers, region):
    if region not in REGIONS:
        raise CollectionError('invalid_region_code')
    scope = code or 'basic'
    folder = directory / 'regions' / region / scope
    folder.mkdir(parents=True, exist_ok=True)
    manifest_path = folder / 'manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        if manifest.get('scope') != scope or manifest.get('region') != region:
            raise CollectionError('scope_mismatch')
    else:
        first = client.page(code, 1, initial_size, region)
        size = initial_size
        validate_page(first, 1, size, first['total'], region)
        manifest = {'region': region, 'scope': scope, 'size': size, 'total': first['total'], 'pages': {}, 'startedAt': timestamp()}
        atomic_json(manifest_path, manifest)
        path = folder / 'page-1.json'
        manifest['pages']['1'] = atomic_json(path, first)
        atomic_json(manifest_path, manifest)
    size, total = manifest['size'], manifest['total']
    page_count = max(1, math.ceil(total / size))
    data = {}
    times = {}
    observed_times = []
    missing = []
    for page in range(1, page_count + 1):
        path = folder / f'page-{page}.json'
        if not path.exists():
            missing.append(page)
            continue
        payload = path.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        if str(page) in manifest['pages'] and manifest['pages'][str(page)] != digest:
            raise CollectionError('page_checksum_mismatch')
        document = json.loads(payload)
        validate_page(document, page, size, total, region)
        observed_times.append(document['fetchedAt'])
        for row in document['rows']:
            if row['ykiho'] in data:
                raise CollectionError('duplicate_hospital_across_pages')
            data[row['ykiho']] = row
            times[row['ykiho']] = document['fetchedAt']
        manifest['pages'][str(page)] = digest
    atomic_json(manifest_path, manifest)
    iterator = iter(missing)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
    pending = {}
    try:
        for _ in range(workers):
            page = next(iterator, None)
            if page is not None:
                pending[pool.submit(client.page, code, page, size, region)] = page
        while pending:
            finished, _ = concurrent.futures.wait(pending, return_when=concurrent.futures.FIRST_COMPLETED)
            for future in finished:
                page = pending.pop(future)
                document = future.result()
                validate_page(document, page, size, total, region)
                observed_times.append(document['fetchedAt'])
                for row in document['rows']:
                    if row['ykiho'] in data:
                        raise CollectionError('duplicate_hospital_across_pages')
                    data[row['ykiho']] = row
                    times[row['ykiho']] = document['fetchedAt']
                manifest['pages'][str(page)] = atomic_json(folder / f'page-{page}.json', document)
                atomic_json(manifest_path, manifest)
                emit('page_saved', region=region, scope=scope, page=page, pages=page_count, rows=len(data), total=total)
            while len(pending) < workers:
                page = next(iterator, None)
                if page is None:
                    break
                pending[pool.submit(client.page, code, page, size, region)] = page
    except BaseException:
        client.cancel.set()
        for future in pending:
            future.cancel()
        raise
    finally:
        pool.shutdown(wait=True, cancel_futures=True)
    if len(data) != total:
        raise CollectionError('scope_count_mismatch')
    scope_times = scope_time_range(observed_times)
    # A changed source total makes deletion unsafe; keep the last good DB instead.
    confirmation = client.page(code, 1, 1, region)
    if confirmation['total'] != total:
        raise CollectionError('source_total_changed_after_collection')
    validate_page(confirmation, 1, 1, total, region)
    emit('scope_complete', region=region, scope=scope, rows=total)
    return data, times, scope_times


def import_basic_cache(folder):
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    audit = json.loads((folder / 'summary.json').read_text(encoding='utf-8'))
    payload = (folder / 'hospitals.jsonl').read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if manifest.get('status') != 'complete' or not audit.get('complete') or audit.get('duplicateRows') != 0 or audit.get('missingPages'):
        raise CollectionError('basic_cache_incomplete')
    if digest != audit.get('sha256') or digest != manifest.get('exportSha256'):
        raise CollectionError('basic_cache_checksum_mismatch')
    if manifest.get('scope') != 'nationwide-no-filters':
        raise CollectionError('basic_cache_not_nationwide')
    age = dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(audit['firstFetchedAt'])
    if age.total_seconds() < -300 or age > dt.timedelta(hours=24):
        raise CollectionError('basic_cache_too_old')
    data, times = {}, {}
    for index, line in enumerate(payload.decode('utf-8').splitlines()):
        row = json.loads(line)
        if not row.get('ykiho') or row['ykiho'] in data:
            raise CollectionError('basic_cache_invalid_identity')
        data[row['ykiho']] = row
        page = index // manifest['pageSize'] + 1
        times[row['ykiho']] = manifest['pages'][str(page)]['fetchedAt']
    if len(data) != audit['expectedTotal'] or len(data) != audit['uniqueHospitals']:
        raise CollectionError('basic_cache_count_mismatch')
    emit('basic_cache_reused', rows=len(data))
    return data, times


def collect_regional_catalog(client, directory, page_size, workers, basic_cache=None, regions=None):
    selected = normalize_regions(regions)
    data, times, memberships, region_scopes = {}, {}, {}, {}
    cached_regions = None
    if basic_cache is not None:
        cached_data, cached_times = import_basic_cache(basic_cache)
        cached_regions = {region: {} for region in selected}
        for identity, row in cached_data.items():
            region = row.get('sidoCd')
            if region not in REGIONS:
                raise CollectionError('basic_cache_invalid_region')
            if region in cached_regions:
                cached_regions[region][identity] = row
        if not cached_data:
            raise CollectionError('empty_regional_catalog')

    for completed_regions, region in enumerate(selected, 1):
        emit('region_started', region=region, name=REGIONS[region], completed=completed_regions - 1, total=len(selected))
        if cached_regions is None:
            basic, fetched, basic_times = collect_scope(client, directory, '', page_size, workers, region)
        else:
            basic = cached_regions[region]
            fetched = {identity: cached_times[identity] for identity in basic}
            confirmation = client.page('', 1, 1, region)
            if confirmation['total'] != len(basic):
                raise CollectionError('basic_cache_source_total_changed')
            validate_page(confirmation, 1, 1, len(basic), region)
            basic_times = scope_time_range(fetched.values() if fetched else [confirmation['fetchedAt']])
        if any(identity in data for identity in basic):
            raise CollectionError('duplicate_hospital_across_regions')
        data.update(basic)
        times.update(fetched)
        memberships.update({identity: set() for identity in basic})
        region_counts = {'basic': len(basic)}
        region_times = {'basic': basic_times}
        for code in DEPARTMENTS:
            department_rows, _, department_times = collect_scope(client, directory, code, page_size, workers, region)
            for identity in department_rows:
                if identity not in basic:
                    raise CollectionError('department_hospital_missing_from_regional_basic_snapshot')
                memberships[identity].add(code)
            region_counts[code] = len(department_rows)
            region_times[code] = department_times
        region_scopes[region] = {'scopeCounts': region_counts, 'scopeTimes': region_times}
        emit('region_complete', region=region, name=REGIONS[region], completed=completed_regions,
             total=len(selected), rows=len(basic), collectedRows=len(data))

    if not data and len(selected) == len(REGIONS):
        raise CollectionError('empty_regional_catalog')
    # Never reintroduce an unfiltered national count query for final validation.
    for region, summary in region_scopes.items():
        confirmation = client.page('', 1, 1, region)
        expected = summary['scopeCounts']['basic']
        if confirmation['total'] != expected:
            raise CollectionError('regional_total_changed_during_classification')
        validate_page(confirmation, 1, 1, expected, region)
    scopes = ['basic', *DEPARTMENTS]
    scope_counts = {code: sum(summary['scopeCounts'][code] for summary in region_scopes.values()) for code in scopes}
    scope_times = {code: scope_time_range([value for summary in region_scopes.values()
                                         for value in summary['scopeTimes'][code].values()]) for code in scopes}
    return data, times, memberships, scope_counts, scope_times, region_scopes


def export_catalog(directory, data, times, memberships, scope_counts, calls, scope_times, region_scopes, regions=None, recovery=None):
    selected = normalize_regions(regions)
    maximum_age_hours = 48
    completed_at = timestamp()
    if recovery is not None:
        validate_recovery(recovery, selected)
        maximum_age_hours = 8 * 24
        completed_at = recovery['recoveredAt']
    if set(scope_times) != {'basic', *DEPARTMENTS}:
        raise CollectionError('incomplete_scope_times')
    checked_times = {}
    for code, values in scope_times.items():
        checked_times[code] = scope_time_range([values['firstFetchedAt'], values['lastFetchedAt']], maximum_age_hours)
    all_times = scope_time_range([value for timespan in checked_times.values() for value in timespan.values()], maximum_age_hours)
    if recovery is not None:
        week_start = week_start_date(recovery['weekStartKst'])
        start = dt.datetime.combine(week_start, dt.time(), KST)
        if dt.datetime.fromisoformat(all_times['firstFetchedAt']) < start or dt.datetime.fromisoformat(all_times['lastFetchedAt']) >= start + dt.timedelta(days=7):
            raise CollectionError('recovery_time_outside_week')
    if set(region_scopes) != set(selected) or (not data and len(selected) == len(REGIONS)):
        raise CollectionError('incomplete_region_scopes')
    if set(scope_counts) != set(checked_times):
        raise CollectionError('regional_scope_summary_mismatch')
    actual_counts = {region: {code: 0 for code in checked_times} for region in selected}
    for identity, row in data.items():
        region = row.get('sidoCd')
        if region not in selected or identity != row.get('ykiho'):
            raise CollectionError('invalid_regional_hospital_identity')
        actual_counts[region]['basic'] += 1
        for code in memberships[identity]:
            if code not in DEPARTMENTS:
                raise CollectionError('invalid_department_membership')
            actual_counts[region][code] += 1
    for region, summary in region_scopes.items():
        if set(summary.get('scopeTimes', {})) != set(checked_times) or summary.get('scopeCounts') != actual_counts[region]:
            raise CollectionError('incomplete_region_scopes')
        for values in summary['scopeTimes'].values():
            scope_time_range([values['firstFetchedAt'], values['lastFetchedAt']], maximum_age_hours)
    for code in checked_times:
        total = sum(summary['scopeCounts'][code] for summary in region_scopes.values())
        combined_times = scope_time_range([value for summary in region_scopes.values()
                                          for value in summary['scopeTimes'][code].values()], maximum_age_hours)
        if total != scope_counts.get(code) or combined_times != checked_times[code]:
            raise CollectionError('regional_scope_summary_mismatch')
    destination = directory / 'catalog.jsonl'
    temporary = directory / 'catalog.jsonl.tmp'
    digest = hashlib.sha256()
    with temporary.open('wb') as stream:
        for identity in sorted(data):
            row = {'apiFields': data[identity], 'departmentCodes': sorted(memberships[identity]), 'fetchedAt': times[identity]}
            encoded = (json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
            stream.write(encoded)
            digest.update(encoded)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, destination)
    manifest = {'schemaVersion': 4 if recovery is not None else 2 if regions is None else 3, 'complete': True, 'source': 'hospInfoServicev2/getHospBasisList',
                'scope': 'nationwide-with-department-memberships' if regions is None else 'regions-with-department-memberships',
                **({'regions': list(selected)} if regions is not None else {}), 'rows': len(data),
                'departments': DEPARTMENTS, 'scopeCounts': scope_counts, 'requestCount': calls,
                **all_times, 'scopeTimes': checked_times, 'regionScopes': region_scopes,
                **({'recovery': recovery} if recovery is not None else {}),
                'completedAt': completed_at, 'sha256': digest.hexdigest()}
    atomic_json(directory / 'catalog-manifest.json', manifest)
    emit('catalog_complete', rows=len(data), requests=calls, sha256=manifest['sha256'])
    return manifest


def week_start_date(value):
    try:
        date = dt.date.fromisoformat(value)
        if date.isoformat() != value or date.weekday() != 0:
            raise ValueError()
        return date
    except (ValueError, TypeError):
        raise CollectionError('invalid_collection_week') from None


def validate_recovery(recovery, regions):
    if not isinstance(recovery, dict) or set(recovery) != {'mode', 'weekStartKst', 'originalDay', 'attempt', 'recoveredAt'}:
        raise CollectionError('invalid_recovery_metadata')
    if recovery['mode'] != 'sunday-failed-requests' or type(recovery['attempt']) is not int or recovery['attempt'] != 1 or recovery['originalDay'] not in DAY_REGIONS:
        raise CollectionError('invalid_recovery_metadata')
    if any(region not in DAY_REGIONS[recovery['originalDay']] for region in regions):
        raise CollectionError('invalid_recovery_regions')
    start = week_start_date(recovery['weekStartKst'])
    try:
        recovered = dt.datetime.fromisoformat(recovery['recoveredAt'])
        if recovered.tzinfo is None or recovered.astimezone(KST).date() != start + dt.timedelta(days=6):
            raise ValueError()
    except (TypeError, ValueError):
        raise CollectionError('invalid_recovery_date') from None


def request_key(region, scope, page, size):
    return {'region': region, 'scope': scope, 'page': page, 'size': size}


def document_checksum(document):
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()


class WeeklyBatch:
    """Persist each query once; Sunday recovery may consume failed queries once."""

    def __init__(self, client, directory, regions, day, week_start, page_size=500, mode='collect'):
        self.client, self.directory = client, directory
        self.regions = normalize_regions(regions)
        self.day, self.week_start, self.page_size, self.mode = day, week_start, page_size, mode
        self.starting_calls = client.calls
        self.missing_requests = {}
        start = week_start_date(week_start)
        if day not in DAY_REGIONS or any(region not in DAY_REGIONS[day] for region in self.regions):
            raise CollectionError('invalid_batch_regions')
        if mode not in ('collect', 'retry') or not 1 <= page_size <= 1000:
            raise CollectionError('invalid_batch_configuration')
        if not start <= utc_now().astimezone(KST).date() < start + dt.timedelta(days=7):
            raise CollectionError('batch_outside_collection_week')
        if mode == 'collect' and utc_now().astimezone(KST).weekday() == 6:
            raise CollectionError('collect_not_allowed_on_sunday')
        if mode == 'retry' and utc_now().astimezone(KST).date() != start + dt.timedelta(days=6):
            raise CollectionError('recovery_requires_same_week_sunday')
        metadata = {'schemaVersion': 1, 'sourceMode': 'collect', 'weekStartKst': week_start,
                    'originalDay': day, 'regions': list(self.regions), 'pageSize': page_size}
        path = directory / 'batch.json'
        if path.exists():
            existing = json.loads(path.read_text(encoding='utf-8'))
            if any(existing.get(key) != value for key, value in metadata.items()):
                raise CollectionError('batch_metadata_mismatch')
        elif mode == 'retry':
            raise CollectionError('missing_recovery_batch')
        else:
            atomic_json(path, {**metadata, 'createdAt': timestamp()})

    def path(self, key):
        if key.get('region') not in self.regions or key.get('scope') not in ('basic', *DEPARTMENTS):
            raise CollectionError('invalid_request_checkpoint')
        if type(key.get('page')) is not int or key['page'] < 1 or type(key.get('size')) is not int or key['size'] not in (1, self.page_size):
            raise CollectionError('invalid_request_checkpoint')
        return self.directory / 'requests' / key['region'] / key['scope'] / f"page-{key['page']}-size-{key['size']}.json"

    def read(self, key):
        path = self.path(key)
        if not path.exists():
            return None
        value = json.loads(path.read_text(encoding='utf-8'))
        if any(value.get(field) != item for field, item in key.items()) or value.get('status') not in ('claimed', 'success', 'failed', 'skipped') or type(value.get('attempt')) is not int or value['attempt'] not in (0, 1):
            raise CollectionError('invalid_request_checkpoint')
        if value['status'] == 'success':
            document = value.get('document')
            if not isinstance(document, dict) or value.get('documentSha256') != document_checksum(document):
                raise CollectionError('request_checkpoint_checksum_mismatch')
            validate_page(document, key['page'], key['size'], document.get('total'), key['region'])
        return value

    def fetch(self, key, retry=False):
        previous = self.read(key)
        if retry:
            if not previous or previous['status'] != 'failed' or previous['attempt'] != 0:
                return previous
        elif previous is not None:
            return previous
        attempt = 1 if retry else 0
        # A crash after this write spends the attempt; a rerun must not send it again.
        value = {**key, 'attempt': attempt, 'status': 'claimed'}
        atomic_json(self.path(key), value)
        today, week_start = utc_now().astimezone(KST).date(), week_start_date(self.week_start)
        allowed = today == week_start + dt.timedelta(days=6) if retry else week_start <= today < week_start + dt.timedelta(days=6)
        if not allowed:
            value.update(status='skipped', reason='outside_request_window')
            atomic_json(self.path(key), value)
            emit('request_skipped', **key, reason='outside_request_window')
            return value
        before_calls = self.client.calls
        try:
            document = self.client.page('' if key['scope'] == 'basic' else key['scope'], key['page'], key['size'], key['region'])
            validate_page(document, key['page'], key['size'], document['total'], key['region'])
        except CollectionError as error:
            value.update(status='skipped' if retry or self.client.calls == before_calls else 'failed', **error.diagnostic_fields())
        except Exception:
            value.update(status='skipped' if retry or self.client.calls == before_calls else 'failed', reason='collection_request_failed')
        else:
            value.update(status='success', document=document, documentSha256=document_checksum(document))
        atomic_json(self.path(key), value)
        if value['status'] != 'success':
            emit('request_deferred' if value['status'] == 'failed' else 'request_skipped', **key, reason=value.get('reason', 'request_interrupted'),
                 **({'resultCode': value['resultCode']} if 'resultCode' in value else {}))
        return value

    def checkpoints(self):
        for path in sorted((self.directory / 'requests').glob('*/*/*.json')):
            value = json.loads(path.read_text(encoding='utf-8'))
            key = {field: value.get(field) for field in ('region', 'scope', 'page', 'size')}
            if self.path(key) != path:
                raise CollectionError('invalid_request_checkpoint')
            yield self.read(key)

    def collect(self, workers):
        for region in self.regions:
            emit('region_started', region=region, name=REGIONS[region])
            for scope in ('basic', *DEPARTMENTS):
                first = self.fetch(request_key(region, scope, 1, self.page_size))
                if first['status'] != 'success':
                    # The count query is independently planned for every scope,
                    # not a retry or replacement for the failed first data page.
                    confirmation = self.fetch(request_key(region, scope, 1, 1))
                    if confirmation['status'] != 'success':
                        continue
                    total = confirmation['document']['total']
                else:
                    total = first['document']['total']
                keys = [request_key(region, scope, page, self.page_size) for page in range(2, math.ceil(total / self.page_size) + 1)]
                with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
                    list(pool.map(self.fetch, keys))
                self.fetch(request_key(region, scope, 1, 1))

    def retry_failed(self):
        # Freeze the original failure list. No new query can be discovered here.
        failed = [value for value in self.checkpoints() if value['status'] == 'failed' and value['attempt'] == 0]
        for value in failed:
            self.fetch({field: value[field] for field in ('region', 'scope', 'page', 'size')}, retry=True)

    def cached_document(self, key):
        value = self.read(key)
        if value is None:
            self.missing_requests[tuple(key.values())] = {**key, 'reason': 'not_requested_during_week'}
        if value is None or value['status'] != 'success':
            raise CollectionError('incomplete_cached_scope')
        return value['document']

    def assemble_scope(self, region, scope):
        first = self.cached_document(request_key(region, scope, 1, self.page_size))
        total = first['total']
        rows, times, observed = {}, {}, []
        for page in range(1, max(1, math.ceil(total / self.page_size)) + 1):
            document = self.cached_document(request_key(region, scope, page, self.page_size))
            validate_page(document, page, self.page_size, total, region)
            observed.append(document['fetchedAt'])
            for row in document['rows']:
                if row['ykiho'] in rows:
                    raise CollectionError('duplicate_hospital_across_pages')
                rows[row['ykiho']], times[row['ykiho']] = row, document['fetchedAt']
        confirmation = self.cached_document(request_key(region, scope, 1, 1))
        validate_page(confirmation, 1, 1, total, region)
        if len(rows) != total:
            raise CollectionError('scope_count_mismatch')
        observed.append(confirmation['fetchedAt'])
        return rows, times, scope_time_range(observed, 8 * 24 if self.mode == 'retry' else 48)

    def assemble_region(self, region):
        basic, times, basic_times = self.assemble_scope(region, 'basic')
        memberships = {identity: set() for identity in basic}
        counts, scope_times = {'basic': len(basic)}, {'basic': basic_times}
        for scope in DEPARTMENTS:
            rows, _, fetched_times = self.assemble_scope(region, scope)
            if any(identity not in basic for identity in rows):
                raise CollectionError('department_hospital_missing_from_regional_basic_snapshot')
            for identity in rows:
                memberships[identity].add(scope)
            counts[scope], scope_times[scope] = len(rows), fetched_times
        return basic, times, memberships, counts, scope_times, {region: {'scopeCounts': counts, 'scopeTimes': scope_times}}

    def run(self, workers=2):
        if self.mode == 'collect':
            self.collect(workers)
        else:
            self.retry_failed()
        complete, deferred = [], []
        for region in self.regions:
            try:
                data, times, memberships, counts, scope_times, region_scopes = self.assemble_region(region)
                recovery = {'mode': 'sunday-failed-requests', 'weekStartKst': self.week_start, 'originalDay': self.day,
                            'attempt': 1, 'recoveredAt': timestamp()} if self.mode == 'retry' else None
                target = self.directory / 'ready' / region
                target.mkdir(parents=True, exist_ok=True)
                export_catalog(target, data, times, memberships, counts, self.client.calls - self.starting_calls,
                               scope_times, region_scopes, regions=[region], recovery=recovery)
            except CollectionError as error:
                deferred.append(region)
                emit('region_deferred', region=region, **error.diagnostic_fields())
            else:
                complete.append(region)
                emit('region_complete', region=region, rows=len(data))
        failures, skipped = [], list(self.missing_requests.values())
        for value in self.checkpoints():
            if value['status'] in ('failed', 'skipped', 'claimed'):
                item = {field: value[field] for field in ('region', 'scope', 'page', 'size')}
                item['reason'] = value.get('reason', 'request_interrupted')
                if 'resultCode' in value:
                    item['resultCode'] = value['resultCode']
                (failures if value['status'] == 'failed' else skipped).append(item)
        result = {'mode': self.mode, 'weekStartKst': self.week_start, 'originalDay': self.day,
                  'completeRegions': complete, 'deferredRegions': deferred, 'failedRequests': failures,
                  'skippedRequests': skipped, 'requests': self.client.calls - self.starting_calls}
        atomic_json(self.directory / 'result.json', result)
        emit('batch_complete', mode=self.mode, completeRegions=complete, deferredRegions=deferred,
             failedRequests=len(failures), skippedRequests=len(skipped), requests=result['requests'])
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path('.runtime/hospital-catalog') / dt.datetime.now(KST).date().isoformat())
    parser.add_argument('--regions', required=True, help='Comma-separated HIRA region codes from the original weekday group')
    parser.add_argument('--day', required=True, choices=tuple(DAY_REGIONS))
    parser.add_argument('--week-start', required=True)
    parser.add_argument('--mode', choices=('collect', 'retry'), default='collect')
    parser.add_argument('--page-size', type=int, default=500)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--workers', type=int, choices=(1, 2), default=2)
    parser.add_argument('--maximum-calls', type=int, default=10000)
    args = parser.parse_args()
    if not 1 <= args.page_size <= 1000 or not 1 <= args.timeout <= 300 or not 1 <= args.maximum_calls <= 10000:
        parser.error('Invalid page size, timeout, or maximum calls')
    regions = normalize_regions([region.strip() for region in args.regions.split(',')]) if args.regions is not None else None
    key = os.environ.get('HIRA_HOSPITAL_KEY', '').strip()
    if not key:
        raise CollectionError('missing_hospital_api_key')
    with directory_lock(args.directory):
        client = Client(key, args.directory, args.timeout, args.maximum_calls)
        WeeklyBatch(client, args.directory, regions, args.day, args.week_start, args.page_size, args.mode).run(args.workers)


if __name__ == '__main__':
    try:
        main()
    except CollectionError as error:
        emit('collection_failed', **error.diagnostic_fields())
        sys.exit(2)
    except KeyboardInterrupt:
        emit('collection_failed', reason='interrupted_checkpoints_preserved')
        sys.exit(130)
    except Exception as error:
        emit('collection_failed', reason='unexpected_failure', errorType=type(error).__name__)
        sys.exit(3)
