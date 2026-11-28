import datetime as dt
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location('catalog_runner', Path(__file__).parents[1] / 'run-hospital-catalog.py')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
WEEK = '2026-11-23'
SATURDAY = dt.datetime(2026, 11, 28, 3, tzinfo=dt.timezone.utc)
SUNDAY = dt.datetime(2026, 11, 28, 15, tzinfo=dt.timezone.utc)


def archive(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as output:
        for name, content in files.items():
            output.writestr(name, content if isinstance(content, str) else json.dumps(content))
    return stream.getvalue()


def source(run_id, **changes):
    return {'id': run_id, 'path': runner.WORKFLOW, 'head_branch': 'main', 'status': 'completed',
            'event': 'schedule', 'head_repository': {'full_name': runner.REPOSITORY},
            'created_at': '2026-11-23T00:00:00Z', **changes}


def checkpoint(run_id, mode='collect', failed=True, week=WEEK):
    return archive({
        'run.json': {'schemaVersion': 1, 'mode': mode, 'day': 'monday', 'weekStartKst': week, 'runId': str(run_id)},
        'monday/batch.json': {'schemaVersion': 1, 'sourceMode': 'collect', 'originalDay': 'monday',
                            'weekStartKst': week, 'regions': ['110000'], 'pageSize': 500},
        'monday/requests/110000/basic/page-1-size-500.json': {'status': 'failed' if failed else 'success'},
    })


class GitHubMock:
    def __init__(self, runs, archives):
        self.entries = runs
        self.archives = archives
        self.artifact_reads = []

    def runs(self, week):
        return self.entries

    def artifacts(self, run_id):
        self.artifact_reads.append(run_id)
        payload = self.archives[run_id]
        return [{'id': run_id, 'name': f'hospital-catalog-checkpoint-{run_id}', 'expired': False,
                 'digest': 'sha256:' + hashlib.sha256(payload).hexdigest()}]

    def request(self, path, binary=False):
        assert binary
        return self.archives[int(path.split('/')[-2])]


class RunnerTests(unittest.TestCase):
    def test_actual_kst_day_gate_prevents_late_sunday_or_weekday_replay(self):
        with tempfile.TemporaryDirectory() as temporary:
            def forbidden(*args):
                self.fail('no claim, HTTP or DB operation is permitted')
            for day, now in [('sunday', SATURDAY), ('monday', SUNDAY),
                             ('sunday', dt.datetime(2026, 11, 29, 15, tzinfo=dt.timezone.utc))]:
                with patch.object(runner, 'recovery_sources', side_effect=forbidden):
                    result = runner.run(Path(temporary), day, now, claim=forbidden, process=forbidden)
                    self.assertTrue(result['skipped'])
                    self.assertEqual(result['requests'], 0)

    def test_duplicate_claim_does_not_create_or_replace_source_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result = runner.run(directory, 'saturday', SATURDAY, claim=lambda *args: False,
                                process=lambda *args: self.fail('duplicate must not execute collection'))
            self.assertTrue(result['skipped'])
            self.assertFalse((directory / 'checkpoint').exists())

    def test_collect_writes_source_identity_before_processing_and_records_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def process(folder, week, day, mode, budget):
                self.assertEqual(runner.read_json(directory / 'checkpoint/run.json'),
                    {'schemaVersion': 1, 'mode': 'collect', 'day': 'monday', 'weekStartKst': WEEK, 'runId': '77'})
                self.assertEqual((week, day, mode, budget), (WEEK, 'monday', 'collect', 10000))
                return {'day': day, 'claimed': True, 'completeRegions': [], 'deferredRegions': ['110000'],
                        'requests': 12, 'failedRequests': 1, 'skippedRequests': 0, 'appliedRegions': []}
            with patch.dict('os.environ', {'GITHUB_RUN_ID': '77'}):
                result = runner.run(directory, 'monday', SATURDAY, claim=lambda *args: True, process=process)
            self.assertEqual(result['requests'], 12)
            self.assertEqual(result['deferredRegions'], ['110000'])

    def test_only_same_week_main_workflow_and_collect_source_are_recovered(self):
        entries = [source(1, head_branch='feature'), source(2, event='pull_request'),
                   source(3, path='.github/workflows/untrusted.yml'),
                   source(4, created_at='2026-11-16T00:00:00Z'),
                   source(5, head_repository={'full_name': 'someone/fork'}),
                   source(6), source(7), source(8), source(9)]
        github = GitHubMock(entries, {6: checkpoint(6, mode='retry'), 7: checkpoint(7, failed=False),
                                      8: checkpoint(8), 9: checkpoint(9)})
        with tempfile.TemporaryDirectory() as temporary:
            batches = runner.recovery_sources(github, WEEK, Path(temporary))
        self.assertEqual(github.artifact_reads, [6, 7, 8, 9])
        self.assertEqual([(day, path.parent.name) for day, path in batches], [('monday', 'artifact-8')])

    def test_sunday_empty_queue_performs_no_claim_or_hira_requests(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = runner.run(Path(temporary), 'sunday', SUNDAY, github=GitHubMock([], {}),
                claim=lambda *args: self.fail('empty queue must not claim'),
                process=lambda *args: self.fail('empty queue must not collect'))
            self.assertTrue(result['skipped'])
            self.assertEqual(result['requests'], 0)

    def test_canceled_partial_artifact_does_not_block_another_recovery_source(self):
        incomplete = archive({'run.json': {'schemaVersion': 1, 'mode': 'collect', 'day': 'monday',
                              'weekStartKst': WEEK, 'runId': '7'}})
        github = GitHubMock([source(6), source(7), source(8)],
                            {6: archive({'collector.lock': '0'}), 7: incomplete, 8: checkpoint(8)})
        with tempfile.TemporaryDirectory() as temporary:
            batches = runner.recovery_sources(github, WEEK, Path(temporary))
            self.assertEqual([(day, path.parent.name) for day, path in batches], [('monday', 'artifact-8')])

    def test_sunday_duplicate_recovery_claim_never_repeats_failed_http(self):
        github = GitHubMock([source(8)], {8: checkpoint(8)})
        with tempfile.TemporaryDirectory() as temporary:
            observed = []
            def claim(directory, week, day, mode):
                observed.append((week, day, mode))
                return False
            result = runner.run(Path(temporary), 'sunday', SUNDAY, github=github, claim=claim,
                process=lambda *args: self.fail('Sunday rerun must not collect'))
            self.assertEqual(observed, [(WEEK, 'monday', 'retry')])
            self.assertTrue(result['skipped'])

    def test_archive_rejects_traversal_symlink_and_wrong_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / 'incoming'
            for name in ['../outside.json', '/outside.json', 'C:/outside.json', '..\\outside.json']:
                with self.subTest(name=name), self.assertRaisesRegex(runner.RunError, 'unsafe_checkpoint_archive_path'):
                    runner.extract_checkpoint(archive({name: {}}), destination)
            with self.assertRaisesRegex(runner.RunError, 'checkpoint_archive_digest_mismatch'):
                runner.extract_checkpoint(archive({'run.json': {}}), destination, 'sha256:' + '0' * 64)
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as output:
                info = zipfile.ZipInfo('link.json')
                info.external_attr = (0o120777 << 16)
                output.writestr(info, '../outside.json')
            with self.assertRaisesRegex(runner.RunError, 'unsafe_checkpoint_archive_path'):
                runner.extract_checkpoint(stream.getvalue(), destination)
            self.assertFalse((Path(temporary) / 'outside.json').exists())

    def test_successful_weekday_regions_are_not_applied_again_on_sunday(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            runner.write_json(folder / 'applied.json', {'regions': ['210000']})
            runner.write_json(folder / 'result.json', {'mode': 'retry', 'originalDay': 'wednesday',
                'weekStartKst': WEEK, 'requests': 1, 'completeRegions': ['210000', '360000'],
                'deferredRegions': ['380000'], 'failedRequests': [], 'skippedRequests': []})
            with patch.object(runner.subprocess, 'run') as execute:
                execute.return_value.returncode = 0
                result = runner.process_batch(folder, WEEK, 'wednesday', 'retry', 10000)
            self.assertEqual(execute.call_count, 2)  # collector + newly complete region only
            self.assertIn(str(folder / 'ready/360000'), execute.call_args_list[1].args[0])
            self.assertEqual(result['appliedRegions'], ['360000'])
            self.assertEqual(result['completeRegions'], ['360000'])
            self.assertEqual(runner.read_json(folder / 'applied.json')['regions'], ['210000', '360000'])

    def test_partial_or_unselected_region_output_cannot_reach_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            runner.write_json(folder / 'result.json', {'mode': 'collect', 'originalDay': 'monday',
                'weekStartKst': WEEK, 'requests': 1, 'completeRegions': ['210000'], 'deferredRegions': []})
            with patch.object(runner.subprocess, 'run') as execute:
                execute.return_value.returncode = 0
                with self.assertRaisesRegex(runner.RunError, 'invalid_catalog_completed_regions'):
                    runner.process_batch(folder, WEEK, 'monday', 'collect', 10000)
            self.assertEqual(execute.call_count, 1)  # no DB apply


if __name__ == '__main__':
    unittest.main()
