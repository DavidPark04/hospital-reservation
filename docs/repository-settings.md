# 저장소 설정 현황

현재 저장소는 [medipick-dev/hospital-reservation](https://github.com/medipick-dev/hospital-reservation)입니다. 2026-11-25 확인 기준 공개 조직 저장소로 이전했으며, 아래 초기 설정·소유자 기록은 당시 이력입니다. 현재 이전 상태는 마지막 절에 기록합니다.

아래 표는 2026-11-20 초기 설정 이력입니다. 팀원 승인 대기 없이 병합하도록 필수 승인 수를 0명으로 변경했습니다.

| 항목 | 설정 |
| --- | --- |
| 저장소 | [DavidPark04/hospital-reservation](https://github.com/DavidPark04/hospital-reservation), 비공개 |
| 기본 브랜치 | `main` |
| 통합 브랜치 | `develop` |
| 공동 작업자 | `kdh0381` 초대 발송, 수락 대기. 수락 후 공동 작업 가능 |
| PR 필수 | `main`, `develop` 모두 적용 |
| 승인 | 필수 승인 0명, 상대 리뷰 선택, 작성자 병합 가능 |
| 검사 | `Repository checks` 성공, 최신 대상 브랜치 반영 |
| 리뷰 대화 | 해결 필수 |
| 강제 push·삭제 | 보호 브랜치에서 금지 |
| 관리자 | 초기 설정 완료 후 같은 보호 규칙 적용 |
| 병합 방법 | Squash·Merge commit 허용, Rebase merge 비활성 |
| 브랜치 자동 삭제 | 비활성. 작업 브랜치만 병합 후 직접 정리하여 `develop` 유지 |
| 작업 관리 | 초기 Issue 8개와 영역·우선순위 라벨 |

초기 설정 당시 보호 API에서 `main`·`develop`의 필수 승인 수 0명을 확인했습니다. 당시 기록에는 PR 사용, 최신 대상 브랜치 반영, `Repository checks`, 리뷰 대화 해결, 관리자 적용 및 강제 push·삭제 제한이 포함됩니다.

초기 CI는 저장소 구조·문서 상대 링크·PR 제목·병합 경로를 확인했습니다. Java·React 앱 검사는 초기 설정 Issue #1·#2에서 계획했습니다. 처음 워크플로 파일만 등록한 실행은 검사 스크립트 미등록으로 실패했고, 전체 파일을 올린 뒤 두 브랜치에서 성공을 확인했습니다.

설정 화면: [브랜치 보호](https://github.com/medipick-dev/hospital-reservation/settings/branches) · [공동 작업자](https://github.com/medipick-dev/hospital-reservation/settings/access) · [Actions](https://github.com/medipick-dev/hospital-reservation/actions)

기준: [GitHub 보호 브랜치 안내](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches). GitHub 요금제·설정이 달라지면 실제 적용 상태를 다시 확인합니다.


## 기능 통합 후 확인 사항

초기에는 운영 배포와 브랜치 동기화를 별도 PR로 진행했습니다. [초기 릴리스 동기화 PR #15](https://github.com/DavidPark04/hospital-reservation/pull/15)

이후 `Application checks`에 `Frontend build`와 `Backend tests`를 추가했습니다. 백엔드 검사에는 임시 MongoDB replica set을 사용하는 테스트, 테스트 생략 여부 확인, Docker 이미지 빌드가 포함됩니다.

2026-11-21 현재 저장소는 **공개**이며 `kdh0381` 공동 작업자 권한으로 PR 작성·검사 확인·병합을 진행합니다. GitHub 저장소 소유자는 `DavidPark04`로 유지합니다.

메디픽 UI를 운영에 반영한 프로젝트는 `0381kdh-8885/medipick`입니다. [운영 반영 PR #42](https://github.com/DavidPark04/hospital-reservation/pull/42) · [메디픽 운영 사이트](https://medi-pick.vercel.app)

GitHub Actions의 배포 작업은 `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID`, 프로젝트 범위의 60일 `VERCEL_TOKEN`을 사용합니다. 서버의 DB·OAuth·외부 API 키는 Vercel Production 환경변수로 관리합니다. 병원 목록을 동기화하는 Actions와 Spring API는 같은 MongoDB Atlas `MediPick` 데이터베이스를 사용합니다. [배포 및 환경변수 안내](../infra/README.md)

## 현재 브랜치 운영

2026-11-21에 공동 작업자 계정으로 조회한 `main`·`develop`의 브랜치 보호 API는 HTTP 404를, 브랜치에 적용되는 규칙 조회는 빈 목록을 반환했습니다. 404 응답만으로 서버 측 보호 여부를 확정할 수 없습니다. [현재 브랜치 설정](https://github.com/medipick-dev/hospital-reservation/settings/branches)

기능·긴급 수정 모두 `main`으로 PR 한 개를 열고 스쿼시 병합합니다. `Vercel production`은 같은 `main` 커밋의 push에서 저장소 검사와 앱 검사가 모두 성공해야 `develop`을 fast-forward하고 그 커밋을 배포합니다. 이를 위해 Actions에 `develop` 참조를 갱신할 쓰기 권한이 필요합니다. 실행 결과와 배포 SHA는 Actions에서 확인합니다. [Vercel production 실행 기록](https://github.com/medipick-dev/hospital-reservation/actions/workflows/vercel-production.yml)

기존 방식에서 전환할 때는 두 브랜치의 파일 트리가 같은지 확인한 뒤 시작점을 `main`의 같은 SHA로 한 번 맞춥니다. 이후 두 브랜치 이력이 갈라지면 워크플로가 동기화와 배포를 중단합니다. [브랜치 운영 절차](branch-strategy.md)

## 조직 저장소 이전

2026-11-25 GitHub API에서 기존 저장소와 같은 ID `1465066975`의 소유자가 `medipick-dev` 조직으로 변경된 것을 확인했습니다. 현재 주소는 `medipick-dev/hospital-reservation`이며 공개 상태입니다. 이전 주소의 리디렉션과 별개로 Actions의 `github.repository`에는 새 소유자가 포함되므로 운영 배포·자정 수집의 저장소 조건과 배포 준비 스크립트를 새 주소에 맞춥니다.

배포·일일 수집의 성공 여부는 이전 사실만으로 판단하지 않고 해당 워크플로 실행 결과로 확인합니다. Vercel 운영 주소와 MongoDB 데이터베이스 이름은 저장소 이전으로 바뀌지 않습니다.
