# 진행 현황과 다음 작업

2026-11-20 기준.

## 준비된 것

- 노션 기준 기획서와 4주 일정에 맞춘 README, 계획·설계·협업 문서
- FE/BE/공통 문서를 구분하는 폴더
- `main`·`develop`·작업 브랜치 운영 규칙
- PR·Issue 템플릿, 저장소 검사 CI
- Vercel 사전 배포용 정적 안내 화면과 설정 (`frontend/public`, `frontend/vercel.json`)

원격 저장소·초대·브랜치 보호 적용 결과는 [repository-settings.md](repository-settings.md)에 별도로 기록합니다.

## 시작할 작업

| Issue | 작업 | 계획 기간·확인일 | 완료 증거 |
| --- | --- | --- | --- |
| [#1](https://github.com/DavidPark04/hospital-reservation/issues/1) | BE 초기화 | 1주차 준비, 2주차(11/23~29) 실행 환경 확정 | Java 21·Spring Boot 3 고정, Gradle Wrapper, 로컬 실행·테스트·CI |
| [#2](https://github.com/DavidPark04/hospital-reservation/issues/2) | FE 초기화 | 1주차 준비, 2주차(11/23~29) 실행 환경 확정 | React·TypeScript·Vite·TanStack Query, lockfile, lint·build·CI |
| [#3](https://github.com/DavidPark04/hospital-reservation/issues/3) | API·MongoDB 계약 | 11/20~22 초안, 11/25 API·DB 점검 | 요청·응답·오류·권한·인덱스·예약 상태·요청 키 합의 |
| [#4](https://github.com/DavidPark04/hospital-reservation/issues/4) | 공공 API 승인·실조회 확인 | 11/20~22 신청, 11/25 승인·응답 확인 | 서비스별 승인 상태, 샘플 요청·응답·한계 |
| [#5](https://github.com/DavidPark04/hospital-reservation/issues/5) | 인증·권한 | 11/23~29 구현, 12/3~5 통합 검증 | 본인 예약·관리자 권한·로그아웃 실패 경로 테스트 |
| [#6](https://github.com/DavidPark04/hospital-reservation/issues/6) | 가상 일정·예약 정합성 | 11/23~29 구현, 12/3~5 통합 검증 | 동시 요청·중복·취소·변경 실패·관리자 정원·휴진 테스트 |
| [#7](https://github.com/DavidPark04/hospital-reservation/issues/7) | 사용자 흐름·AI 통합 | 11/23~12/2 구현·AI 결정, 12/3~5 통합 검증 | 직접 검색과 상담의 전체 흐름, 근거·장애 처리·모바일 화면 |
| [#8](https://github.com/DavidPark04/hospital-reservation/issues/8) | 배포·제출 준비 | 11/30 배포 시작, 12/1 보고서 시작, 12/8 준비 완료 | 실제 URL, 환경설정, 보고서·시연·기여, 과목 제출 공지 확인 |

현재 정적 안내 화면만 준비되어 있으며 서비스 기능 구현·앱 빌드·백엔드 배포는 미완료입니다. UI 설계안이나 저장소 검사 성공을 실제 기능 구현 완료로 기록하지 않습니다.

공통 기준은 **12/2 기능 동결 → 12/5 통합 검증 → 12/8 준비 완료 → 12/9 기말고사**입니다. 보고서·시연 준비를 마지막 주에 모두 시작하지 않도록 배포·보고서 작업을 병행합니다. 기획서의 로컬 MongoDB PoC 10개 시나리오 기록은 참고 근거이며, Spring·Atlas·실제 인증 API 검증은 미완료입니다.
