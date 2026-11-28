import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('catalog_schedule', Path(__file__).parents[1] / 'hospital-catalog-schedule.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ScheduleTests(unittest.TestCase):
    def test_weekday_groups_cover_each_of_the_17_codes_exactly_once(self):
        expected = {
            'monday': ['110000'], 'tuesday': ['310000'],
            'wednesday': ['210000', '360000', '380000'],
            'thursday': ['230000', '220000', '370000'],
            'friday': ['350000', '340000', '250000'],
            'saturday': ['330000', '320000', '260000', '390000', '410000', '240000'],
            'sunday': [],
        }
        actual = {day: list(value[1]) for day, value in MODULE.PLANS.items()}
        self.assertEqual(actual, expected)
        codes = [code for regions in actual.values() for code in regions]
        self.assertEqual(len(codes), 17)
        self.assertEqual(len(set(codes)), 17)
        self.assertEqual(actual['sunday'], [])

    def test_trigger_cron_selects_original_kst_day_despite_delayed_rerun(self):
        # Neither current date nor unrelated dispatch inputs decide scheduled work.
        for utc_day, kst_day in enumerate(MODULE.PLANS):
            with self.subTest(day=kst_day):
                plan = MODULE.select_plan('schedule', {'schedule': f'0 15 * * {utc_day}', 'inputs': {'day': 'saturday'}})
                self.assertEqual(plan['day'], kst_day)
                self.assertEqual(plan['mode'], 'retry' if kst_day == 'sunday' else 'collect')
        with self.assertRaises(ValueError):
            MODULE.select_plan('schedule', {'schedule': '0 15 * * 7'})

    def test_manual_run_requires_selected_group_and_never_defaults_to_all_regions(self):
        for day in MODULE.PLANS:
            self.assertEqual(MODULE.select_plan('workflow_dispatch', {'inputs': {'day': day}})['day'], day)
        for event in ({}, {'inputs': None}, {'inputs': {'day': 'all'}}, {'inputs': {'day': 'monday\nregions=all'}}, {'inputs': {'day': []}}):
            with self.subTest(event=event), self.assertRaises(ValueError):
                MODULE.select_plan('workflow_dispatch', event)
        with self.assertRaises(ValueError):
            MODULE.select_plan('push', {})

    def test_sunday_manual_and_scheduled_runs_only_select_retry_mode(self):
        scheduled = MODULE.select_plan('schedule', {'schedule': '0 15 * * 6', 'inputs': {'day': 'monday'}})
        manual = MODULE.select_plan('workflow_dispatch', {'inputs': {'day': 'sunday'}})
        self.assertEqual(scheduled, manual)
        self.assertEqual(scheduled, {'day': 'sunday', 'label': '일요일 · 실패 요청 재처리', 'regions': '', 'mode': 'retry'})

    def test_runner_outputs_are_explicit_and_invalid_event_leaves_no_output(self):
        with tempfile.TemporaryDirectory() as folder:
            event_path, output_path = Path(folder) / 'event.json', Path(folder) / 'output.txt'
            event_path.write_text(json.dumps({'schedule': '0 15 * * 2'}), encoding='utf-8')
            with patch.dict(MODULE.os.environ, {'GITHUB_EVENT_NAME': 'schedule', 'GITHUB_EVENT_PATH': str(event_path), 'GITHUB_OUTPUT': str(output_path)}), patch('builtins.print'):
                MODULE.main()
            values = dict(line.split('=', 1) for line in output_path.read_text(encoding='utf-8').splitlines())
            self.assertEqual(values['day'], 'wednesday')
            self.assertEqual(values['regions'], '210000,360000,380000')
            self.assertEqual(values['mode'], 'collect')
            event_path.write_text(json.dumps({'inputs': {'day': 'sunday'}}), encoding='utf-8')
            with patch.dict(MODULE.os.environ, {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_EVENT_PATH': str(event_path), 'GITHUB_OUTPUT': str(output_path)}), patch('builtins.print'):
                MODULE.main()
            values = dict(line.split('=', 1) for line in output_path.read_text(encoding='utf-8').splitlines())
            self.assertEqual(values['day'], 'sunday')
            self.assertEqual(values['regions'], '')
            self.assertEqual(values['mode'], 'retry')
            before = output_path.read_bytes()
            event_path.write_text('{}', encoding='utf-8')
            with patch.dict(MODULE.os.environ, {'GITHUB_EVENT_NAME': 'schedule', 'GITHUB_EVENT_PATH': str(event_path), 'GITHUB_OUTPUT': str(output_path)}), self.assertRaises(ValueError):
                MODULE.main()
            self.assertEqual(output_path.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
