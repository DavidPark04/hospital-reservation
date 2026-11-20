# 병원 기본 목록 운영

웹의 병원 검색·홈 목록·AI 추천은 MongoDB의 `hospitalBasicCatalog`를 조회합니다. 사용자가 접속하거나 검색할 때 기본 공공 API 수집을 시작하지 않습니다. 초기 적재와 일일 수집은 별도 실행하며, 실제 저장 완료 건수는 실행 결과에서 확인합니다.

## 수집 범위

사용 API는 [건강보험심사평가원 병원정보서비스](https://www.data.go.kr/data/15001698/openapi.do)의 `hospInfoServicev2/getHospBasisList`입니다.

1. 조건 없는 전국 기본 목록을 모든 페이지에서 수집합니다.
2. 같은 기본 API에 `dgsbjtCd`를 지정해 서비스가 지원하는 16개 진료과의 소속 병원을 확인합니다.
3. 원본 `ykiho`로 병원을 식별하고 기본 정보와 진료과 소속을 하나의 카탈로그로 만듭니다.

지원 분류는 내과·신경과·정신건강의학과·외과·정형외과·신경외과·산부인과·소아청소년과·안과·이비인후과·피부과·비뇨의학과·재활의학과·가정의학과·치과·한방내과입니다. 진료과 조건별 목록을 수집하는 것은 병원별 상세 API 호출과 다릅니다.

`의료기관별상세정보서비스`의 진료과·특수진료·전문병원 지정 3종은 일일 수집 대상이 아닙니다. 사용자가 병원을 선택하면 기존처럼 필요한 상세 정보를 조회하며, 완전한 응답은 1시간 재사용합니다. 기본 목록 수집이 상세 API 11종 전체 수집을 의미하지 않습니다.

## 저장과 공개

| 저장소 | 역할 |
| --- | --- |
| `hospitalBasicCatalog` | 전체 기본 원본 `apiFields`, 검색 필드, `departmentCodes`, `departments`, `active`, 내용 비교 해시 |
| `hospitals`, `hospitalEvidence` | 선택한 병원의 상세 캐시·근거와 기존 예약·의료진의 참조 |
| `hospitalImportRuns` | 실행별 상태·건수와 마지막 성공 실행, 동시 적용 방지 잠금 |

병원 ID는 기존 앱과 같은 `UUID.nameUUIDFromBytes(UTF-8("HIRA:" + ykiho))` 규칙을 사용합니다. 기본 카탈로그를 전부 `hospitals`에 복제하지 않습니다. 웹에서 공개하는 병원 응답에는 원본 전체 필드·암호화 기관번호·비교용 해시를 넣지 않습니다.

`basicSourceHash`는 정렬한 기본 원본 필드의 SHA-256이고, `catalogHash`는 기본 해시와 정렬한 진료과 소속으로 계산합니다. 신규 병원은 추가하고, 해시나 활성 상태가 달라진 문서만 갱신합니다. 진료과 소속만 달라지면 같은 기본 필드·기본 조회 시각을 다시 쓰지 않습니다. 내용이 같은 문서는 날짜만 갱신하지 않으므로 `basicFetchedAt`과 전체 동기화 성공 시각은 다를 수 있습니다.

검증한 전체 목록에서 빠진 병원은 `active:false`로 바꾸고 `retiredAt`을 남깁니다. 이는 현재 원본 목록에서 확인되지 않는다는 뜻이며 폐업 판정이 아닙니다. 다시 나타나면 활성화합니다. 회원·예약·의료진과 과거 이력은 삭제하지 않습니다.

수집을 모두 검증한 뒤 DB 변경분을 순서대로 적용하며 반영된 자료는 이후 웹 조회에 사용됩니다. 전체 DB를 한 번에 교체하는 트랜잭션은 아닙니다. DB 쓰기 도중 실패하면 일부 변경이 반영될 수 있으며, 같은 검증 자료로 재실행해 이어서 적용합니다.

## 매일 한국 시간 자정 실행

[Hospital basic catalog sync](../.github/workflows/hospital-catalog-sync.yml)는 `0 15 * * *` UTC, 즉 매일 한국 시간 00시로 예약됩니다. 기본 브랜치 `main`의 코드로 수집·검증·DB 적용을 실행합니다. 수동 실행은 GitHub Actions에서 같은 워크플로의 **Run workflow → main**을 선택합니다.

자정은 실행 예정 시각이며 완료 시각이 아닙니다. GitHub Actions는 부하에 따라 예약 실행이 지연되거나 누락될 수 있고, 공개 저장소에 60일간 활동이 없으면 예약 워크플로가 자동 비활성화될 수 있습니다. 이 경우 워크플로를 다시 활성화하고 수동 실행 결과를 확인합니다. [GitHub 예약 실행 기준](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

워크플로는 `contents: read` 권한과 한 번에 하나의 실행을 사용하며 기존 실행을 중간 취소하지 않습니다. 실행 제한은 360분입니다. 수집과 DB 반영은 GitHub runner에서 직접 수행하고 Vercel 운영 API·재배포를 사용하지 않습니다. Vercel의 사용자 검색 요청은 여전히 DB 조회를 처리합니다.

저장소 **Settings → Secrets and variables → Actions → Repository secrets**에 아래 두 값을 설정합니다.

| Secret | 용도 |
| --- | --- |
| `HIRA_HOSPITAL_KEY` | 병원정보서비스 사용이 승인된 인증키 |
| `MONGODB_URI` | 대상 DB명이 포함된 Atlas 연결 URI |

이 두 값은 배포용 `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID`와 별도입니다. 일일 수집에 상세·OAuth·OpenAI 키는 필요하지 않습니다. Atlas 네트워크 설정에서 해당 실행 환경의 연결이 허용되어야 합니다. 비밀 값과 원본 데이터 파일을 커밋하거나 공개 로그에 출력하지 않습니다.

## 로컬 수집·검토·적용

Python 3.12와 Node.js 22를 준비하고 저장소 루트에서 실행합니다. 현재 셸에 `HIRA_HOSPITAL_KEY`, `MONGODB_URI`를 환경변수로 전달합니다. 아래 스크립트는 `.env`를 자동으로 읽지 않습니다. 날짜가 다른 새 수집에는 새 디렉터리를 사용합니다.

```bash
npm ci --ignore-scripts
python scripts/collect-hospital-catalog.py --directory .runtime/hospital-catalog/initial --page-size 1000 --timeout 120 --workers 2 --maximum-calls 10000
node scripts/sync-hospital-catalog.mjs --directory .runtime/hospital-catalog/initial
node scripts/sync-hospital-catalog.mjs --directory .runtime/hospital-catalog/initial --apply
```

수집기는 요청한 페이지 크기의 실제 응답과 총건수를 확인하며, 동시 요청은 최대 2개로 제한합니다. `--maximum-calls`는 해당 수집기의 호출 예산이며 계정의 실제 승인 한도를 늘리는 설정이 아닙니다. 페이지·재시도에 호출 한도가 사용됩니다. `--apply` 없는 동기화 명령은 DB와 비교한 계획만 출력합니다.

수집 폴더에는 범위별 페이지·체크포인트와 최종 `catalog.jsonl`, `catalog-manifest.json`이 생성됩니다. 전체 검증 성공 후에만 최종 파일을 게시합니다. DB 명령은 체크섬·건수·진료과 범위·조회 시각을 다시 검증합니다. 48시간보다 오래된 수집 자료는 적용하지 않습니다.

이미 별도 기본 수집기의 완료 감사를 통과한 자료가 있으면 수집 명령에 `--basic-cache <수집 폴더>`를 추가할 수 있습니다. 이 경로는 `hospitals.jsonl`, `manifest.json`, `summary.json`을 요구하고 원본 해시·건수·현재 API 총건수와 24시간 이내 수집 여부를 검사합니다. 진료과 조건별 수집은 별도로 수행합니다.

## 확인과 복구

- 성공 후 `sync-result.json`과 Actions 실행 요약의 `rows`, `inserted`, `updated`, `reactivated`, `retired`, `unchanged`, `verified`를 확인합니다.
- `hospitalImportRuns`에는 실행 UUID의 `COMPLETE` 또는 `FAILED` 상태를 기록합니다. `_id: hospital-basic-current`는 마지막 성공 실행과 범위별 건수·수집 시각을 가리킵니다. `hospital-basic-sync-lock`은 중복 DB 적용을 막는 임시 잠금입니다.
- 누락 페이지·중복 기관번호·총건수 불일치·분류 범위 불일치·수집 중 전국 총건수 변경이 있으면 적용을 시작하지 않습니다. 기본 자료에 없는 기관이 진료과 목록에 나타나도 중단합니다.
- 기존 활성 자료에서 빠진 건수가 `max(100, 기존 활성 건수의 5% 내림)`보다 크면 대량 비활성화를 중단합니다. 실제 원본 변화인지 확인한 뒤 수집 범위와 자료를 검토하며, 한 번 실패했다는 이유로 DB를 비우지 않습니다.
- 로컬 수집 중 연결 오류가 나면 같은 디렉터리로 재실행해 검증된 페이지를 이어서 사용할 수 있습니다. 날짜가 지난 자료나 변한 원본 총건수 때문에 검증이 실패하면 새 디렉터리에서 다시 수집합니다. GitHub runner의 파일은 다음 실행에 자동 보존되지 않으므로 원격 재실행은 새 수집으로 진행합니다.
- 수집 실패는 웹의 실시간 기본 API 조회로 전환하지 않습니다. DB 적용 실패 시 같은 최신 검증 자료로 재실행하고 `COMPLETE`와 최종 검증 건수를 확인합니다. 회원·예약·상세 캐시를 초기화하지 않습니다.

코드·테스트 통과, 실제 초기 적재, 예약 실행의 첫 성공, 운영 웹 반영은 별도 확인 항목입니다. 문서나 워크플로 파일이 추가됐다는 사실만으로 운영 저장·스케줄 실행이 완료됐다고 판단하지 않습니다.
