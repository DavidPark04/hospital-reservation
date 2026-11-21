# 같이 작업하는 규칙

박다윗·김도형 두 사람이 한 저장소에서 작업합니다. 일정·회의 결정은 Notion, 코드 작업과 검증 증거는 GitHub Issues·PR에 기록합니다.

## 1. 작업 시작

1. Issue에 목적, 완료 기준, 영향 영역, 선행 작업을 적습니다.
2. 담당자는 한 명으로 정하고 필요하면 상대에게 리뷰를 요청합니다. 공동 작업이면 본문에 역할을 나눕니다.
3. 최신 `main`에서 작업 브랜치를 만듭니다. 긴급 수정도 같은 절차를 따릅니다.

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/backend/12-create-reservation
# 변경 파일을 확인하고 필요한 파일만 추가
git add backend/src docs/api-contract.md
git commit -m "feat: 모의 예약 생성 API 추가"
git push -u origin feat/backend/12-create-reservation
```

`12`는 예시입니다. 실제 Issue 번호로 바꿉니다. 이미 만들어진 시작 브랜치는 [브랜치 안내](docs/branch-strategy.md)를 참고합니다.

## 2. 브랜치와 병합

| 흐름 | 방법 | 기준 |
| --- | --- | --- |
| 작업·긴급 수정 브랜치 → `main` | PR 한 개를 Squash and merge | 해당 PR의 최신 검사 결과와 리뷰 대화 확인 |
| `main` → `develop` | GitHub Actions가 fast-forward | 병합된 `main` 커밋의 push 검사 두 개 성공 |
| 검사된 `main` → Vercel | 같은 `Vercel production` 실행에서 배포 | `develop`이 같은 커밋 SHA를 가리키는지 확인 |

작업자는 자기 작업 브랜치에 push합니다. 충돌은 그 브랜치에 최신 `main`을 병합해 해결하고 검사를 다시 실행합니다. PR 병합 후에는 GitHub의 Delete branch로 작업 브랜치를 정리할 수 있습니다.

`develop`은 검사된 `main`의 사본입니다. 두 브랜치의 이력이 갈라져 fast-forward가 불가능하면 자동화가 중단됩니다. Actions 실행에서 실패 원인을 확인하고 각 브랜치에만 있는 커밋을 검토합니다.

## 3. PR과 리뷰

- 기능·문서·긴급 수정 모두 PR의 대상 브랜치를 `main`으로 선택합니다.
- 제목에 `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`, `style:`, `revert:` 중 하나를 사용합니다.
- 변경 이유, 변경 동작, 검증 명령·결과, 관련 Issue를 작성합니다. 미실행 검사를 통과했다고 쓰지 않습니다.
- 팀은 상대 리뷰를 선택 사항으로 운영합니다. 작성자는 PR의 `Repository checks`와 `Application checks` 결과를 확인하고 리뷰 대화를 해결한 뒤 병합합니다. 새 변경을 push하면 새 커밋의 검사를 확인합니다.
- 검토를 요청받으면 가능하면 하루 안에 의견을 남깁니다.
- API 요청·응답, 인증, 컬렉션·인덱스, 공통 설정을 바꾸면 관련 문서도 같은 PR에서 수정합니다.

관련 Issue는 PR 본문에서 `Refs #번호`로 연결합니다. 이 PR의 병합으로 Issue까지 완료되면 `Closes #번호`를 쓸 수 있습니다. 기본 브랜치 `main`에 병합될 때 해당 Issue가 닫힙니다. [GitHub 공식 설명](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)

## 4. 커밋 메시지

| 종류 | 용도 | 예시 |
| --- | --- | --- |
| feat | 새 기능 | `feat: 병원 지역 검색 추가` |
| fix | 오류 수정 | `fix: 예약 취소 시 잔여 정원 복구` |
| docs | 문서 | `docs: 예약 API 응답 예시 추가` |
| refactor | 동작을 유지하는 구조 변경 | `refactor: 외부 병원 API 클라이언트 분리` |
| test | 테스트 | `test: 마지막 한 자리 동시 예약 검증` |
| chore | 설정·도구 | `chore: 프론트엔드 프로젝트 초기화` |
| build | 빌드·배포 | `build: 백엔드 Docker 빌드 추가` |
| style | 동작 없는 코드 형식 변경 | `style: Java 들여쓰기 정리` |
| revert | 변경 되돌리기 | `revert: 병원 검색 캐시 변경 되돌리기` |

한 커밋은 한 목적을 갖습니다. `수정`, `완료`, `final_final`은 사용하지 않습니다. 작업 브랜치 접두사 전체 규칙은 [브랜치 안내](docs/branch-strategy.md)에 있습니다.

PR을 열면 GitHub가 고정 번호를 발급합니다. PR 제목과 최종 squash 커밋 제목 끝에 실제 번호를 `(#번호)`로 한 번만 적습니다. 동기화 후 두 브랜치가 같은 커밋을 가리키므로 양쪽의 링크가 같은 PR로 연결됩니다.

## 5. 코드·데이터 규칙

- Backend는 도메인별 패키지로 나누고 controller → service → repository 책임을 분리합니다. HTTP 요청·응답 DTO와 저장 문서를 분리합니다.
- Frontend는 `features/`, `pages/`, `components/`, `lib/`를 기준으로 구성합니다. 공통 API 호출과 오류 처리는 한곳에 둡니다.
- Java 클래스·React 컴포넌트는 PascalCase, 함수·변수는 camelCase, 상수는 UPPER_SNAKE_CASE를 사용합니다. 코드 식별자는 영어, 설명은 한국어로 적어도 됩니다.
- 최초 설정 PR에서 포매터·린터를 고정합니다. 생성된 lockfile과 Gradle Wrapper는 함께 커밋합니다.
- 비밀 값은 로컬 환경변수·배포 서비스 설정에만 둡니다. `.env.example`에는 변수명과 빈 값만 넣습니다.
- `VITE_` 변수는 브라우저에 공개됩니다. OpenAI 키·공공 API 키·DB URI·JWT 비밀값을 넣지 않습니다.
- 증상 원문은 MVP 기본 미저장입니다. 샘플 데이터·스크린샷·로그에는 실사용자 정보가 들어가지 않도록 합니다.
- AI가 작성한 코드도 작성자가 설명하고 검증할 수 있어야 합니다.

## 6. 완료 기준

기능 동작과 실패 경로, 권한, API 문서, 관련 테스트를 확인합니다. 특히 예약 기능은 정원 초과·중복 제출·타인 예약 변경·취소 재시도·일정 변경 실패 시 롤백을 검증합니다. 테스트를 위해 외부 유료 API를 자동 호출하지 않습니다.

CI는 저장소 문서·브랜치 규칙, 프론트 TypeScript/Vite 빌드, Java 21 빌드와 로컬 MongoDB replica set 기반 테스트를 검사합니다. 실제 외부 API·소셜 계정·운영 배포 검증은 별도 기록합니다. 현재 구현 구조와 초기 폴더 세분화 계획의 차이는 아키텍처 문서를 참고합니다.

## 7. 일정과 제출

노션과 기준 기획서의 **2026-11-20~12-09, 4주 계획**을 따릅니다. 11/25 공공 API 승인·응답 확인, 12/2 기능 동결·AI 연동 결정, 12/5 통합 검증, 12/8 준비 완료, 12/9 기말고사가 기준입니다. 배포 구성은 11/30, 결과보고서는 12/1부터 시작합니다.

주요 단계별 점검은 총 6회 계획합니다. 범위(11/20~22), API·DB·인증(11/25), 예약(11/29), AI·사용자 흐름(12/2), 배포·정합성(12/5), 보고서·시연(12/8)을 안건으로 두되 실제 개최 시간·참석자·결과는 회의 후 기록합니다. 세부 시간과 담당은 팀에서 정합니다.

12/2 이후에는 새 기능 추가를 멈추고 결함 수정·검증에 집중합니다. 미연동 기능과 알려진 한계는 보고서에 명시합니다. 과제 제출 시각·형식·개인 Fork 필요 여부는 과목 공지를 확인하고 [제출 체크리스트](docs/submission-checklist.md)에 반영합니다. 자세한 작업 기간과 산출물은 [프로젝트 계획서](docs/project-plan.md)를 따릅니다.
