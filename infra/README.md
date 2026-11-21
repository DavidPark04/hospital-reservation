# 배포와 운영

GitHub 저장소 `DavidPark04/hospital-reservation`을 유지하고, **김도형의 Vercel 프로젝트에서 프론트와 Java 백엔드를 함께 운영**합니다. 새 운영 대상은 [medi-pick.vercel.app](https://medi-pick.vercel.app)입니다.

2026-11-21 첫 운영 배포에서 화면·병원 검색·MongoDB 연결과 소셜 인증 시작 주소를 확인했습니다. Production 환경변수와 GitHub Actions 비밀값 3개를 등록했습니다. 버전별 운영 반영은 해당 PR·Actions·Vercel 배포 결과로 확인하며, Google/Kakao 운영 callback 등록과 실계정 로그인 검증은 사용자가 직접 진행합니다.

## Vercel 프로젝트 설정

| 항목 | 적용 값 |
| --- | --- |
| 소유 scope / 프로젝트 | `0381kdh-8885` / `medipick` |
| 소스 저장소 | `DavidPark04/hospital-reservation` |
| 운영 브랜치·배포 방식 | `main`, GitHub Actions에서 Vercel CLI 소스 배포 |
| Root Directory | 기존 `frontend` 대신 저장소 루트: 입력란 비우기 |
| 프로젝트 Build / Install / Output | 기존 정적 사이트 override를 해제하고 저장소 설정 사용 |
| 배포 구성 | 저장소 루트 [vercel.json](../vercel.json)의 `services` |
| frontend service | `frontend/`, Vite, `npm ci`, `npm run build`, `dist` |
| backend service | `backend/`, Container Runtime, [Dockerfile](../backend/Dockerfile) |
| 운영 주소 | `https://medi-pick.vercel.app` |

`frontend/vercel.json`만 읽는 기존 Root Directory 설정을 유지하면 Java 백엔드가 함께 배포되지 않습니다. 루트 설정은 API·OAuth·OpenAPI 경로를 backend, 나머지 경로를 frontend로 전달합니다. `/auth/social/callback`과 화면 직접 접속·새로고침은 SPA 진입점으로 처리합니다.

Java 21 컨테이너는 `PORT=8080`에 HTTP 서버를 열며 외부 프록시의 HTTPS 정보를 사용합니다. Vercel도 같은 포트로 전달하도록 프로젝트 환경변수에 `PORT=8080`을 설정합니다. 서비스 구성과 Container Runtime은 Vercel Beta 기능이므로 실제 프로젝트의 배포 로그·지원 상태를 확인합니다. [Vercel Container Runtime](https://vercel.com/docs/functions/runtimes/container), [Vercel Services](https://vercel.com/docs/services)

## GitHub 자동 배포 설정

개인 소유 GitHub 저장소의 공동 작업자는 Vercel의 기본 Git 연결을 직접 만들 수 없으므로 [공식 외부 CI 방식](https://vercel.com/docs/git/external-pipelines)을 사용합니다. 저장소 Settings → Secrets and variables → Actions에 아래 **Repository secrets**를 등록합니다.

| Secret | 값 |
| --- | --- |
| `VERCEL_TOKEN` | `medipick` 프로젝트에만 접근하는 배포 토큰. 현재 유효기간 60일 |
| `VERCEL_ORG_ID` | `0381kdh-8885` scope의 실제 ID (`team_...`), 표시 이름이 아님 |
| `VERCEL_PROJECT_ID` | `medipick`의 프로젝트 ID |

배포 CLI는 저장소 루트의 소스를 업로드하고 Vercel이 두 서비스를 빌드하므로 배포 작업에 DB·OAuth·OpenAI 키를 전달하지 않습니다. 별도의 병원 기본 목록 동기화에는 Actions의 `MONGODB_URI`, `HIRA_HOSPITAL_KEY`가 필요합니다. 나머지 서버 비밀값은 아래 Vercel Production 환경변수에서 관리합니다. 로컬 `.runtime`, `.env`, 의존성·빌드 결과는 [.vercelignore](../.vercelignore)로 업로드에서 제외합니다.

[Vercel production](../.github/workflows/vercel-production.yml)은 `main` 푸시의 `Application checks` 성공 후 **검사를 통과한 동일 SHA**를 배포합니다. PR·`develop`·fork 실행은 배포하지 않으며, 배포 직전에 최신 `main`과 SHA를 비교해 과거 검사 재실행으로 운영 버전이 되돌아가지 않게 합니다. 동시 배포는 한 번에 하나씩 진행합니다.

루트 `vercel.json`의 `git.deploymentEnabled=false`는 기존 팀원 프로젝트를 포함한 Vercel 기본 Git 자동 배포를 중지합니다. 기존 배포 사이트는 삭제하지 않으며, 새 운영 배포는 위 Actions의 CLI 명령으로만 실행합니다. 이 설정은 CLI 배포를 막지 않습니다. [Vercel Git 설정](https://vercel.com/docs/project-configuration/git-configuration#turning-off-all-automatic-deployments)

환경변수만 변경한 뒤에는 Actions → Vercel production → Run workflow에서 `main`을 선택합니다. 수동 실행도 그 커밋의 앱 검사 성공을 요구합니다. 워크플로 파일이 `main`에 병합되고 위 secrets가 설정되어야 자동 배포가 시작됩니다.

자동 배포 토큰이 만료되면 기존 사이트는 유지되지만 새 배포 인증이 실패합니다. Vercel에서 같은 프로젝트 범위의 토큰을 다시 발급해 GitHub의 `VERCEL_TOKEN`만 교체한 뒤 `main` 재배포를 실행합니다.

## 병원 기본 목록 일일 갱신

[Hospital basic catalog sync](../.github/workflows/hospital-catalog-sync.yml)는 매일 `15:00 UTC`, 한국 시간 `00:00`에 실행하도록 예약합니다. 기본 API 전체 목록과 지원 진료과별 목록을 모두 수집·검증한 뒤 `hospitals`에서 추가·변경·비활성화할 문서만 적용합니다. 상세 3종은 이 작업에서 수집하지 않으며 `hospitalDetails`에 저장하는 웹의 1시간 캐시를 유지합니다. 저장된 병원 내용이 같으면 문서를 다시 쓰지 않고 실행 성공 기록만 갱신합니다.

Repository secrets에 `HIRA_HOSPITAL_KEY`, `MONGODB_URI`를 등록해야 합니다. 수집·DB 적용은 GitHub runner에서 직접 진행하며 Vercel 운영 API를 호출하거나 매일 재배포하지 않습니다. 일반 검색·AI 후보 조회는 기본 카탈로그를 읽고 웹 요청으로 기본 API 수집을 시작하지 않습니다.

GitHub 예약 실행은 지연되거나 누락될 수 있으므로 자정 정각 완료를 보장하지 않습니다. 공개 저장소가 60일간 비활성 상태면 스케줄이 중지될 수 있습니다. Actions에서 다시 활성화하거나 **Run workflow → main**으로 실행하고 실제 완료 건수를 확인합니다. [GitHub 예약 실행 기준](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

처음 배포하기 전에 기본 카탈로그 적재를 완료하고, 일일 워크플로의 성공 결과를 별도로 확인합니다. 수동 수집·계획 확인·적용 명령과 실패 복구는 [병원 목록 운영 안내](../docs/hospital-catalog.md)를 따릅니다. 아직 실행되지 않은 워크플로나 문서 추가를 운영 갱신 완료로 기록하지 않습니다.

이전 DB의 `hospitalBasicCatalog`·`hospitals`를 새 버전의 `hospitals`·`hospitalDetails`로 전환할 때는 [컬렉션 이름 전환 절차](../docs/hospital-catalog.md#기존-컬렉션-이름-전환)를 따릅니다. 운영 트래픽과 같은 DB의 쓰기 작업을 중지한 구간에 이름 전환·새 버전 배포를 모두 완료하며, 기존 요청과 일일 수집이 실행 중인 상태에서는 전환하지 않습니다.

## Production 환경변수

비밀 값은 기존에 발급한 값을 **Vercel 프로젝트의 서버 환경변수**로 입력합니다. Git·PR·README·프론트 `VITE_` 변수에 복사하지 않습니다. 로컬 `.env`나 `.runtime` 파일을 Git에 올려 전달하지 않습니다.

| 변수 | 값 또는 용도 |
| --- | --- |
| `MONGODB_URI` | 기존 Atlas의 `MediPick` DB 연결 URI |
| `JWT_SECRET` | 모든 backend 인스턴스가 공유하는 고정된 충분히 긴 비밀값 |
| `APP_FRONTEND_URL` | `https://medi-pick.vercel.app` |
| `APP_BACKEND_URL` | `https://medi-pick.vercel.app` |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | 기존 Google 웹 OAuth 클라이언트 |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | 기존 Kakao REST API 키·Client Secret |
| `VITE_KAKAO_MAP_JS_KEY` | 지도용 공개 JavaScript 키. frontend 빌드에 반영하며 서버 비밀값과 구분 |
| `OPENAI_API_KEY` | 기존 OpenAI API 키 |
| `OPENAI_MODEL` | `gpt-6.2-sol` |
| `HIRA_HOSPITAL_KEY` | 병원정보서비스 인증키. 기본 수집은 Actions에서 수행하며 웹 요청에는 사용하지 않음 |
| `HIRA_DETAIL_KEY` | 선택한 병원의 상세 3종 조회용 인증키 |
| `PORT` | `8080` |
| `SERVER_ADDRESS` | `0.0.0.0` (컨테이너 기본값) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` (배포 프록시용 컨테이너 기본값) |
| `DEMO_ENABLED` | 샘플 병원 생성 여부. 실제 병원만 시작하려면 `false` |

`SERVER_PORT`를 따로 설정하면 `PORT`와 동일하게 맞춥니다. `HOSPITAL_API_FILE`은 로컬 보조 입력용이므로 운영에 설정하지 않습니다. DB URI에는 대상 DB명을 지정하고, Atlas 네트워크 허용 설정에서 Vercel 실행 환경이 접속할 수 있는지 확인합니다. 로컬 PC IP만 허용한 상태로는 클라우드 서버 접속이 보장되지 않습니다.

Production 비밀값을 Preview에 자동 복제하지 않습니다. Preview에서 실계정 OAuth를 확인하려면 해당 origin·callback·DB를 별도로 맞춰야 하므로 이번 운영 검증은 확정된 production 주소를 기준으로 합니다.

## 카카오 지도 설정

1. 기존 카카오 개발자 앱의 **카카오맵 → 사용 설정**을 확인하고 해당 앱에서 지도 API를 사용할 수 있게 설정합니다.
2. **앱 → 플랫폼 키 → JavaScript 키**의 JavaScript SDK 도메인에 `https://medi-pick.vercel.app`, `http://127.0.0.1:5174`, `http://localhost:5174`를 등록합니다. 다른 개발 포트를 사용하면 그 origin도 별도로 등록합니다.
3. 해당 JavaScript 키를 Vercel **Production**의 `VITE_KAKAO_MAP_JS_KEY`에 입력합니다. 이는 브라우저에 공개되는 지도 전용 설정이며 `KAKAO_CLIENT_ID`의 REST API 키나 Client Secret으로 대체하지 않습니다.
4. 환경변수를 저장한 뒤 frontend를 다시 빌드·배포하고 실제 병원 상세에서 지도 마커와 길찾기 목적지를 확인합니다. 미등록 Preview 도메인에서는 지도가 표시되지 않을 수 있습니다.

카카오맵 활성화와 JavaScript SDK 도메인 등록은 소셜 로그인 Redirect URI 설정과 별개입니다. 지도 추가가 사용자가 추후 처리할 OAuth callback 등록을 대신하지 않습니다. [Kakao 지도 Web API 가이드](https://apis.map.kakao.com/web/guide/)

2026년 7월 21일부터 카카오맵 무료 쿼터는 개발자 계정에서 첫 번째로 활성화한 앱에 적용됩니다. 다른 앱 또는 초과 사용에 필요한 유료 API·비즈월렛 설정은 별도로 결정합니다. [카카오맵 사용 방법·쿼터](https://developers.kakao.com/docs/ko/kakaomap/common)

2026-11-21 기존 앱의 카카오맵 활성화·무료 쿼터 적용, 위 세 SDK 도메인 저장과 Vercel Production의 공개 JavaScript 키 설정을 확인했습니다. 운영 지도 표시와 길찾기 검증은 해당 기능의 PR·배포 결과로 확인합니다.

## 배포 후 사용자가 등록할 소셜 주소

로컬 callback을 유지하면서 아래 운영 callback을 추가합니다.

- Google 승인된 리디렉션 URI: `https://medi-pick.vercel.app/login/oauth2/code/google`
- Kakao Redirect URI: `https://medi-pick.vercel.app/login/oauth2/code/kakao`
- 각 콘솔의 웹 사이트 origin 설정이 필요하면 `https://medi-pick.vercel.app`을 사용합니다.

기존 동의 범위는 유지합니다. Google은 `openid`, Kakao는 추가 개인정보 scope 없이 계정 식별번호만 사용하고 이름·이메일·전화번호는 앱의 추가 입력 화면에서 받습니다. 앱 비즈니스 전환이나 이메일·SMS 인증은 이번 구성에 포함되지 않습니다.

## 배포 순서와 검증

1. 작업 PR → `develop`은 Squash, `develop` → `main`은 Merge commit으로 병합합니다. 최신 커밋의 Repository checks·Frontend build·Backend tests를 확인합니다.
2. 프로젝트 소유자가 Vercel Production 환경변수와 GitHub Actions secrets를 적용합니다. `main` 병합 후 Application checks → Vercel production 결과를 확인합니다. 설정을 나중에 마쳤다면 위 수동 재배포를 실행합니다.
3. Vercel에서 두 service의 빌드와 Ready 상태를 확인합니다. React 화면 HTTP 200만으로 backend 성공을 판단하지 않습니다.
4. `/`, `/login`, `/mypage` 직접 접속·새로고침, JS/CSS 자원, `/api/health`, `/api/meta`, 병원 검색을 확인합니다. 기본 검색은 적재된 MongoDB 카탈로그에서 `CACHE`로 반환하고 기본 API를 호출하지 않아야 합니다. 일일 동기화 완료 건수와 이름·지역·진료과 검색 결과를 함께 확인합니다. API 경로가 `index.html`을 돌려주지 않아야 합니다. 실제 병원 상세의 지도·길찾기 목적지, 좌표·SDK 실패 시 안내와 검색 링크, 샘플 병원 지도 제외도 확인합니다.
5. 사용자 운영 callback 등록 후 일반 로그인·소셜 인증·추가 입력·중복 안내·로그아웃·관리자 목록을 검증합니다. 가입 미완료 자료는 `users`나 관리자 목록에 없어야 합니다.
6. 사용자가 정한 시험 계정으로 모의 예약 생성·변경·취소, 관리자 정원·휴진을 확인합니다. 서비스의 실제 계정을 임의로 생성·삭제하지 않습니다.
7. 유휴 상태에서 서버가 다시 시작돼도 기존 회원·예약이 유지되고 로그인·검색이 다시 되는지 확인합니다. 운영 결과와 미완료 항목을 해당 PR 및 [진행 현황](../docs/progress-and-next.md)에 기록합니다.

## 자동 검사와 현재 확인 범위

전화번호·이메일 중복 차단 수정본의 로컬 검증은 **JUnit 155개, 실패·오류·생략 0개**, `bootJar` 성공, `npm ci` 및 프론트 TypeScript/Vite 빌드 성공, 격리 DB의 HTTP 검사 **26개 통과**입니다. 수정본의 실제 운영 반영은 해당 배포 기록으로 별도 확인합니다.

새 주소의 첫 운영 배포에서는 화면·자원·병원 검색·DB 상태·비로그인 권한 차단·소셜 인증 시작 callback 등 **HTTP 검사 10개**를 통과했습니다. 이는 Google/Kakao 콘솔 등록 후의 실계정 로그인 완료나 실제 사용자 예약 검증을 뜻하지 않습니다.

GitHub Actions는 Node.js 22 프론트 지도 테스트·빌드와 Java 21 테스트·JAR 빌드, 임시 MongoDB 8.0.16 replica set, backend Docker image 빌드를 수행합니다. Docker가 없는 로컬 환경에서는 컨테이너 빌드를 실행하지 않았으며 원격 CI 결과로 확인합니다. 외부 API 키 없이 실행하고 통합 테스트가 생략되면 실패 처리합니다.

OAuth 요청은 5분, 교환 티켓은 60초의 MongoDB TTL 저장소에서 인스턴스 간 공유하고 원자적으로 한 번만 사용합니다. 가입 임시 권한은 30분입니다. AI 일일 제한도 공유 MongoDB 카운터를 사용하며 한국 시간 자정을 기준으로 비회원 20회·회원 60회입니다. DB 저장은 PC나 Java 프로세스의 수명과 분리됩니다.

## 되돌리기

문제가 있으면 프로젝트 소유자가 Vercel의 직전 정상 배포로 Rollback하고, Git에도 원인을 설명한 revert PR을 만듭니다. 데이터베이스 회원·예약을 자동 삭제하거나 백업으로 덮어쓰지 않습니다. 코드 롤백과 DB 마이그레이션 검토는 별도로 수행합니다.
