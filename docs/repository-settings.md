# 저장소 설정 현황

아래 표는 2026-11-20 초기 설정 이력입니다. 팀원 승인 대기 없이 병합하도록 필수 승인 수를 0명으로 변경했습니다. 이후 공개 전환·자동 배포 상태는 마지막 절에 기록합니다.

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

보호 API에서 `main`·`develop`의 필수 승인 수 0명을 확인했습니다. PR 필수, 최신 대상 브랜치 반영, `Repository checks`, 리뷰 대화 해결, 관리자 적용, 강제 push·삭제 금지는 유지합니다. 초기 파일 등록 단계만 직접 커밋했으며, 이후 개발은 PR로 진행합니다.

현재 CI는 저장소 구조·문서 상대 링크·PR 제목·병합 경로를 확인합니다. 실제 Java·React 앱 테스트와 빌드는 초기 설정 Issue #1·#2에서 추가합니다. 처음 워크플로 파일만 등록한 실행은 검사 스크립트 미등록으로 실패했으며, 전체 파일을 올린 뒤 두 브랜치에서 성공을 확인했습니다.

설정 화면: [브랜치 보호](https://github.com/DavidPark04/hospital-reservation/settings/branches) · [공동 작업자](https://github.com/DavidPark04/hospital-reservation/settings/access) · [Actions](https://github.com/DavidPark04/hospital-reservation/actions)

기준: [GitHub 보호 브랜치 안내](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches). GitHub 요금제·설정이 달라지면 실제 적용 상태를 다시 확인합니다.


## 기능 통합 후 확인 사항

위 표는 초기 설정 당시의 이력입니다. 기능 통합 작업은 GitHub 쓰기 권한으로 진행하며 실제 보호 규칙·최신 커밋 검사 상태는 병합 시 다시 확인합니다. 초기 #15 릴리스 동기화 PR은 이미 병합되어 이번 변경은 새 PR로 진행합니다.

기존 `Repository checks` 외에 `Application checks`에서 `Frontend build`, `Backend tests`를 추가했습니다. Java 테스트에는 임시 MongoDB replica set과 생략 검사, backend Docker build가 포함됩니다. 새 CI 정의를 추가했다는 사실과 GitHub 브랜치 보호의 필수 검사 항목을 변경했다는 사실은 구분합니다. 이 문서 갱신으로 서버 보호 설정을 변경하지 않았습니다.

2026-11-21 현재 저장소는 **공개**이며 `kdh0381` 공동 작업자 권한으로 PR 작성·검사 확인·병합을 진행합니다. GitHub 저장소 소유자는 `DavidPark04`로 유지합니다.

운영 대상은 김도형 소유의 `0381kdh-8885/hospital-reservation-app` Vercel 프로젝트와 [hospital-reservation-app.vercel.app](https://hospital-reservation-app.vercel.app)입니다. `main` 앱 검사 성공 후 GitHub Actions가 해당 프로젝트에 배포하도록 구성했습니다. 저장소 Actions에는 `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID`, 프로젝트 범위의 60일 `VERCEL_TOKEN`을 암호화해 저장하고, DB·OAuth·외부 API 키는 Vercel Production 환경변수로 관리합니다. 이 문서에는 실제 비밀값을 기록하지 않습니다. 설정과 재배포 방법은 [배포 안내](../infra/README.md)를 따릅니다.
