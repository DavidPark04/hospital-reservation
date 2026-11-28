"""Run one scheduled collection, or Sunday's one-time recovery of this week's failures."""

import argparse
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'medipick-dev/hospital-reservation'
WORKFLOW = '.github/workflows/hospital-catalog-sync.yml'
KST = dt.timezone(dt.timedelta(hours=9))
MAX_ARCHIVE_BYTES = 250 * 1024 * 1024
MAX_EXTRACTED_BYTES = 1024 * 1024 * 1024
spec = importlib.util.spec_from_file_location('catalog_schedule', ROOT / 'scripts/hospital-catalog-schedule.py')
schedule = importlib.util.module_from_spec(spec)
spec.loader.exec_module(schedule)
DAYS = tuple(day for day in schedule.PLANS if day != 'sunday')


class RunError(Exception):
    """Only fixed, credential-free diagnostic codes belong here."""


def emit(event, **fields):
    print(json.dumps({'event': event, **fields}, ensure_ascii=True), flush=True)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, UnicodeError, ValueError):
        raise RunError('invalid_checkpoint_json') from None


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=True), encoding='utf-8')
    temporary.replace(path)


def week_start(now):
    local = now.astimezone(KST)
    return (local.date() - dt.timedelta(days=local.weekday())).isoformat()


def permitted(day, now):
    return (now.astimezone(KST).weekday() == 6) == (day == 'sunday')


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        if urllib.parse.urlparse(new_url).scheme != 'https':
            raise RunError('insecure_artifact_redirect')
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected and urllib.parse.urlparse(request.full_url).netloc != urllib.parse.urlparse(new_url).netloc:
            redirected.remove_header('Authorization')
        return redirected


class GitHub:
    def __init__(self, token):
        if not token:
            raise RunError('missing_github_token')
        self.token = token

    def request(self, path, binary=False):
        if not path.startswith(f'/repos/{REPOSITORY}/'):
            raise RunError('unexpected_github_repository')
        request = urllib.request.Request('https://api.github.com' + path, headers={
            'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'MediPick-catalog-recovery',
        })
        try:
            with urllib.request.build_opener(SafeRedirect()).open(request, timeout=60) as response:
                payload = response.read(MAX_ARCHIVE_BYTES + 1 if binary else 8 * 1024 * 1024 + 1)
        except (urllib.error.URLError, TimeoutError, OSError):
            raise RunError('github_checkpoint_request_failed') from None
        if len(payload) > (MAX_ARCHIVE_BYTES if binary else 8 * 1024 * 1024):
            raise RunError('github_checkpoint_too_large')
        if binary:
            return payload
        try:
            return json.loads(payload)
        except (ValueError, UnicodeError):
            raise RunError('invalid_github_response') from None

    def runs(self, week):
        start = dt.datetime.combine(dt.date.fromisoformat(week), dt.time(), KST)
        end = start + dt.timedelta(days=7)
        interval = start.astimezone(dt.timezone.utc).isoformat() + '..' + end.astimezone(dt.timezone.utc).isoformat()
        result = []
        for page in range(1, 21):
            query = urllib.parse.urlencode({'branch': 'main', 'created': interval, 'per_page': 100, 'page': page})
            runs = self.request(f'/repos/{REPOSITORY}/actions/workflows/hospital-catalog-sync.yml/runs?{query}').get('workflow_runs')
            if not isinstance(runs, list):
                raise RunError('invalid_github_run_list')
            result.extend(runs)
            if len(runs) < 100:
                return result
        raise RunError('too_many_catalog_runs')

    def artifacts(self, run_id):
        result = self.request(f'/repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100')
        artifacts = result.get('artifacts')
        if not isinstance(artifacts, list) or result.get('total_count', len(artifacts)) > 100:
            raise RunError('invalid_github_artifact_list')
        return artifacts


def trusted_run(run, week):
    if not (isinstance(run, dict) and isinstance(run.get('id'), int) and run['id'] > 0
            and run.get('path') == WORKFLOW and run.get('head_branch') == 'main'
            and run.get('status') == 'completed' and run.get('event') in ('schedule', 'workflow_dispatch')
            and (run.get('head_repository') or {}).get('full_name') == REPOSITORY):
        return False
    try:
        created = dt.datetime.fromisoformat(run['created_at'].replace('Z', '+00:00'))
        return created.tzinfo is not None and week_start(created) == week
    except (KeyError, ValueError, TypeError):
        return False


def extract_checkpoint(payload, destination, digest=None):
    if len(payload) > MAX_ARCHIVE_BYTES:
        raise RunError('checkpoint_archive_too_large')
    if digest is not None and (not isinstance(digest, str)
            or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest)
            or 'sha256:' + hashlib.sha256(payload).hexdigest() != digest):
        raise RunError('checkpoint_archive_digest_mismatch')
    try:
        archive = zipfile.ZipFile(io.BytesIO(payload))
    except zipfile.BadZipFile:
        raise RunError('invalid_checkpoint_archive') from None
    with archive:
        members = archive.infolist()
        if sum(item.file_size for item in members) > MAX_EXTRACTED_BYTES or len(members) > 50000:
            raise RunError('checkpoint_archive_too_large')
        seen = set()
        for item in members:
            path = PurePosixPath(item.filename)
            if (not item.filename or '\\' in item.filename or ':' in item.filename or path.is_absolute()
                    or '..' in path.parts or path.as_posix() in seen
                    or (item.external_attr >> 16) & 0o170000 == 0o120000):
                raise RunError('unsafe_checkpoint_archive_path')
            seen.add(path.as_posix())
            if item.is_dir() or path.suffix not in ('.json', '.jsonl'):
                continue
            target = destination.joinpath(*path.parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RunError('unsafe_checkpoint_archive_path')
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as source, target.open('wb') as output:
                while chunk := source.read(1024 * 1024):
                    output.write(chunk)


def recovery_sources(github, week, destination):
    batches = {}
    for run in sorted((run for run in github.runs(week) if trusted_run(run, week)), key=lambda run: run['id']):
        run_id = run['id']
        for artifact in github.artifacts(run_id):
            if not (artifact.get('name') == f'hospital-catalog-checkpoint-{run_id}'
                    and artifact.get('expired') is False and isinstance(artifact.get('id'), int)):
                continue
            payload = github.request(f"/repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip", binary=True)
            folder = destination / f'artifact-{artifact["id"]}'
            extract_checkpoint(payload, folder, artifact.get('digest'))
            if not (folder / 'run.json').is_file():
                emit('catalog_checkpoint_skipped', artifactId=artifact['id'], reason='missing_run_checkpoint')
                continue
            metadata = read_json(folder / 'run.json')
            if (metadata.get('schemaVersion') != 1 or metadata.get('mode') != 'collect'
                    or metadata.get('weekStartKst') != week or metadata.get('runId') != str(run_id)):
                continue
            day = metadata.get('day')
            if day not in DAYS or day in batches:
                continue
            batch = folder / day
            if not (batch / 'batch.json').is_file():
                emit('catalog_checkpoint_skipped', artifactId=artifact['id'], reason='missing_batch_checkpoint')
                continue
            descriptor = read_json(batch / 'batch.json')
            if (descriptor.get('schemaVersion') != 1 or descriptor.get('weekStartKst') != week
                    or descriptor.get('originalDay') != day or descriptor.get('sourceMode') != 'collect'
                    or set(descriptor.get('regions', [])) != set(schedule.PLANS[day][1])
                    or descriptor.get('pageSize') != 500):
                raise RunError('checkpoint_batch_mismatch')
            # A canceled collection may have checkpoints but no final result file.
            failed = any(read_json(path).get('status') == 'failed' for path in (batch / 'requests').rglob('*.json'))
            if failed:
                batches[day] = batch
    return [(day, batches[day]) for day in DAYS if day in batches]


def claim_batch(directory, week, day, mode):
    output = directory / f'claim-{day}-{mode}.json'
    result = subprocess.run(['node', str(ROOT / 'scripts/claim-hospital-catalog-batch.mjs'),
                             '--week-start', week, '--day', day, '--mode', mode, '--output', str(output)], cwd=ROOT)
    if result.returncode:
        raise RunError('catalog_batch_claim_failed')
    claim = read_json(output)
    if not isinstance(claim.get('claimed'), bool):
        raise RunError('invalid_catalog_batch_claim')
    return claim['claimed']


def process_batch(folder, week, day, mode, remaining_calls):
    regions = schedule.PLANS[day][1]
    applied_path = folder / 'applied.json'
    previously_applied = read_json(applied_path).get('regions', []) if applied_path.exists() else []
    if (not isinstance(previously_applied, list) or len(set(previously_applied)) != len(previously_applied)
            or any(region not in regions for region in previously_applied)):
        raise RunError('invalid_catalog_applied_regions')
    command = [sys.executable, str(ROOT / 'scripts/collect-hospital-catalog.py'),
               '--directory', str(folder), '--regions', ','.join(regions), '--page-size', '500',
               '--timeout', '120', '--workers', '2', '--maximum-calls', str(remaining_calls),
               '--day', day, '--week-start', week, '--mode', mode]
    if subprocess.run(command, cwd=ROOT).returncode:
        raise RunError('catalog_checkpoint_collection_failed')
    result = read_json(folder / 'result.json')
    if (result.get('mode') != mode or result.get('originalDay') != day or result.get('weekStartKst') != week
            or not isinstance(result.get('requests'), int) or not 0 <= result['requests'] <= remaining_calls):
        raise RunError('invalid_catalog_batch_result')
    complete, deferred = result.get('completeRegions'), result.get('deferredRegions')
    if (not isinstance(complete, list) or not isinstance(deferred, list)
            or len(set(complete + deferred)) != len(complete + deferred)
            or set(complete + deferred) != set(regions)):
        raise RunError('invalid_catalog_completed_regions')
    applied = []
    for region in complete:
        if region in previously_applied:
            continue
        if subprocess.run(['node', str(ROOT / 'scripts/sync-hospital-catalog.mjs'),
                           '--directory', str(folder / 'ready' / region), '--apply'], cwd=ROOT).returncode:
            raise RunError('catalog_region_apply_failed')
        applied.append(region)
        write_json(applied_path, {'regions': [*previously_applied, *applied]})
    return {'day': day, 'claimed': True, 'completeRegions': applied, 'deferredRegions': deferred,
            'requests': result['requests'], 'failedRequests': len(result.get('failedRequests', [])),
            'skippedRequests': len(result.get('skippedRequests', [])), 'appliedRegions': applied}


def run(directory, day, now=None, github=None, claim=claim_batch, process=process_batch):
    now = now or dt.datetime.now(dt.timezone.utc)
    week = week_start(now)
    mode = 'retry' if day == 'sunday' else 'collect'
    summary = {'day': day, 'mode': mode, 'weekStartKst': week, 'completeRegions': [],
               'deferredRegions': [], 'requests': 0, 'skipped': False, 'batches': []}
    directory.mkdir(parents=True, exist_ok=True)
    if not permitted(day, now):
        summary['skipped'] = True
        emit('catalog_schedule_skipped', reason='outside_allowed_weekday', day=day)
    else:
        if mode == 'retry':
            sources = recovery_sources(github or GitHub(os.environ.get('GITHUB_TOKEN', '')), week, directory / 'incoming')
        else:
            sources = [(day, directory / 'checkpoint' / day)]
        for original_day, folder in sources:
            if summary['requests'] >= 10000:
                emit('catalog_batch_skipped', day=original_day, reason='daily_request_budget_reached')
                summary['deferredRegions'].extend(schedule.PLANS[original_day][1])
                continue
            if not claim(directory, week, original_day, mode):
                summary['batches'].append({'day': original_day, 'claimed': False, 'completeRegions': [],
                    'deferredRegions': [], 'requests': 0, 'failedRequests': 0, 'skippedRequests': 0, 'appliedRegions': []})
                emit('catalog_batch_skipped', day=original_day, reason='batch_already_claimed')
                continue
            if mode == 'collect':
                write_json(directory / 'checkpoint/run.json', {'schemaVersion': 1, 'mode': mode, 'day': day,
                           'weekStartKst': week, 'runId': os.environ.get('GITHUB_RUN_ID', '')})
            result = process(folder, week, original_day, mode, 10000 - summary['requests'])
            summary['batches'].append(result)
            summary['completeRegions'].extend(result['completeRegions'])
            summary['deferredRegions'].extend(result['deferredRegions'])
            summary['requests'] += result['requests']
        if not summary['batches'] or not any(batch['claimed'] for batch in summary['batches']):
            summary['skipped'] = True
    write_json(directory / 'result.json', summary)
    emit('catalog_schedule_complete', **summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--day', choices=(*DAYS, 'sunday'), required=True)
    args = parser.parse_args()
    if (os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY
            or os.environ.get('GITHUB_REF') != 'refs/heads/main'
            or not re.fullmatch(r'[1-9][0-9]*', os.environ.get('GITHUB_RUN_ID', ''))):
        raise RunError('scheduled_main_workflow_required')
    run(args.directory.resolve(), args.day)


if __name__ == '__main__':
    try:
        main()
    except RunError as error:
        emit('catalog_schedule_failed', reason=str(error))
        sys.exit(2)
    except Exception as error:
        emit('catalog_schedule_failed', reason='unexpected_failure', errorType=type(error).__name__)
        sys.exit(3)
