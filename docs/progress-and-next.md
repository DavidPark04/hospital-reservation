# 진행 현황과 다음 작업

2026-11-21 기능 통합·배포 확인 기준입니다. 원래 4주 계획과 Issue는 유지하며, 아래는 실제 구현 상태입니다. 로컬 검증·CI 통과·운영 기능 확인을 구분합니다.

## 통합한 기능

- Java 21·Spring Boot·MongoDB 및 React·TypeScript·Vite·TanStack Query 실행 코드와 lockfile/Gradle Wrapper
- 아이디 회원가입·로그인·로그아웃, 구글/카카오 식별번호 기반 로그인, 이름·이메일·전화번호 추가 입력
- 전화번호 또는 이메일 중 하나라도 일치하면 중복 가입 차단, 이름·전화번호가 모두 일치할 때만 기존 가입 방식 안내, 일반 가입 이메일 필수 입력
- 가입 미완료 자료의 별도 만료 저장소, 가입 완료 시 정식 회원 생성, 관리자 목록에서 미완료 자료 제외
- 실제 병원·진료과 조회와 근거·조회 시점, AI 상담과 명시된 장애 대체 흐름
- 저장된 가상 의료진·일정, 모의 예약·변경·취소, 관리자 정원·휴진·회원 목록·회원 삭제
- 스크롤 애니메이션과 반응형 UI, 마이페이지
- MongoDB replica set 기반 정합성 테스트 및 프론트 빌드 CI 구성

이 코드는 로컬에서 개발·검증한 기능을 저장소로 옮긴 것입니다. 현재 브랜치의 CI 실행 결과와 운영 URL 반영 여부는 해당 PR·배포 기록에서 확인합니다. 실제 사용자 계정을 자동으로 새로 가입시키지 않으며 실계정 최종 테스트는 사용자 진행 결과와 구분합니다.

## 자동 검증

전화번호·이메일 중복 차단 수정본의 로컬 JUnit 155개가 실패·오류·생략 없이 통과했고 Gradle bootJar, 프론트 npm ci 및 TypeScript/Vite 빌드가 성공했습니다. 격리된 로컬 MongoDB에서 가입·중복 안내·로그인·권한·예약 HTTP 검사 26개도 통과했습니다. OAuth state·교환 티켓의 인스턴스 간 처리와 MongoDB 공유 상담 횟수 제한을 포함합니다. 기존 Atlas의 실제 회원을 자동 가입·삭제하지 않았으며, 원격 CI·컨테이너 빌드와 수정본 운영 반영은 해당 PR·배포 기록에서 별도로 확인합니다.

## 배포 진행

운영 주소를 [hospital-reservation-app.vercel.app](https://hospital-reservation-app.vercel.app)으로 변경했습니다. 김도형의 `0381kdh-8885/hospital-reservation-app` Vercel 프로젝트에서 React frontend와 Java Container backend를 함께 운영하며, 기존 MongoDB Atlas를 사용합니다. Oracle 계획은 사용하지 않습니다.

2026-11-21 첫 배포에서 화면·정적 자원, 병원 검색, MongoDB 상태 `UP`, 비로그인 접근 차단, 구글·카카오 인증 시작의 새 callback 주소 등 HTTP 검사 10개를 통과했습니다. Production 환경변수와 GitHub Actions 배포용 비밀값을 등록했으며, 자동 배포 토큰은 해당 프로젝트에만 접근하는 60일 토큰입니다. 새 주소의 실계정 소셜 로그인 완료 검증은 사용자가 Google/Kakao 콘솔에 운영 callback을 등록한 뒤 진행합니다.

`main`의 `Application checks`가 성공하면 같은 커밋을 새 Vercel 프로젝트에 배포합니다. 기존 Vercel 기본 Git 자동 배포는 `git.deploymentEnabled=false`로 중지하며 기존 사이트 자체는 삭제하지 않습니다. 동작과 재배포·토큰 교체 방법은 [배포 안내](../infra/README.md)를 따릅니다.

PR #15는 사전 배포 릴리스 이력 동기화로 이미 병합되었습니다. 이번 기능 통합은 그 이후의 새 PR에서 검토·통합하며 관련 Issue의 완료 여부는 아래 증거와 함께 판단합니다.

| Issue | 현재 코드·남은 확인 |
| --- | --- |
| [#1](https://github.com/DavidPark04/hospital-reservation/issues/1) BE 초기화 | 실행 코드·Wrapper·테스트 포함. 새 브랜치 CI 결과 확인 |
| [#2](https://github.com/DavidPark04/hospital-reservation/issues/2) FE 초기화 | 앱·lockfile·TypeScript/Vite 빌드 포함. 별도 lint 명령·폴더 세분화는 미적용 |
| [#3](https://github.com/DavidPark04/hospital-reservation/issues/3) API·DB | 실제 경로·회원·임시 가입·예약 인덱스·트랜잭션 문서 반영 |
| [#4](https://github.com/DavidPark04/hospital-reservation/issues/4) 공공 API | 조회·캐시·진료과 근거 구현. 운영 키 설정 후 실제 응답 확인 필요 |
| [#5](https://github.com/DavidPark04/hospital-reservation/issues/5) 인증·권한 | 일반/소셜·관리자·가입 중복 구현. 운영 callback·실계정 흐름 확인 필요 |
| [#6](https://github.com/DavidPark04/hospital-reservation/issues/6) 예약 정합성 | 트랜잭션·중복 요청·정원·소유권 테스트 포함. 운영 시연 별도 |
| [#7](https://github.com/DavidPark04/hospital-reservation/issues/7) 사용자 흐름·AI | 상담·직접 검색·예약 UI 구현. 운영 API 연결과 사용자 시연 확인 필요 |
| [#8](https://github.com/DavidPark04/hospital-reservation/issues/8) 배포·제출 | 새 Vercel 주소의 화면·API·DB 연결 확인. 수정본 자동 배포 결과·실계정 소셜 로그인·결과보고서·발표·시연 영상·제출 공지 확인은 남음 |

## 유지하는 범위와 일정

실제 병원 접수·진단·처방·결제는 제공하지 않습니다. 의료진·시간표는 가상이고 회원의 이메일·전화번호를 인증하지 않습니다. 상담 이력·이메일 알림·병원평가·NMC 확장은 미구현입니다.

기준 일정은 **12/2 기능 동결 → 12/5 통합 검증 → 12/8 준비 완료 → 12/9 기말고사**입니다. 원래 계획·팀원 협업 기록을 기능 통합으로 삭제하지 않습니다. [프로젝트 계획서](project-plan.md), [배포 안내](../infra/README.md), [저장소 설정 이력](repository-settings.md)을 함께 확인합니다.
