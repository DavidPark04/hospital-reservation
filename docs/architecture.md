# 아키텍처 기준

기획 단계의 구조입니다. 실행되는 시스템이 아직 아닙니다.

- `frontend/`: React·TypeScript·Vite. 사용자 상담·검색·일정 선택·예약·관리자 화면.
- `backend/`: Java 21·Spring Boot·Security·Spring Data MongoDB. 인증·권한, 외부 API, 예약 정합성.
- MongoDB: 외부 병원 식별자·근거, 샘플 의료진·시간표, 회원·예약. 다중 문서 트랜잭션이 가능한 환경을 검증.
- 외부 API: OpenAI와 심평원은 백엔드만 호출. 병원 후보는 실제 조회 결과에서 선택하고 출처·확인 시점을 전달.
- 배포: Vercel FE와 Render BE 후보. 도입·비용·버전은 확정 전.

## 코드 경계

Backend 도메인 후보: `auth`, `user`, `hospital`, `consultation`, `doctor`, `schedule`, `reservation`, `admin`, `global`.
도메인 안에서 controller, service, repository, document, dto를 분리합니다. Spring 기본 패키지명은 초기화 PR에서 고정합니다.

Frontend 기능 후보: `auth`, `hospital-search`, `consultation`, `reservation`, `my-reservations`, `admin`.
라우트는 pages, 재사용 UI는 components, 공통 API·환경 설정은 lib에 둡니다.

## 함께 검토할 변경

API 필드·오류 코드, 인증·CORS, DB 인덱스·트랜잭션, 예약 상태 전이, 환경변수, CI·배포 설정은 상대 리뷰가 필요합니다. 공용 파일의 큰 수정 전에 Issue로 영향을 알립니다.

증상 원문은 MVP 기본 미저장입니다. AI 입력 최소화·외부 호출 실패 처리·조회 목록 밖 병원 제거는 구현과 테스트에서 확인합니다.
