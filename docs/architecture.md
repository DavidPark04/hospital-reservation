# 아키텍처

[기준 기획서](https://app.notion.com/p/879b8822a70f82f789298129ce73194d)의 범위를 바탕으로 로컬에서 구현한 앱을 통합했습니다. 현재 구성과 초기 계획을 구분하며, 운영 연결 결과는 [배포 안내](../infra/README.md)에 기록합니다.

```mermaid
flowchart LR
    Browser[브라우저] --> Vercel[Vercel 단일 도메인]
    Vercel --> FE[Vite 정적 화면]
    Vercel --> BE[Java 21 Spring 컨테이너]
    BE --> DB[(MongoDB Atlas)]
    BE --> Detail[HIRA 상세 3종 / 필요 시]
    Sync[GitHub Actions / 매일 00시 KST 예정] --> Basic[HIRA 병원 기본 API]
    Sync --> DB
    BE --> AI[OpenAI]
    BE --> OAuth[Google / Kakao]
    FE --> Maps[Kakao 지도 SDK / 외부 길찾기]
```

- `frontend/`: React·TypeScript·Vite·TanStack Query. 상담·검색·예약·가입·마이페이지·관리자 UI와 서버 상태 관리.
- `backend/`: Java 21·Spring Boot 3·Security·Spring Data MongoDB·WebClient. JWT 인증·권한, 외부 API, 예약 정합성.
- MongoDB Atlas: 전국 병원 기본 카탈로그, 선택 병원의 상세 캐시·근거, 저장된 가상 의료진·시간표, 가입 완료 회원, 만료되는 소셜 가입·OAuth 임시 상태, 예약·요청 키·변경 이벤트.
- Vercel: 같은 프로젝트의 frontend와 backend services로 구성합니다. Java는 Container Runtime을 사용하며 기존 Oracle 배포 계획은 사용하지 않습니다.
- GitHub Actions: 기본 병원 목록의 초기 적재·일일 동기화. 기본 API 전체 목록과 서비스에서 지원하는 진료과 필터 목록을 수집·검증한 뒤 MongoDB 변경분을 적용합니다. Vercel 운영 API를 경유하거나 매일 재배포하지 않습니다.

## 코드 경계

Backend 패키지는 `auth`, `member`, `integration`, `booking`, `common`, `config`입니다. 기능별 controller가 service를 호출하고 service는 MongoTemplate·외부 API client를 사용합니다. HTTP 공개 필드와 저장 문서를 구분하며 비밀번호·소셜 식별번호·인증 토큰은 회원 목록 응답에 포함하지 않습니다.

Frontend는 `App.tsx`의 페이지 흐름, 소셜 가입 전용 컴포넌트, `ScrollStory.tsx`, 공통 요청 `api.ts`로 구성합니다. 기획 단계의 repository/DTO 및 features/pages 세분화는 현재 구현과 동일하지 않으며, 기존 기능을 유지한 채 필요한 범위에서 조정합니다.

병원 상세 지도는 브라우저의 카카오 Web SDK로 표시합니다. 서버가 제공한 병원 좌표를 우선 사용하며 주소 변환도 SDK의 `services` 라이브러리로 처리합니다. 외부 길찾기는 카카오맵으로 연결하고 현재 위치를 수집하지 않습니다. 공개 JavaScript 키만 frontend 빌드 변수에 두며 서버의 카카오 REST API 키·Client Secret은 기존 인증 설정에 유지합니다.

## 병원 목록과 상세 정보

검색·홈·AI 추천의 병원 후보는 `hospitalBasicCatalog`에서 조회합니다. 방문자가 검색할 때 기본 API를 호출하지 않습니다. 일일 수집은 기본 목록의 `dgsbjtCd` 조건으로 진료과별 소속 병원도 확인하므로, 전국 병원의 상세 3종을 일괄 호출하지 않고 진료과 검색을 준비할 수 있습니다.

병원 상세를 열면 기본 카탈로그와 기존 상세 캐시를 합칩니다. 필요한 병원만 `hospitals`에 저장해 예약·가상 의료진의 참조를 유지하며, HIRA 상세 3종의 1시간 캐시 정책은 유지합니다. `hospitalBasicCatalog` 전체를 서버 시작 때 병원별로 정리하거나 `hospitalEvidence`에 복제하지 않습니다.

일일 동기화는 매일 한국 시간 00시로 예약하며, 실제 실행은 GitHub 스케줄 상황에 따라 늦어질 수 있습니다. 수집·검증 후 변경된 기본 문서만 수정하고, 전체 수집이 정상 완료된 경우에만 누락 자료를 비활성화합니다. 회원·예약은 이 일일 수집 대상이 아닙니다. 상세 흐름과 실패 복구는 [병원 목록 운영 안내](hospital-catalog.md)를 참고합니다.

## 인증과 데이터

일반 가입은 아이디·비밀번호·이름·이메일·전화번호를 받습니다. 구글·카카오는 고유 식별번호만 이용하며 이름·이메일·전화번호를 추가 입력받습니다. 가입 방식과 이름에 관계없이 정규화한 전화번호 또는 이메일 중 하나라도 이미 등록돼 있으면 중복 가입을 차단합니다. 기존 가입 방식은 이름과 전화번호가 모두 일치할 때만 안내하고, 나머지 중복은 가입 방식을 포함하지 않는 일반 안내를 표시합니다. 이메일·SMS 인증과 계정 자동 연결은 제공하지 않습니다. 기존 회원의 누락된 이메일은 그대로 보존합니다.

가입 완료 전 소셜 인증은 만료되는 별도 임시 저장소에서 관리하며 `users` 회원이나 관리자 목록에 넣지 않습니다. 가입 완료 시 정식 회원 생성과 임시 자료 소모를 트랜잭션으로 처리합니다. OAuth 요청과 교환 티켓도 서버 교체·확장에 대비해 공유 저장소에서 유효기간과 일회성을 검사합니다.

예약은 조건부 정원 갱신과 MongoDB 다중 문서 트랜잭션으로 처리합니다. 증상 원문은 저장하지 않습니다. AI는 실제 조회한 병원 후보만 사용하며, 장애 시 직접 검색 또는 명시된 캐시·샘플 상태를 표시합니다. 의료진·시간표는 가상이며 실제 병원 접수를 제공하지 않습니다.

API·인증·DB·배포 설정을 변경하면 같은 PR에 관련 문서와 검증 결과를 기록합니다. [API 계약](api-contract.md), [DB 설계](database.md), [구현 상세](implementation-contract.md), [프로젝트 계획서](project-plan.md)를 함께 참고합니다.
