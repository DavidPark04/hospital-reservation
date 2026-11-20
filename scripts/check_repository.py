"""Repository structure, relative Markdown links, and PR routing checks.

This is not an application build or a security scanner.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
TYPES = r"feat|fix|docs|refactor|test|chore|build|style|revert"
TASK = re.compile(
    r"(?:(?:feat|fix|refactor|test|chore|build|style|revert)/"
    r"(?:backend|frontend|shared|infra)|docs)/[1-9][0-9]*-[a-z0-9]+(?:-[a-z0-9]+)*"
)
HOTFIX = re.compile(
    r"hotfix/(?:backend|frontend|shared|infra)/[1-9][0-9]*-[a-z0-9]+(?:-[a-z0-9]+)*"
)


def check_pr(pr):
    head, base = pr['head']['ref'], pr['base']['ref']
    errors = []
    allowed = (
        (base == 'develop' and (head == 'main' or TASK.fullmatch(head)))
        or (base == 'main' and (head == 'develop' or HOTFIX.fullmatch(head)))
    )
    if not allowed:
        errors.append(f'허용되지 않는 PR 경로: {head} -> {base}')
    if not re.fullmatch(rf'(?:{TYPES}): \S[^\r\n]*', pr.get('title', '')):
        errors.append('PR 제목은 종류: 설명 형식이어야 합니다.')
    return errors


def main():
    errors = []
    for name in [
        'README.md', 'CONTRIBUTING.md', 'backend/README.md', 'frontend/README.md',
        'backend/.env.example', 'frontend/.env.example', 'docs/branch-strategy.md',
        'docs/api-contract.md', 'docs/database.md', 'docs/repository-settings.md',
        '.github/pull_request_template.md', '.github/ISSUE_TEMPLATE/task.yml',
    ]:
        if not (ROOT / name).is_file():
            errors.append(f'필수 파일 누락: {name}')

    tracked = subprocess.check_output(
        ['git', 'ls-files', '-z'], cwd=ROOT
    ).decode().split('\0')
    for name in filter(None, tracked):
        path = ROOT / name
        if (path.name == '.env' or path.name.startswith('.env.')) and path.name != '.env.example':
            errors.append(f'환경 파일 추적 금지: {name}')
        if path.is_file() and path.suffix == '.md':
            text = path.read_text(encoding='utf-8')
            # Code examples are not Markdown links.
            text = re.sub(r'```.*?```', '', text, flags=re.S)
            for target in re.findall(r'\[[^\]]*\]\(([^)\s]+)\)', text):
                if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target) or target.startswith('#'):
                    continue
                target = unquote(target.split('#')[0])
                resolved = (path.parent / target).resolve()
                if not resolved.is_relative_to(ROOT) or not resolved.exists():
                    errors.append(f'상대 링크 오류: {name} -> {target}')

    event_path = os.environ.get('GITHUB_EVENT_PATH')
    if event_path and os.environ.get('GITHUB_EVENT_NAME') == 'pull_request':
        event = json.loads(Path(event_path).read_text(encoding='utf-8'))
        errors.extend(check_pr(event['pull_request']))

    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('저장소 구조·상대 문서 링크·해당 PR 규칙 검사 통과. 앱 빌드·테스트는 별도입니다.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
