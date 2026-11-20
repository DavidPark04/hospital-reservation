# API 계약 — 설계 초안

현재 엔드포인트는 구현되지 않았습니다. 아래는 FE/BE 합의를 시작하기 위한 제안이며 Issue #3에서 확정합니다. 모든 API는 `/api/v1`을 제안합니다.

| 기능 | 메서드·경로 제안 | 권한 |
| --- | --- | --- |
| 회원가입 | POST `/auth/signup` | 비회원 |
| 로그인 | POST `/auth/login` | 비회원 |
| 병원 검색·상세 | GET `/hospitals`, `/hospitals/{id}` | 전체 |
| 상담 조건 추출 | POST `/consultations` | 전체, 사용량 제한 검토 |
| 샘플 의료진·시간표 | GET `/hospitals/{id}/doctors`, `/doctors/{id}/slots` | 전체 |
| 예약 생성 | POST `/reservations` | 회원 |
| 내 예약 | GET `/me/reservations` | 본인 |
| 예약 변경·취소 | PATCH `/reservations/{id}`, POST `/reservations/{id}/cancel` | 본인 |
| 관리자 일정 | POST/PATCH `/admin/slots` | 관리자 |

각 엔드포인트에 요청·응답 JSON, 필드 타입, 필수 여부, 인증 방식, HTTP 상태·오류 코드를 적습니다. 목록의 페이지 규칙, 시간대·날짜 형식, 빈 결과·외부 API 장애 표시를 통일합니다.

예약 생성의 `Idempotency-Key`, 변경·취소 재시도 정책, JWT 만료·갱신·로그아웃 방식은 구현 전 합의합니다. 프론트엔드는 API 계약에 맞춘 모의 응답을 사용하고, 실제 서버 연동 완료 여부를 별도로 표시합니다.
