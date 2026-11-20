# 배포와 운영

GitHub 저장소 `DavidPark04/hospital-reservation`을 유지하고, **김도형의 Vercel 프로젝트에서 프론트와 Java 백엔드를 함께 운영**합니다. 새 운영 대상은 [hospital-reservation-app.vercel.app](https://hospital-reservation-app.vercel.app)입니다.

소스·설정 준비와 운영 반영은 별도 단계입니다. 프로젝트 소유자가 아래 Production 환경변수와 배포 인증을 설정한 뒤 실제 배포 결과를 확인합니다. Google/Kakao 운영 주소 등록은 배포 후 사용자가 직접 진행합니다.

## Vercel 프로젝트 설정

| 항목 | 적용 값 |
| --- | --- |
| 소유 scope / 프로젝트 | `0381kdh-8885` / `hospital-reservation-app` |
| 소스 저장소 | `DavidPark04/hospital-reservation` |
| 운영 브랜치·배포 방식 | `main`, GitHub Actions에서 Vercel CLI 소스 배포 |
| Root Directory | 기존 `frontend` 대신 저장소 루트: 입력란 비우기 |
| 프로젝트 Build / Install / Output | 기존 정적 사이트 override를 해제하고 저장소 설정 사용 |
| 배포 구성 | 저장소 루트 [vercel.json](../vercel.json)의 `services` |
| frontend service | `frontend/`, Vite, `npm ci`, `npm run build`, `dist` |
| backend service | `backend/`, Container Runtime, [Dockerfile](../backend/Dockerfile) |
| 운영 주소 | `https://hospital-reservation-app.vercel.app` |

`frontend/vercel.json`만 읽는 기존 Root Directory 설정을 유지하면 Java 백엔드가 함께 배포되지 않습니다. 루트 설정은 API·OAuth·OpenAPI 경로를 backend, 나머지 경로를 frontend로 전달합니다. `/auth/social/callback`과 화면 직접 접속·새로고침은 SPA 진입점으로 처리합니다.

Java 21 컨테이너는 `PORT=8080`에 HTTP 서버를 열며 외부 프록시의 HTTPS 정보를 사용합니다. Vercel도 같은 포트로 전달하도록 프로젝트 환경변수에 `PORT=8080`을 설정합니다. 서비스 구성과 Container Runtime은 Vercel Beta 기능이므로 실제 프로젝트의 배포 로그·지원 상태를 확인합니다. [Vercel Container Runtime](https://vercel.com/docs/functions/runtimes/container), [Vercel Services](https://vercel.com/docs/services)

## GitHub 자동 배포 설정

개인 소유 GitHub 저장소의 공동 작업자는 Vercel의 기본 Git 연결을 직접 만들 수 없으므로 [공식 외부 CI 방식](https://vercel.com/docs/git/external-pipelines)을 사용합니다. 저장소 Settings → Secrets and variables → Actions에 아래 **Repository secrets**를 등록합니다.

| Secret | 값 |
| --- | --- |
| `VERCEL_TOKEN` | 새 Vercel 프로젝트에 배포할 수 있는 소유자의 토큰. 가능하면 해당 프로젝트로 범위를 제한 |
| `VERCEL_ORG_ID` | `0381kdh-8885` scope의 실제 ID (`team_...`), 표시 이름이 아님 |
| `VERCEL_PROJECT_ID` | `hospital-reservation-app`의 프로젝트 ID |

DB·OAuth·OpenAI·HIRA 키는 아래 Vercel Production 환경변수에만 등록하며 Actions에 복사할 필요가 없습니다. CLI는 저장소 루트의 소스를 업로드하고 Vercel이 두 서비스를 빌드합니다. 로컬 `.runtime`, `.env`, 의존성·빌드 결과는 [.vercelignore](../.vercelignore)로 업로드에서 제외합니다.

[Vercel production](../.github/workflows/vercel-production.yml)은 `main` 푸시의 `Application checks` 성공 후 **검사를 통과한 동일 SHA**를 배포합니다. PR·`develop`·fork 실행은 배포하지 않으며, 배포 직전에 최신 `main`과 SHA를 비교해 과거 검사 재실행으로 운영 버전이 되돌아가지 않게 합니다. 동시 배포는 한 번에 하나씩 진행합니다.

루트 `vercel.json`의 `git.deploymentEnabled=false`는 기존 팀원 프로젝트를 포함한 Vercel 기본 Git 자동 배포를 중지합니다. 기존 배포 사이트는 삭제하지 않으며, 새 운영 배포는 위 Actions의 CLI 명령으로만 실행합니다. 이 설정은 CLI 배포를 막지 않습니다. [Vercel Git 설정](https://vercel.com/docs/project-configuration/git-configuration#turning-off-all-automatic-deployments)

환경변수만 변경한 뒤에는 Actions → Vercel production → Run workflow에서 `main`을 선택합니다. 수동 실행도 그 커밋의 앱 검사 성공을 요구합니다. 워크플로 파일이 `main`에 병합되고 위 secrets가 설정되어야 자동 배포가 시작됩니다.

## Production 환경변수

비밀 값은 기존에 발급한 값을 **Vercel 프로젝트의 서버 환경변수**로 입력합니다. Git·PR·README·프론트 `VITE_` 변수에 복사하지 않습니다. 로컬 `.env`나 `.runtime` 파일을 Git에 올려 전달하지 않습니다.

| 변수 | 값 또는 용도 |
| --- | --- |
| `MONGODB_URI` | 기존 Atlas의 `hospital_reservation` DB 연결 URI |
| `JWT_SECRET` | 모든 backend 인스턴스가 공유하는 고정된 충분히 긴 비밀값 |
| `APP_FRONTEND_URL` | `https://hospital-reservation-app.vercel.app` |
| `APP_BACKEND_URL` | `https://hospital-reservation-app.vercel.app` |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | 기존 Google 웹 OAuth 클라이언트 |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | 기존 Kakao REST API 키·Client Secret |
| `OPENAI_API_KEY` | 기존 OpenAI API 키 |
| `OPENAI_MODEL` | `gpt-6.2-sol` |
| `HIRA_HOSPITAL_KEY`, `HIRA_DETAIL_KEY` | 승인받은 HIRA 병원정보·상세정보 인증키 |
| `PORT` | `8080` |
| `SERVER_ADDRESS` | `0.0.0.0` (컨테이너 기본값) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` (배포 프록시용 컨테이너 기본값) |
| `DEMO_ENABLED` | 샘플 병원 생성 여부. 실제 병원만 시작하려면 `false` |

`SERVER_PORT`를 따로 설정하면 `PORT`와 동일하게 맞춥니다. `HOSPITAL_API_FILE`은 로컬 보조 입력용이므로 운영에 설정하지 않습니다. DB URI에는 대상 DB명을 지정하고, Atlas 네트워크 허용 설정에서 Vercel 실행 환경이 접속할 수 있는지 확인합니다. 로컬 PC IP만 허용한 상태로는 클라우드 서버 접속이 보장되지 않습니다.

Production 비밀값을 Preview에 자동 복제하지 않습니다. Preview에서 실계정 OAuth를 확인하려면 해당 origin·callback·DB를 별도로 맞춰야 하므로 이번 운영 검증은 확정된 production 주소를 기준으로 합니다.

## 배포 후 사용자가 등록할 소셜 주소

로컬 callback을 유지하면서 아래 운영 callback을 추가합니다.

- Google 승인된 리디렉션 URI: `https://hospital-reservation-app.vercel.app/login/oauth2/code/google`
- Kakao Redirect URI: `https://hospital-reservation-app.vercel.app/login/oauth2/code/kakao`
- 각 콘솔의 웹 사이트 origin 설정이 필요하면 `https://hospital-reservation-app.vercel.app`을 사용합니다.

기존 동의 범위는 유지합니다. Google은 `openid`, Kakao는 추가 개인정보 scope 없이 계정 식별번호만 사용하고 이름·이메일·전화번호는 앱의 추가 입력 화면에서 받습니다. 앱 비즈니스 전환이나 이메일·SMS 인증은 이번 구성에 포함되지 않습니다.

## 배포 순서와 검증

1. 작업 PR → `develop`은 Squash, `develop` → `main`은 Merge commit으로 병합합니다. 최신 커밋의 Repository checks·Frontend build·Backend tests를 확인합니다.
2. 프로젝트 소유자가 Vercel Production 환경변수와 GitHub Actions secrets를 적용합니다. `main` 병합 후 Application checks → Vercel production 결과를 확인합니다. 설정을 나중에 마쳤다면 위 수동 재배포를 실행합니다.
3. Vercel에서 두 service의 빌드와 Ready 상태를 확인합니다. React 화면 HTTP 200만으로 backend 성공을 판단하지 않습니다.
4. `/`, `/login`, `/mypage` 직접 접속·새로고침, JS/CSS 자원, `/api/health`, `/api/meta`, 병원 검색을 확인합니다. API 경로가 `index.html`을 돌려주지 않아야 합니다.
5. 사용자 운영 callback 등록 후 일반 로그인·소셜 인증·추가 입력·중복 안내·로그아웃·관리자 목록을 검증합니다. 가입 미완료 자료는 `users`나 관리자 목록에 없어야 합니다.
6. 사용자가 정한 시험 계정으로 모의 예약 생성·변경·취소, 관리자 정원·휴진을 확인합니다. 서비스의 실제 계정을 임의로 생성·삭제하지 않습니다.
7. 유휴 상태에서 서버가 다시 시작돼도 기존 회원·예약이 유지되고 로그인·검색이 다시 되는지 확인합니다. 운영 결과와 미완료 항목을 해당 PR 및 [진행 현황](../docs/progress-and-next.md)에 기록합니다.

## 자동 검사와 현재 확인 범위

기능 통합 소스의 로컬 검증은 **JUnit 134개, 실패·오류·생략 0개**, `bootJar` 성공, `npm ci` 및 프론트 TypeScript/Vite 빌드 성공입니다. 실제 외부 API·실계정 OAuth·운영 Vercel 배포 성공을 뜻하지 않습니다.

GitHub Actions는 Node.js 22 프론트 빌드와 Java 21 테스트·JAR 빌드, 임시 MongoDB 8.0.16 replica set, backend Docker image 빌드를 수행합니다. Docker가 없는 로컬 환경에서는 컨테이너 빌드를 실행하지 않았으며 원격 CI 결과로 확인합니다. 외부 API 키 없이 실행하고 통합 테스트가 생략되면 실패 처리합니다.

OAuth 요청은 5분, 교환 티켓은 60초의 MongoDB TTL 저장소에서 인스턴스 간 공유하고 원자적으로 한 번만 사용합니다. 가입 임시 권한은 30분입니다. AI 일일 제한도 공유 MongoDB 카운터를 사용하며 한국 시간 자정을 기준으로 비회원 20회·회원 60회입니다. DB 저장은 PC나 Java 프로세스의 수명과 분리됩니다.

## 되돌리기

문제가 있으면 프로젝트 소유자가 Vercel의 직전 정상 배포로 Rollback하고, Git에도 원인을 설명한 revert PR을 만듭니다. 데이터베이스 회원·예약을 자동 삭제하거나 백업으로 덮어쓰지 않습니다. 코드 롤백과 DB 마이그레이션 검토는 별도로 수행합니다.
