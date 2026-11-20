# 같이 작업하는 규칙

박다윗·김도형 두 사람이 한 저장소에서 작업합니다. 일정·회의 결정은 Notion, 코드 작업과 검증 증거는 GitHub Issues·PR에 기록합니다.

## 1. 작업 시작

1. Issue에 목적, 완료 기준, 영향 영역, 선행 작업을 적습니다.
2. 담당자는 한 명으로 정하고 상대에게 리뷰를 요청합니다. 공동 작업이면 본문에 역할을 나눕니다.
3. 최신 `develop`에서 작업 브랜치를 만듭니다.

```bash
git switch develop
git pull --ff-only origin develop
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
| 작업 브랜치 → `develop` | Squash and merge | 한 Issue 또는 작은 완료 단위 |
| `develop` → `main` | Create a merge commit | 팀이 시연·제출 가능한 버전을 확인 |
| `main` → `develop` | Create a merge commit | 릴리스·긴급 수정 이력을 통합 브랜치에 반영 |
| `hotfix/영역/번호-내용` → `main` | Squash and merge | 배포·제출 버전의 긴급 오류, 상대 승인 필수 |

`main`, `develop`에는 직접 push·force push하지 않고 삭제하지 않습니다. 사람별 영구 브랜치는 만들지 않습니다. 각자 작업 브랜치를 소유하고 다른 사람 브랜치에 임의로 push하지 않습니다. 긴급 수정 후에는 `main` → `develop` PR을 반드시 만듭니다.

작업 브랜치는 병합 후 해당 PR의 Delete branch로 정리합니다. `develop`은 릴리스 후에도 유지합니다. 충돌은 작업 브랜치에서 최신 `develop`을 merge해 해결하고, 충돌 해결 뒤 검사를 다시 실행합니다. 공용 브랜치를 rebase하지 않습니다.

## 3. PR과 리뷰

- 기본 대상은 `develop`입니다. `main` 대상은 릴리스·긴급 수정만 허용합니다.
- 제목에 `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`, `style:`, `revert:` 중 하나를 사용합니다.
- 변경 이유, 변경 동작, 검증 명령·결과, 관련 Issue를 작성합니다. 미실행 검사를 통과했다고 쓰지 않습니다.
- 상대 팀원 1명 Approve, CI 성공, 리뷰 대화 해결 후 작성자가 병합합니다. 새 변경을 push하면 다시 검토받습니다.
- 가능하면 하루 안에 리뷰합니다. 바쁘면 가능한 시간을 남기고, 응답이 없다는 이유만으로 승인한 것으로 처리하지 않습니다.
- API 요청·응답, 인증, 컬렉션·인덱스, 공통 설정을 바꾸면 관련 문서도 같은 PR에서 수정합니다.

기본 브랜치는 `main`입니다. 작업 PR에는 `Refs #번호`를 적고, `develop` 통합·검증 후 Issue를 수동 완료 처리합니다. `Closes #번호` 자동 종료는 기본 브랜치를 대상으로 하는 PR에서만 동작하므로, `develop` PR에서 자동 종료를 기대하지 않습니다. [GitHub 공식 설명](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)

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

현재 CI는 저장소 문서·브랜치 규칙만 검사합니다. BE·FE 초기 설정 PR에서 앱 테스트·빌드 CI를 추가해야 기능 검증까지 수행됩니다.

## 7. 일정과 제출

주 1회 40분 리뷰는 노션의 제안입니다. 요일·시간과 담당을 회의에서 정합니다. 2026-11-16~12-27은 6주 일정안이며 공식 마감이 아닙니다. 제출 형식·개인 Fork 필요 여부는 이 과목 공지를 확인하고 [제출 체크리스트](docs/submission-checklist.md)에 반영합니다. 복지문턱 과제의 마감과 Fork 제출 규칙을 그대로 적용하지 않습니다.
