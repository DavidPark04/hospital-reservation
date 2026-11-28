"""Choose the intended KST weekday group, even when a scheduled run is delayed."""

import json
import os
from pathlib import Path
import sys


PLANS = {
    'monday': ('월요일 · 서울', ('110000',)),
    'tuesday': ('화요일 · 경기', ('310000',)),
    'wednesday': ('수요일 · 부산·전남광주·경남', ('210000', '360000', '380000')),
    'thursday': ('목요일 · 대구·인천·경북', ('230000', '220000', '370000')),
    'friday': ('금요일 · 전북·충남·대전', ('350000', '340000', '250000')),
    'saturday': ('토요일 · 충북·강원·울산·제주·세종·광주 별도', ('330000', '320000', '260000', '390000', '410000', '240000')),
}
# 15:00 UTC on Sunday-Friday is 00:00 KST on Monday-Saturday.
# The exact triggering cron is stable across queue delays and re-runs.
SCHEDULES = {f'0 15 * * {index}': day for index, day in enumerate(PLANS)}


def select_plan(event_name, event):
    if not isinstance(event, dict):
        raise ValueError('invalid_catalog_event')
    if event_name == 'schedule':
        schedule = event.get('schedule')
        day = SCHEDULES.get(schedule) if isinstance(schedule, str) else None
    elif event_name == 'workflow_dispatch':
        inputs = event.get('inputs')
        day = inputs.get('day') if isinstance(inputs, dict) else None
    else:
        raise ValueError('unsupported_catalog_event')
    if not isinstance(day, str) or day not in PLANS:
        raise ValueError('invalid_catalog_day')
    label, regions = PLANS[day]
    return {'day': day, 'label': label, 'regions': ','.join(regions)}


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
    plan = select_plan(os.environ['GITHUB_EVENT_NAME'], event)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        for key, value in plan.items():
            output.write(f'{key}={value}\n')
    print(json.dumps({'event': 'catalog_region_plan', **plan}, ensure_ascii=True))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError):
        print('Unable to select an explicit hospital catalogue weekday group.', file=sys.stderr)
        sys.exit(2)
