# Frontend

React·TypeScript·Vite 소스를 이 폴더에서 시작합니다. 현재는 실행 코드가 없는 준비 단계입니다.

첫 작업 브랜치: `chore/frontend/2-project-init`.

초기 PR 완료 기준:

- Node·패키지 관리자·React·TypeScript·Vite 버전 고정
- package.json과 lockfile, 기본 라우팅·린터·포매터 구성
- `src/features`, `src/pages`, `src/components`, `src/lib` 역할 분리
- 공통 API 호출·오류 표시, 환경변수 연결
- dev·lint·build 명령과 GitHub Actions 추가

`VITE_` 변수는 브라우저에 노출됩니다. `.env.example`에는 공개 API 주소만 두며, 외부 API 키와 DB URI는 BE에 둡니다.
