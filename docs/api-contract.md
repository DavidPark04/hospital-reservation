# API 계약

현재 구현 기준입니다. 초기 기획의 경로 제안을 아래 실제 경로로 확정했습니다. 기본 경로는 `/api`, 일반 인증은 `Authorization: Bearer <JWT>`입니다. JSON 날짜는 UTC ISO 형식이며 화면에서 한국 시간으로 표시합니다. 상세 응답 필드는 [구현 상세](implementation-contract.md), 실행 중 명세는 `/swagger-ui.html`에서 확인합니다.

| 기능 | 메서드·경로 | 권한 |
| --- | --- | --- |
| 상태·설정 | GET `/api/health`, `/api/meta` | 전체 |
| 일반 가입 | POST `/api/auth/register` | 비회원 |
| 아이디 로그인 | POST `/api/auth/login` | 비회원 |
| 내 정보·로그아웃 | GET `/api/auth/me`, POST `/api/auth/logout` | 회원 또는 유효한 가입 임시 토큰 |
| 소셜 제공자 | GET `/api/auth/social/providers` | 전체 |
| 소셜 로그인 시작 | GET `/oauth2/authorization/{google\|kakao}` | 전체 |
| 제공자 콜백 | GET `/login/oauth2/code/{google\|kakao}` | OAuth state 검증 |
| 소셜 결과 교환 | POST `/api/auth/social/exchange` | 같은 origin·일회용 HttpOnly 교환 쿠키 |
| 소셜 가입 완료 | POST `/api/auth/social/profile` | 유효한 가입 임시 토큰 |
| 일반 회원 탈퇴 | DELETE `/api/members/me` | LOCAL USER, 현재 비밀번호 확인 |
| 소셜 탈퇴 인증 시작 | POST `/api/auth/social/withdrawal/start` | 가입 완료 GOOGLE/KAKAO USER |
| 소셜 탈퇴 확정 | POST `/api/auth/social/withdrawal/confirm` | 시작한 회원의 JWT·재확인 증명 쿠키 |
| 소셜 탈퇴 취소 | POST `/api/auth/social/withdrawal/cancel` | 현재 회원의 JWT |
| 병원 검색·상세 | GET `/api/hospitals`, `/api/hospitals/{id}` | 전체 |
| AI 상담 | POST `/api/chat` | 전체, 서버 사용량 제한 적용 |
| 가상 의료진 | GET `/api/hospitals/{id}/doctors?department=01` | 전체 |
| 가상 일정 | GET `/api/slots?doctorId=...&date=YYYY-MM-DD` | 전체 |
| 내 예약·생성 | GET·POST `/api/reservations` | 가입 완료 회원 |
| 예약 변경·취소 | PATCH·DELETE `/api/reservations/{id}` | 예약 소유자 |
| 관리자 일정 | GET·POST `/api/admin/slots`, PATCH `/api/admin/slots/{id}` | ADMIN |
| 회원 목록·삭제 | GET `/api/admin/members`, DELETE `/api/admin/members/{id}` | ADMIN |

## 가입과 로그인

일반 가입 요청은 `{name,loginId,password,email,phone}`, 로그인은 `{loginId,password}`입니다. 신규 가입의 이메일과 전화번호는 모두 필수입니다. 아이디는 소문자로 정규화합니다. 신규 연락처는 공백·하이픈을 제거하고 0으로 시작하는 국내 9~11자리 숫자로 저장합니다. 이메일은 앞뒤 공백을 제거해 저장하며 중복 검사에는 소문자로 정규화한 `emailNormalized`를 사용합니다. 기존 이메일·전화번호가 없는 LOCAL 회원의 로그인은 유지하고 누락된 값을 임의로 채우지 않습니다.

로그인 응답은 `{token,user}`입니다. 공개 User 필드는 `id,name,loginId,role,authProvider,profileComplete,emailVerified`와 선택 `email,phone`입니다. 비밀번호 해시, 제공자 식별번호, 제공자 토큰, 프로필 이미지는 공개하지 않습니다.

Google은 `openid`만 요청하고 Kakao는 별도 개인정보 scope를 요청하지 않습니다. 최초 소셜 인증은 임시 가입 상태만 만들며 `users`에는 등록하지 않습니다. 임시 응답은 빈 이름·아이디, `profileComplete:false`이고 이메일·전화번호가 없습니다. 임시 JWT는 상태 조회·가입 완료·로그아웃에만 사용할 수 있습니다.

가입 완료 요청은 `{name,email,phone}`입니다. 입력 검증과 중복 검사를 통과하면 정식 회원 생성과 임시 자료 소모를 하나의 트랜잭션으로 처리하고 새 회원 JWT를 반환합니다. 기존 임시 JWT는 회원 JWT로 바뀌지 않으며 재사용할 수 없습니다. 이미 가입된 같은 제공자 계정으로 로그인하면 직접 입력한 회원 정보를 유지합니다.

일반·구글·카카오 가입을 통틀어 정규화한 전화번호 또는 이메일 중 하나라도 기존 회원과 일치하면 HTTP 409를 반환합니다. 이름은 가입 차단 여부에 영향을 주지 않습니다. 기존 가입 방식은 정규화한 이름과 전화번호가 모두 같은 회원이 있을 때만 아래 응답으로 안내합니다. 전화번호와 이메일이 각각 다른 계정과 일치할 때도 이름·전화번호가 함께 일치하는 계정에만 이 안내를 적용합니다.

```json
{
  "message": "이미 카카오로 가입된 계정이 있어요. 카카오로 로그인해 주세요.",
  "code": "MEMBER_ALREADY_EXISTS",
  "existingAuthProvider": "KAKAO"
}
```

구글은 구글 로그인 안내, 일반 회원은 “이미 일반 가입으로 등록된 계정이 있어요. 아이디로 로그인해 주세요.”를 표시합니다. `existingAuthProvider`는 `LOCAL`, `GOOGLE`, `KAKAO` 중 하나입니다. 전화번호만 같고 이름은 다르거나 이메일만 같은 경우에는 `{message:"이미 가입된 정보가 있어요. 입력 정보를 확인해 주세요."}`만 반환하며 가입 방식과 해당 로그인 버튼을 표시하지 않습니다. 별도 아이디 중복은 기존 메시지 형태의 409입니다. 전화번호·이메일 입력값의 일치 검사이며 이메일·전화번호 본인 인증 또는 자동 계정 통합이 아닙니다.

회원 로그아웃은 토큰 버전을 폐기하고, 가입 진행 중 로그아웃은 임시 가입 자료를 제거합니다. 프론트는 저장한 JWT를 지웁니다.

## 회원 탈퇴

일반 회원은 DELETE `/api/members/me`에 `{password}`를 전달합니다. 삭제 대상은 요청의 ID가 아니라 현재 JWT 회원으로 정하며 저장된 BCrypt 해시와 현재 비밀번호를 비교합니다. 비밀번호 누락은 400, 불일치는 403이고 실패 시 계정·예약을 유지합니다. 관리자 및 소셜 회원은 이 비밀번호 경로로 탈퇴할 수 없습니다.

소셜 회원은 POST `/api/auth/social/withdrawal/start`로 `{authorizationUrl}`을 받아 기존 OAuth 경로에서 가입한 계정을 다시 확인합니다. 콜백의 제공자 고유번호가 시작한 회원과 일치해야 하며 일반 로그인·가입 처리로 전환하지 않습니다. 인증 결과는 `/mypage?withdrawal=verified`로 돌아오고, 프론트는 결과 쿼리를 지운 뒤 최종 확인 창을 표시합니다. 취소·실패·다른 계정 선택 시 탈퇴하지 않습니다. 최종 POST `/api/auth/social/withdrawal/confirm`은 현재 JWT와 유효한 일회용 HttpOnly 증명 쿠키를 함께 요구합니다. OAuth 콜백 방문만으로 회원을 삭제하지 않습니다.

성공 응답은 `{message,deletedUserId,canceledReservations}`입니다. 회원정보·요청 키·로그인 교환 티켓 삭제와 활성 예약 취소·정원 반환을 한 트랜잭션으로 처리합니다. 실패하면 전체를 되돌리고, 성공하면 기존 회원 JWT가 무효화됩니다. 프론트는 세션과 회원별 캐시를 비우고 홈으로 이동합니다. 예약·이벤트 이력은 UUID로 보존합니다. 탈퇴한 이메일·전화번호는 신규 가입에 다시 사용할 수 있으며, 재가입은 새 회원 ID를 발급합니다. 카카오·구글 자체 계정 삭제나 해당 서비스의 연결 해제를 수행하는 기능은 아닙니다.

## 병원·상담

검색 파라미터는 `query`, `region`, `department`입니다. 응답은 `{items,source,message?,fetchedAt?}`이며 `source`는 `LIVE`, `CACHE`, `SAMPLE`입니다. 병원 상세에는 실제 조회한 진료과 목록과 `evidence`의 출처·조회 시점이 포함됩니다. 의료진은 `isVirtual:true`인 저장된 가상 자료입니다.

상담 요청은 `{messages:[{role,content}],region?}`, 응답은 `reply,hospitals,departmentCode?,departmentName?,needsMoreInfo,emergency,mode,source?,notice?`입니다. `mode`는 `AI` 또는 `FALLBACK`입니다. 실제 조회 병원 밖의 후보를 생성하지 않으며 증상 원문은 DB에 저장하지 않습니다. AI나 공공 API 장애를 성공 응답으로 숨기지 않습니다.

## 예약 정합성

생성·변경 본문은 `{slotId}`입니다. 생성·변경·취소 모두 `Idempotency-Key` 헤더를 전달합니다. 사용자·키와 정규화한 작업 내용의 SHA-256 해시를 저장합니다. 같은 키·같은 작업은 기존 응답, 다른 작업은 409입니다. 요청 키에는 자동 TTL을 두지 않으며 회원 삭제 시 해당 회원 키를 제거합니다.

예약 상태는 `RESERVED`, `CANCELED`입니다. 변경은 `reservationEvents.action=CHANGE`로 기록하며 `CHANGED` 상태를 만들지 않습니다. 서버에서 소유권·참조 ID·정원·휴진을 확인합니다. 정원 확보/반환, 예약·응답·이력 저장은 하나의 트랜잭션이며 실패하면 롤백합니다. 반복 취소는 정원을 두 번 반환하지 않습니다.

관리자 일정 생성 본문은 `{doctorId,date,startTime,endTime,intervalMinutes,capacity}`, 변경은 `{capacity,closed}`입니다. 기존 예약보다 작은 정원이나 예약이 있는 일정의 휴진 변경은 409로 거부합니다.

관리자 회원 목록은 가입 완료 회원만 반환합니다. 회원 삭제는 모든 ADMIN 계정을 보호하며 USER 삭제·활성 예약 취소·정원 반환·요청 키 제거를 한 트랜잭션으로 처리합니다. 예약·이벤트 이력은 보존합니다.

## 오류

일반 오류 본문은 `{message}`입니다. 잘못된 입력 400, 인증 없음·만료 401, 권한 부족 403, 없는 자료 404, 중복·정합성 충돌 409, 상담 제한 429를 사용합니다. 가입 중복 중 정규화한 이름·전화번호가 모두 일치할 때만 위의 구조화된 코드와 기존 가입 방식을 추가합니다. 프론트는 입력 내용과 안내를 유지하며 인증 재시작·직접 검색·재시도 동작을 제공합니다.
