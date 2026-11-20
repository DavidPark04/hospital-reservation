# 아키텍처 기준

노션 [기준 기획서](https://app.notion.com/p/879b8822a70f82f789298129ce73194d)를 반영한 기획 단계의 구조입니다. 실행되는 시스템이 아직 아닙니다.

- `frontend/`: React·TypeScript·Vite·TanStack Query. 사용자 상담·검색·일정 선택·예약·관리자 화면과 서버 상태 관리.
- `backend/`: Java 21·Spring Boot 3·Security·Spring Data MongoDB·WebClient. JWT 인증·권한, 외부 API, 예약 정합성.
- MongoDB Atlas: 외부 병원 식별자·근거, 저장된 가상 의료진·시간표, 회원·예약·요청 키·변경 이벤트. 로컬 replica set 및 Atlas에서 다중 문서 트랜잭션을 검증.
- 외부 API: OpenAI와 심평원은 백엔드만 호출. 병원 후보는 실제 조회 결과에서 선택하고 출처·확인 시점을 전달.
- 배포: Vercel FE와 Render BE 후보. 비용·시작 지연을 확인하고 11/30부터 구성. 테스트·접속 검증 후 배포하며 자동화는 선택.

## 코드 경계

Backend 도메인 후보: `auth`, `user`, `hospital`, `consultation`, `doctor`, `schedule`, `reservation`, `admin`, `global`.
도메인 안에서 controller, service, repository, document, dto를 분리합니다. Spring 기본 패키지명은 초기화 PR에서 고정합니다.

Frontend 기능 후보: `auth`, `hospital-search`, `consultation`, `reservation`, `my-reservations`, `admin`.
라우트는 pages, 재사용 UI는 components, 공통 API·환경 설정은 lib에 둡니다.

## 함께 검토할 변경

API 필드·오류 코드, 인증·CORS, DB 인덱스·트랜잭션, 예약 상태 전이, 환경변수, CI·배포 설정은 상대 리뷰가 필요합니다. 공용 파일의 큰 수정 전에 Issue로 영향을 알립니다.

증상 원문은 MVP 기본 미저장입니다. AI 입력 최소화·조회 목록 밖 병원 제거를 구현과 테스트에서 확인합니다. AI 실패 시 직접 검색, 병원 API 실패 시 마지막 정상 캐시·확인일 또는 재시도 안내를 제공합니다.

공공 API 실제 인증 응답은 11/25, AI 연동 여부는 12/2에 확인합니다. 기획서에 기록된 로컬 MongoDB PoC 결과와 이 저장소의 Spring·Atlas·외부 API 통합 검증은 구분합니다. 상세 기준은 [DB 설계](database.md), 일정은 [프로젝트 계획서](project-plan.md)를 따릅니다.
