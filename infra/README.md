# 배포와 운영

## 사전 배포 (2026-11-20)

사용자 요청으로 정식 앱 구현 전에 Vercel 배포 주소와 Git 연결을 먼저 준비합니다. 현재 배포 대상은 “서비스 준비 중” 정적 안내 화면입니다. 병원 검색·AI 상담·모의 예약·로그인은 제공하지 않습니다.

| 항목 | 값 |
| --- | --- |
| Git 저장소 | `DavidPark04/hospital-reservation` |
| 운영 브랜치 | `main` |
| Root Directory | `frontend` |
| Framework Preset | Other |
| Build / Install Command | 없음 |
| Output Directory | `public` |
| 설정 파일 | [frontend/vercel.json](../frontend/vercel.json) |
| 환경변수 | 현재 필요 없음 |

프로젝트의 실제 배포 URL과 Ready 상태는 Vercel 프로젝트의 Production Deployment에서 확인합니다. 배포마다 `/` HTTP 200 및 “서비스 준비 중” 표시를 확인합니다. 정적 파일만 게시하므로 소스 문서·`.env.example`은 공개 출력에 포함되지 않습니다. 현재 SPA가 아니므로 존재하지 않는 경로는 404가 정상입니다.

로컬 확인:

```bash
python3 scripts/check_repository.py
python3 -m http.server 8000 --directory frontend/public
```

`http://localhost:8000`에서 화면을 확인합니다. 앱 빌드·기능 테스트 통과를 의미하지 않습니다.

## React 앱 전환 시

- FE 초기화 Issue #2에서 React·TypeScript·Vite와 lint/build CI를 준비합니다.
- `frontend/public/index.html`을 제거하고 Vite 진입점 `frontend/index.html`을 만듭니다.
- `frontend/vercel.json`을 `framework: "vite"`, `buildCommand: "npm run build"`, `outputDirectory: "dist"`로 바꾸고 설치 명령을 lockfile에 맞춥니다.
- 클라이언트 라우팅을 도입하면 SPA rewrite를 추가하고 상세 URL 새로고침을 검증합니다.
- 실제 BE 주소를 `VITE_API_BASE_URL`로 지정합니다. 이 값은 공개되므로 비밀 키를 넣지 않습니다.
- BE의 CORS에 실제 FE 운영 origin을 등록하고 인증·예약 흐름을 검증합니다.

## 백엔드와 데이터베이스

Spring Boot 서버(Render 후보)·MongoDB Atlas·외부 API는 아직 배포·연결하지 않았습니다. 도입·비용·절전·시작 지연을 확인한 뒤 설정 파일을 추가합니다. DB URI, API 키, JWT 비밀값은 BE 환경변수에만 저장합니다.

## 배포와 되돌리기

운영 배포는 `main`만 사용합니다. 작업 PR → `develop` 검증 → `main` 릴리스 PR 순서로 반영합니다. 연결 후 `main` 변경은 Vercel 운영 배포를 트리거합니다. 그 외 브랜치의 Preview는 운영과 구분합니다.

문제가 있으면 Vercel Deployments에서 직전 정상 운영 배포를 찾아 Rollback으로 복구하고, 동일 수정의 재배포를 막기 위해 Git에서도 revert PR을 작성합니다. 첫 배포는 이전 배포가 없으므로 수정 PR로 새 정상 배포를 만듭니다.

정식 서비스 배포·통합 검증은 기존 일정(12/5 통합 검증, 12/8 최종 접속 확인)에 따라 별도로 진행합니다. 사전 안내 화면 공개는 Issue #8 전체 완료를 뜻하지 않습니다.
