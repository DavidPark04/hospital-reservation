"""Collect the complete HIRA basic catalogue without calling detailed-information APIs."""

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
KST = dt.timezone(dt.timedelta(hours=9))


class CollectionError(Exception):
    """Only fixed, credential-free error identifiers may be put in this exception."""


def timestamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def scope_time_range(values):
    try:
        dates = [dt.datetime.fromisoformat(value) for value in values]
        if not dates or any(value.tzinfo is None for value in dates):
            raise ValueError()
    except (ValueError, TypeError):
        raise CollectionError('invalid_scope_time') from None
    first, last = min(dates), max(dates)
    now = dt.datetime.now(dt.timezone.utc)
    if now - first > dt.timedelta(hours=48) or last - now > dt.timedelta(minutes=5):
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
        raise CollectionError('upstream_call_limit')
    if code != '00':
        raise CollectionError('upstream_result_not_success')
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
        self.transport_file = directory / 'transport-state.json'
        self.small_pages = self.transport_file.exists() and json.loads(self.transport_file.read_text(encoding='utf-8')).get('smallPages') is True
        self.budget_file = directory / 'request-budget.json'
        self.budget_date = dt.datetime.now(KST).date().isoformat()
        self.calls = 0
        if self.budget_file.exists():
            saved = json.loads(self.budget_file.read_text(encoding='utf-8'))
            if saved.get('dateKst') == self.budget_date:
                self.calls = int(saved.get('calls', 0))

    def page(self, code, number, size):
        can_split = size > 100 and size % 100 == 0
        if not (can_split and self.small_pages):
            try:
                return self._request_page(code, number, size)
            except CollectionError as error:
                if str(error) != 'upstream_transport_failed' or not can_split:
                    raise
                with self.lock:
                    self.small_pages = True
                    atomic_json(self.transport_file, {'smallPages': True})
        # Some large upstream pages time out. Fetch the same logical window in
        # smaller pages; changing page size without changing the offset skips rows.
        part_size = 100
        first_page = (number - 1) * size // part_size + 1
        combined, total, fetched_at = [], None, None
        emit('smaller_page_retry', scope=code or 'basic', page=number, requestedSize=size, transportSize=part_size)
        for offset in range(size // part_size):
            part = self._request_page(code, first_page + offset, part_size)
            if total is None:
                total = part['total']
            validate_page(part, first_page + offset, part_size, total)
            combined.extend(part['rows'])
            fetched_at = part['fetchedAt']
            if (first_page + offset) * part_size >= total:
                break
        result = {'page': number, 'requestedSize': size, 'reportedSize': size,
                  'total': total, 'fetchedAt': fetched_at, 'rows': combined,
                  'transportPageSize': part_size}
        validate_page(result, number, size, total)
        return result

    def _request_page(self, code, number, size):
        parameters = {'serviceKey': self.key, 'pageNo': number, 'numOfRows': size}
        if code:
            parameters['dgsbjtCd'] = code
        url = ENDPOINT + '?' + urllib.parse.urlencode(parameters)
        for attempt in range(3):
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
                if error.code < 500:
                    raise CollectionError('upstream_http_rejected') from None
            except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError):
                pass
            if attempt < 2:
                emit('request_retry', scope=code or 'basic', page=number, attempt=attempt + 1)
                time.sleep(attempt + 1)
        raise CollectionError('upstream_transport_failed')


def validate_page(document, page, size, total):
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
        if row['ykiho'] in identities:
            raise CollectionError('duplicate_hospital_in_page')
        identities.add(row['ykiho'])


def collect_scope(client, directory, code, initial_size, workers):
    scope = code or 'basic'
    folder = directory / scope
    folder.mkdir(parents=True, exist_ok=True)
    manifest_path = folder / 'manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        if manifest.get('scope') != scope:
            raise CollectionError('scope_mismatch')
    else:
        first = client.page(code, 1, initial_size)
        size = initial_size
        if first['rows'] and len(first['rows']) < min(first['total'], size):
            size = len(first['rows'])
            first = client.page(code, 1, size)
        if not code and first['total'] < 1:
            raise CollectionError('empty_nationwide_catalog')
        validate_page(first, 1, size, first['total'])
        manifest = {'scope': scope, 'size': size, 'total': first['total'], 'pages': {}, 'startedAt': timestamp()}
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
        validate_page(document, page, size, total)
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
                pending[pool.submit(client.page, code, page, size)] = page
        while pending:
            finished, _ = concurrent.futures.wait(pending, return_when=concurrent.futures.FIRST_COMPLETED)
            for future in finished:
                page = pending.pop(future)
                document = future.result()
                validate_page(document, page, size, total)
                observed_times.append(document['fetchedAt'])
                for row in document['rows']:
                    if row['ykiho'] in data:
                        raise CollectionError('duplicate_hospital_across_pages')
                    data[row['ykiho']] = row
                    times[row['ykiho']] = document['fetchedAt']
                manifest['pages'][str(page)] = atomic_json(folder / f'page-{page}.json', document)
                atomic_json(manifest_path, manifest)
                emit('page_saved', scope=scope, page=page, pages=page_count, rows=len(data), total=total)
            while len(pending) < workers:
                page = next(iterator, None)
                if page is None:
                    break
                pending[pool.submit(client.page, code, page, size)] = page
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
    if client.page(code, 1, 1)['total'] != total:
        raise CollectionError('source_total_changed_after_collection')
    emit('scope_complete', scope=scope, rows=total)
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


def export_catalog(directory, data, times, memberships, scope_counts, calls, scope_times):
    if set(scope_times) != {'basic', *DEPARTMENTS}:
        raise CollectionError('incomplete_scope_times')
    checked_times = {}
    for code, values in scope_times.items():
        checked_times[code] = scope_time_range([values['firstFetchedAt'], values['lastFetchedAt']])
    all_times = scope_time_range([value for timespan in checked_times.values() for value in timespan.values()])
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
    manifest = {'schemaVersion': 1, 'complete': True, 'source': 'hospInfoServicev2/getHospBasisList',
                'scope': 'nationwide-with-department-memberships', 'rows': len(data),
                'departments': DEPARTMENTS, 'scopeCounts': scope_counts, 'requestCount': calls,
                **all_times, 'scopeTimes': checked_times,
                'completedAt': timestamp(), 'sha256': digest.hexdigest()}
    atomic_json(directory / 'catalog-manifest.json', manifest)
    emit('catalog_complete', rows=len(data), requests=calls, sha256=manifest['sha256'])
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path('.runtime/hospital-catalog') / dt.datetime.now(KST).date().isoformat())
    parser.add_argument('--basic-cache', type=Path)
    parser.add_argument('--page-size', type=int, default=1000)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--workers', type=int, choices=(1, 2), default=2)
    parser.add_argument('--maximum-calls', type=int, default=10000)
    args = parser.parse_args()
    if not 1 <= args.page_size <= 1000 or not 1 <= args.timeout <= 300 or not 1 <= args.maximum_calls <= 10000:
        parser.error('Invalid page size, timeout, or maximum calls')
    key = os.environ.get('HIRA_HOSPITAL_KEY', '').strip()
    if not key:
        raise CollectionError('missing_hospital_api_key')
    with directory_lock(args.directory):
        client = Client(key, args.directory, args.timeout, args.maximum_calls)
        if args.basic_cache:
            data, times = import_basic_cache(args.basic_cache)
            basic_times = scope_time_range(times.values())
        else:
            data, times, basic_times = collect_scope(client, args.directory, '', args.page_size, args.workers)
        if args.basic_cache and client.page('', 1, 1)['total'] != len(data):
            raise CollectionError('basic_cache_source_total_changed')
        memberships = {identity: set() for identity in data}
        scope_counts = {'basic': len(data)}
        scope_times = {'basic': basic_times}
        for code in DEPARTMENTS:
            department_rows, _, department_times = collect_scope(client, args.directory, code, args.page_size, args.workers)
            for identity in department_rows:
                if identity not in memberships:
                    raise CollectionError('department_hospital_missing_from_basic_snapshot')
                memberships[identity].add(code)
            scope_counts[code] = len(department_rows)
            scope_times[code] = department_times
        if client.page('', 1, 1)['total'] != len(data):
            raise CollectionError('nationwide_total_changed_during_classification')
        export_catalog(args.directory, data, times, memberships, scope_counts, client.calls, scope_times)


if __name__ == '__main__':
    try:
        main()
    except CollectionError as error:
        emit('collection_failed', reason=str(error))
        sys.exit(2)
    except KeyboardInterrupt:
        emit('collection_failed', reason='interrupted_checkpoints_preserved')
        sys.exit(130)
    except Exception as error:
        emit('collection_failed', reason='unexpected_failure', errorType=type(error).__name__)
        sys.exit(3)
