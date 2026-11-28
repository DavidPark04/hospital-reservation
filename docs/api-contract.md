# API 계약

현재 구현 기준입니다. 초기 기획의 경로 제안을 아래 실제 경로로 확정했습니다. 기본 경로는 `/api`, 일반 인증은 `Authorization: Bearer <JWT>`입니다. JSON 날짜는 UTC ISO 형식이며 화면에서 한국 시간으로 표시합니다. 상세 응답 필드는 [구현 상세](implementation-contract.md), 실행 중 명세는 `/swagger-ui.html`에서 확인합니다.

| 기능 | 메서드·경로 | 권한 |
| --- | --- | --- |
| 상태·설정 | GET `/api/health`, `/api/meta` | 전체 |
| 일반 가입 | POST `/api/auth/register` | 비회원 |
| 아이디 로그인 | POST `/api/auth/login` | 비회원 |
| 병원 사업자번호 조회 | POST `/api/auth/hospital/lookup` | 비회원 |
| 가입할 병원 선택 | POST `/api/auth/hospital/lookup/select` | 후보를 조회한 비회원 |
| 병원 가입·로그인 | POST `/api/auth/hospital/register`, `/api/auth/hospital/login` | 비회원 |
| 내 정보·로그아웃 | GET `/api/auth/me`, POST `/api/auth/logout` | 회원 또는 유효한 가입 임시 토큰 |
| 개인 인증 설정·코드 확인 | GET `/api/auth/verification/config`, POST `/api/auth/verification/email/send`, `/email/confirm`, `/phone/confirm` | 목적별 비회원·가입 임시 권한·USER |
| 비밀번호 변경 | PATCH `/api/auth/password` | 가입 완료 LOCAL 계정. USER는 현재 비밀번호와 문자 인증, ADMIN·HOSPITAL은 기존 비밀번호 확인 |
| 이메일 변경 | PATCH `/api/auth/email` | 가입 완료 USER, 새 이메일 인증 |
| 비밀번호 찾기·재설정 | POST `/api/auth/password/reset` | LOCAL USER의 아이디와 등록 휴대전화 문자 인증 |
| 소셜 제공자 | GET `/api/auth/social/providers` | 전체 |
| 소셜 로그인 시작 | GET `/oauth2/authorization/{google\|kakao}` | 전체 |
| 제공자 콜백 | GET `/login/oauth2/code/{google\|kakao}` | OAuth state 검증 |
| 소셜 결과 교환 | POST `/api/auth/social/exchange` | 같은 origin·일회용 HttpOnly 교환 쿠키 |
| 소셜 가입 완료 | POST `/api/auth/social/profile` | 유효한 가입 임시 토큰 |
| 개인 회원 탈퇴 | DELETE `/api/members/me` | 가입 완료 USER의 문자 인증. LOCAL은 현재 비밀번호도 확인 |
| 소셜 탈퇴 인증 시작 | POST `/api/auth/social/withdrawal/start` | 가입 완료 GOOGLE/KAKAO USER |
| 소셜 탈퇴 확정 | POST `/api/auth/social/withdrawal/confirm` | 시작한 회원의 JWT·OAuth 재확인 쿠키·문자 인증 |
| 소셜 탈퇴 취소 | POST `/api/auth/social/withdrawal/cancel` | 현재 회원의 JWT |
| 병원 검색·상세 | GET `/api/hospitals`, `/api/hospitals/{id}` | 전체 |
| AI 상담 | POST `/api/chat` | 전체, 서버 사용량 제한 적용 |
| 병원 등록 의료진 | GET `/api/hospitals/{id}/doctors?department=01` | 전체, 등록 병원의 활성 실의료진 |
| 병원 등록 일정 | GET `/api/slots?doctorId=...&date=YYYY-MM-DD` | 전체, 등록 병원의 실제 일정 |
| 내 예약·생성 | GET·POST `/api/reservations` | 가입 완료 회원 |
| 예약 변경·취소 | PATCH·DELETE `/api/reservations/{id}` | 예약 소유자 |
| 관리자 일정 | GET·POST `/api/admin/slots`, PATCH `/api/admin/slots/{id}` | ADMIN |
| 회원 목록·삭제 | GET `/api/admin/members`, DELETE `/api/admin/members/{id}` | ADMIN |
| 병원 정보·의료진 관리 | GET `/api/hospital/manage/hospital`, GET·POST `/api/hospital/manage/doctors`, PATCH `/api/hospital/manage/doctors/{id}` | HOSPITAL, 자기 병원 |
| 병원 일정 관리 | GET·POST `/api/hospital/manage/slots`, PATCH `/api/hospital/manage/slots/{id}` | HOSPITAL, 자기 병원 |
| 병원 예약 관리 | GET `/api/hospital/manage/reservations`, PATCH·DELETE `/api/hospital/manage/reservations/{id}` | HOSPITAL, 자기 병원 |

## 병원 가입·관리

개인 가입·로그인과 병원 가입·로그인을 분리합니다. 병원 가입에는 이메일·소셜 로그인을 사용하지 않습니다.

사업자번호 조회는 공단 사업장과 우편번호가 같은 활성 병원에서 지점명을 포함한 병원명, 시군구, 도로명, 건물번호를 대조합니다. 학교법인·의료법인 등 법인 유형과 식별 가능한 운영 주체의 표기 차이를 허용합니다. 명시된 법인이 다르면 일치 대상에서 제외합니다.

층과 호수는 각각 두 자료에 모두 적힌 경우 일치해야 합니다. 한쪽에서 생략한 상세주소는 같은 사업장의 다른 공단 기록에 명시된 층·호와 충돌하지 않아야 합니다.

병원명과 주소로 한 곳이 일치하면 바로 확인합니다. 여러 병원이 일치하면 가입 가능한 후보를 모두 반환합니다. 병원명이 직접 일치하는 곳이 없고 공단이 대학 의료원이나 운영 법인 명의로 응답하면, 같은 주소에서 같은 운영 주체를 확인할 수 있는 병원을 선택 후보로 반환합니다. 후보가 없으면 404, 이미 가입한 사업자번호이거나 모든 후보가 가입을 마쳤으면 409입니다.

가입 화면에서 후보를 누르면 병원명과 주소를 보여 주고 선택 여부를 묻습니다. 취소하면 목록으로 돌아갑니다. 선택을 확정하면 DB 병원 기본정보의 병원명, 주소, 우편번호, 전화번호를 읽기 전용으로 채웁니다. `hospital.name`은 식별된 운영 법인 표기를 제외하고 병원명의 띄어쓰기와 분원 표기를 유지합니다. `hospital.address`는 DB에 기록된 층·호를 포함한 전체 주소입니다.

| 동작 | 요청 | 응답·규칙 |
| --- | --- | --- |
| 사업자번호 조회 | `{businessNumber}` | 단일 직접 일치는 `{businessNumber,hospital,verificationToken}`. 선택이 필요하면 `{businessNumber,candidates,selectionToken}`. 각 병원에 `id,name,address,postalCode,tel` 포함 |
| 가입할 병원 선택 | `{businessNumber,selectionToken,hospitalId}` | 제시한 후보인지와 현재 DB 기본정보를 확인한 뒤 `{businessNumber,hospital,verificationToken}` 반환. 선택 토큰은 일회용이며 가입 확인의 만료 시각은 최초 조회로부터 15분 |
| 병원 가입 | `{businessNumber,password,verificationToken}` | `{token,user}`. 조회 확인은 15분간 유효하고 한 번 사용. 서버가 DB 기본정보를 다시 확인 |
| 병원 로그인 | `{businessNumber,password}` | `{token,user}`. 사업자번호는 하이픈 제거 후 10자리. User에 `role:HOSPITAL,hospitalId,businessNumber` 포함 |
| 의료진 등록 | `{name,departmentCode,specialty?}` | Doctor 응답. 신규 `active:true,isVirtual:false` |
| 의료진 수정 | `{name,departmentCode,specialty?,active}` | 자기 병원 의료진만 수정. 예정된 예약이 있으면 진료과 변경 거절 |
| 일정 생성 | `{doctorId,date,startTime,endTime,intervalMinutes,capacity}` | `{items}`. 날짜는 오늘부터 365일 이내, 미래 시간만 생성. 간격 15~120분, 정원 1~100 |
| 일정 수정 | `{capacity,closed}` | Slot 응답. 정원 0~100, 예약 인원 이하 축소·예약 있는 일정 휴진·지난 일정 수정 거절 |
| 병원 예약 목록 | 선택 쿼리 `date=YYYY-MM-DD`, `status=RESERVED\|CANCELED` | `{items}`. 예약 응답에 `patientName,patientPhone`만 추가 |
| 병원 예약 변경·취소 | 변경 `{slotId}`, 취소 본문 없음 | `Idempotency-Key` 필요. 같은 병원의 일정만 이동 가능. 기존 예약 응답 형태 유지 |

병원 정보 응답에는 기본정보와 `businessNumber`, 운영 진료과 `departments`, 의료진 등록용 `availableDepartments`가 있습니다. 병원명·주소·우편번호·전화번호는 읽기 전용이며 전화번호가 없으면 빈 문자열입니다. 등록된 병원이라도 의료진·일정을 직접 등록하기 전에는 예약 가능 항목이 비어 있습니다.

모든 병원 관리 요청은 JWT 계정과 활성 `hospitalRegistrations` 소속을 서버에서 확인합니다. 병원 계정은 개인 예약 생성 및 개인 회원 탈퇴 기능을 사용할 수 없습니다. 사업자 정보 대조는 실제 운영자 신원 인증이 아니며, 실제 병원·환자가 이용하는 서비스로 운영하려면 별도 권한 확인 절차가 필요합니다. 현재 수업 프로젝트 구현에는 운영자 승인 절차가 없습니다.

## 가입과 로그인

일반 가입 요청은 `{name,loginId,password,email,phone,emailVerificationToken,phoneVerificationToken}`, 로그인은 `{loginId,password}`입니다. 신규 가입의 이메일과 휴대전화, 두 연락처에 대한 `SIGNUP` 인증 증명이 모두 필수입니다. 기존 전화번호 입력 정규화와 별도로 실제 문자 인증은 한국 휴대전화만 지원합니다. 아이디는 소문자로 정규화합니다. 신규 연락처는 공백·하이픈을 제거하고 0으로 시작하는 국내 9~11자리 숫자로 저장합니다. 이메일은 앞뒤 공백을 제거해 저장하며 중복 검사에는 소문자로 정규화한 `emailNormalized`를 사용합니다. 기존 이메일·전화번호가 없는 LOCAL 회원의 로그인은 유지하고 누락된 값을 임의로 채우지 않습니다.

로그인 응답은 `{token,user}`입니다. 공개 User 필드는 `id,name,loginId,role,authProvider,profileComplete,emailVerified,phoneVerified`와 선택 `email,phone`입니다. 비밀번호 해시, 제공자 식별번호, 제공자 토큰, 프로필 이미지는 공개하지 않습니다.

Google은 `openid`만 요청하고 Kakao는 별도 개인정보 scope를 요청하지 않습니다. 최초 소셜 인증은 임시 가입 상태만 만들며 `users`에는 등록하지 않습니다. 임시 응답은 빈 이름·아이디, `profileComplete:false`이고 이메일·전화번호가 없습니다. 임시 JWT는 상태 조회·가입 목적 이메일/문자 인증·가입 완료·로그아웃에만 사용할 수 있습니다.

가입 완료 요청은 `{name,email,phone,emailVerificationToken,phoneVerificationToken}`입니다. 입력 검증과 중복 검사, 두 연락처의 `SIGNUP` 증명 검증을 통과하면 정식 회원 생성·인증 증명 소모·임시 가입 자료 소모를 하나의 트랜잭션으로 처리하고 새 회원 JWT를 반환합니다. 신규 일반·소셜 개인 회원의 `emailVerified`, `phoneVerified`는 모두 `true`입니다. 기존 미인증 회원의 상태를 임의로 인증 완료로 바꾸지는 않습니다. 기존 임시 JWT는 회원 JWT로 바뀌지 않으며 재사용할 수 없습니다. 이미 가입된 같은 제공자 계정으로 로그인하면 직접 입력한 회원 정보를 유지합니다.

일반·구글·카카오 가입을 통틀어 정규화한 전화번호 또는 이메일 중 하나라도 기존 회원과 일치하면 이름과 무관하게 HTTP 409로 가입을 차단합니다. 기존 가입 방식은 이름·전화번호가 함께 일치하는 회원이 있으면 아래 형식으로 안내하고, 없으면 이름·이메일이 함께 일치하는 회원을 확인해 안내합니다. 전화번호와 이메일이 서로 다른 회원과 일치할 때도 이 순서를 적용합니다. 전화번호 일치 회원과 이름이 다르더라도 이메일 일치 회원과 이름이 같으면 이메일 일치 회원의 가입 방식을 안내합니다.

```json
{
  "message": "이미 카카오로 가입된 계정이 있어요. 카카오로 로그인해 주세요.",
  "code": "MEMBER_ALREADY_EXISTS",
  "existingAuthProvider": "KAKAO"
}
```

구글은 “이미 구글로 가입된 계정이 있어요. 구글로 로그인해 주세요.”, 일반 회원은 “이미 일반 가입으로 등록된 계정이 있어요. 아이디로 로그인해 주세요.”를 표시합니다. `existingAuthProvider`는 `LOCAL`, `GOOGLE`, `KAKAO` 중 하나이며 프론트는 해당 방식의 로그인 버튼을 제공합니다. 이름·전화번호와 이름·이메일 중 어느 조합도 일치하지 않는 중복에는 `{message:"이미 가입된 정보가 있어요. 기존에 가입한 방법으로 로그인해 주세요."}`만 반환하며 `code`와 `existingAuthProvider`를 포함하지 않습니다. 소셜 추가 정보 화면에서는 ‘가입 완료’를 눌러 중복 응답을 받았을 때 안내하고, 특정 방식의 로그인 버튼을 누르면 임시 가입 인증을 종료한 뒤 로그인 화면으로 이동합니다. 별도 아이디 중복은 기존 메시지 형태의 409입니다. 이 중복 안내는 입력값의 일치 검사입니다. 연락처 인증 절차는 별도로 요구하며, 이름의 실명 확인이나 자동 계정 통합은 수행하지 않습니다. 별도 가입정보 조회 API나 화면은 제공하지 않습니다.

회원 로그아웃은 토큰 버전을 폐기하고, 가입 진행 중 로그아웃은 임시 가입 자료를 제거합니다. 프론트는 저장한 JWT를 지웁니다.

## 개인 연락처 인증

병원 회원가입에는 이 절차를 적용하지 않습니다. 개인 일반 가입과 소셜 가입 완료에는 이메일·휴대전화 인증이 모두 필요합니다. 인증 완료된 이메일·휴대전화 입력은 화면에서 읽기 전용으로 표시합니다. 이메일 변경은 새 이메일을, 비밀번호 변경·탈퇴는 회원정보에 등록된 휴대전화를 인증합니다. 비밀번호 찾기는 아이디와 등록 휴대전화가 일치하는 LOCAL 개인 회원만 사용할 수 있습니다.

| 메서드·경로 | 요청 | 응답·규칙 |
| --- | --- | --- |
| GET `/api/auth/verification/config` | 없음 | `{emailEnabled,phoneEnabled,firebase:{apiKey,authDomain,projectId}}`. 브라우저용 공개 설정만 반환하며 SMTP 비밀값은 반환하지 않음 |
| POST `/api/auth/verification/email/send` | `{purpose,email}` | `purpose`는 `SIGNUP` 또는 `EMAIL_CHANGE`. Gmail SMTP로 6자리 코드 전송. `{challengeId,expiresIn:300,retryAfter:60}` |
| POST `/api/auth/verification/email/confirm` | `{challengeId,code}` | 성공 시 `{verificationToken,expiresIn:600,contact}`. 코드는 5분간 최대 5회 확인 가능 |
| POST `/api/auth/verification/phone/confirm` | `{purpose,idToken,loginId?}` | `purpose`는 `SIGNUP`, `PASSWORD_CHANGE`, `WITHDRAWAL`, `PASSWORD_RESET`. 마지막 목적에는 `loginId` 필수. 성공 시 `{verificationToken,expiresIn:600,contact}` |

문자 발송·코드 입력은 브라우저의 Firebase Phone Auth와 reCAPTCHA로 진행합니다. 서버는 Firebase ID 토큰의 서명, 프로젝트·발급자·수신자, 만료, `phone` 로그인 방식과 최근 5분 안의 인증 시각을 검증하고 한국 휴대전화 번호를 국내 형식으로 정규화합니다. 다른 Firebase 프로젝트나 tenant 토큰은 허용하지 않습니다. Firebase 세션은 메디픽 로그인 세션을 대신하지 않습니다.

`SIGNUP`은 비회원 또는 유효한 소셜 가입 임시 JWT에서 사용하고, `EMAIL_CHANGE`, `PASSWORD_CHANGE`, `WITHDRAWAL`은 가입 완료 USER의 현재 JWT가 필요합니다. `PASSWORD_RESET`은 로그인 없이 사용할 수 있지만 아이디·인증한 휴대전화와 저장된 계정이 일치해야 합니다. 소셜 가입 임시 JWT는 회원 기능 대신 가입 목적 인증만 수행할 수 있습니다.

인증 결과는 용도·채널·정규화 연락처·회원/임시 가입 ID·토큰 버전에 묶인 10분짜리 일회용 증명입니다. 일반 가입은 익명 범위(`userId:""`, 버전 0)이며 소셜 가입은 임시 가입 범위입니다. 최종 작업에 `emailVerificationToken` 또는 `phoneVerificationToken`으로 전달하고 서버가 회원 변경과 같은 트랜잭션에서 소모합니다. 실패하면 증명 소모도 롤백됩니다. 화면의 인증 완료 표시나 클라이언트가 보낸 `emailVerified`·`phoneVerified` 값으로 통과할 수 없습니다. 입력한 연락처를 바꾸면 다시 인증해야 합니다.

이메일 발송은 주소별 60초 재발송 간격, 15분 구간당 이메일별 5회·요청 IP별 20회 제한을 적용합니다. 제한 초과는 429, 인증 누락·불일치·만료·재사용은 400, 로그인 필요는 401, 해당 계정에서 허용하지 않는 요청은 400 또는 403, 발송 설정 누락·메일 전송 실패는 503입니다. 같은 Firebase 사용자와 인증 시각의 토큰은 서버 증명으로 한 번만 교환할 수 있습니다.

## 비밀번호 변경

PATCH `/api/auth/password`의 개인 USER 요청은 `{currentPassword,newPassword,phoneVerificationToken}`입니다. `PASSWORD_CHANGE` 문자 인증 증명이 필요합니다. ADMIN·HOSPITAL의 기존 요청은 `{currentPassword,newPassword}`를 유지합니다. 대상 회원은 현재 JWT로 결정하며 요청에 다른 회원 ID를 넣어 변경할 수 없습니다. 가입 완료된 LOCAL 개인·관리자·병원 계정만 사용할 수 있고 소셜 회원·가입 임시 권한은 사용할 수 없습니다. 비밀번호 앞뒤 공백을 제거하지 않으며 새 비밀번호는 8자 이상, UTF-8 72바이트 이하여야 합니다. 현재 비밀번호와 같은 값은 거절합니다. 화면의 새 비밀번호 확인값은 프런트에서 일치 여부를 확인합니다.

현재 비밀번호 불일치·새 비밀번호 입력 오류는 400이며 기존 비밀번호와 세션을 유지합니다. 인증 없음·만료는 401, 소셜 회원 등 권한 부족은 403입니다. 서버는 인증된 토큰 버전과 기존 비밀번호 해시가 유지된 경우에만 문자 인증 증명 소모(USER), 새 BCrypt 해시 저장, 토큰 버전 증가를 한 트랜잭션으로 수행합니다.

성공 응답은 로그인과 같은 `{token,user}`입니다. 변경 전 JWT는 모두 무효화되고 프런트는 응답의 새 JWT로 현재 로그인을 이어 갑니다. 새 로그인에는 변경된 비밀번호를 사용합니다. 로그아웃 상태의 비밀번호 찾기는 아래 재설정 API를 사용합니다. 소셜 제공자 계정의 비밀번호를 변경하지는 않습니다.

## 이메일 변경·비밀번호 찾기

PATCH `/api/auth/email`은 `{email,emailVerificationToken}`을 받고 새 주소의 `EMAIL_CHANGE` 증명을 요구합니다. 가입 완료 개인 USER는 LOCAL·GOOGLE·KAKAO 모두 사용할 수 있습니다. 현재 주소와 같으면 400, 이미 가입된 주소면 409입니다. 증명 소모, 이메일·정규화 값·인증 상태 갱신, `tokenVersion` 증가를 한 트랜잭션으로 처리하고 `{token,user}`를 반환합니다. 프런트는 새 세션으로 교체하며 이전 JWT는 무효화됩니다.

로그인 화면의 비밀번호 찾기는 아이디 입력 → 등록 휴대전화 문자 인증 → 새 비밀번호 입력 순서입니다. POST `/api/auth/password/reset` 요청은 `{loginId,newPassword,phoneVerificationToken}`이며 `PASSWORD_RESET` 증명이 필요합니다. 일반 개인 계정만 허용하고 관리자·병원·소셜 계정은 대상이 아닙니다. 증명 소모·비밀번호 변경·토큰 버전 증가를 함께 처리하고 `{message}`를 반환합니다. 기존 세션은 모두 만료되며 자동 로그인하지 않고 새 비밀번호로 로그인합니다.

## 회원 탈퇴

일반 회원은 DELETE `/api/members/me`에 `{password,phoneVerificationToken}`을 전달합니다. `WITHDRAWAL` 문자 인증은 등록된 휴대전화와 일치해야 합니다. 소셜 개인 회원은 같은 경로에 `{phoneVerificationToken}`을 전달하며 메디픽 비밀번호는 요구하지 않습니다. 삭제 대상은 요청의 ID가 아니라 현재 JWT 회원으로 정하며 저장된 BCrypt 해시와 현재 비밀번호를 비교합니다. 비밀번호 누락은 400, 불일치는 403이고 실패 시 계정·예약을 유지합니다. 문자 인증이 누락되거나 맞지 않으면 400입니다. 관리자·병원·가입 대기 계정은 개인 회원 탈퇴 대상이 아닙니다.

기존 소셜 OAuth 재확인 경로도 유지합니다. POST `/api/auth/social/withdrawal/start`로 `{authorizationUrl}`을 받아 기존 OAuth 경로에서 가입한 계정을 다시 확인합니다. 콜백의 제공자 고유번호가 시작한 회원과 일치해야 하며 일반 로그인·가입 처리로 전환하지 않습니다. 인증 결과는 `/mypage?withdrawal=verified`로 돌아오고, 프론트는 결과 쿼리를 지운 뒤 최종 확인 창을 표시합니다. 취소·실패·다른 계정 선택 시 탈퇴하지 않습니다. 최종 POST `/api/auth/social/withdrawal/confirm`은 본문 `{phoneVerificationToken}`, 현재 JWT, 유효한 일회용 HttpOnly OAuth 증명 쿠키를 함께 요구합니다. 이 경로도 `WITHDRAWAL` 문자 인증을 생략할 수 없습니다. OAuth 콜백 방문만으로 회원을 삭제하지 않습니다.

성공 응답은 `{message,deletedUserId,canceledReservations}`입니다. 문자 인증 증명 소모, 회원정보·요청 키·로그인 교환 티켓·회원별 연락처 인증 증명 삭제와 활성 예약 취소·정원 반환을 한 트랜잭션으로 처리합니다. 실패하면 전체를 되돌리고, 성공하면 기존 회원 JWT가 무효화됩니다. 프론트는 세션과 회원별 캐시를 비우고 홈으로 이동합니다. 예약·이벤트 이력은 UUID로 보존합니다. 탈퇴한 이메일·전화번호는 신규 가입에 다시 사용할 수 있으며, 재가입은 새 회원 ID를 발급합니다. 카카오·구글 자체 계정 삭제나 해당 서비스의 연결 해제를 수행하는 기능은 아닙니다.

## 병원·상담

검색 파라미터는 `query`, `region`, `department`, `exactName`입니다. 기본 병원명 검색은 부분 일치이며, `exactName=true`이면 입력한 병원명 전체가 대소문자까지 일치하는 자료를 조회합니다. 응답은 `{items,source,message?,fetchedAt?}`이며 `hospitals`의 활성 자료 중 `hospitalRegistrations.status=ACTIVE`인 병원만 이름·ID 순서로 최대 30건 반환합니다. `department`는 병원이 등록한 활성 의료진의 운영 진료과를 검색합니다. 검색 요청은 기본 공공 API를 호출하지 않으며 저장된 자료의 `source`는 `CACHE`입니다. 등록 병원이 없으면 빈 목록을 반환하며 미등록 병원이나 시연 병원으로 대체하지 않습니다. AI 추천 후보도 같은 등록 조건을 적용합니다.

상세는 현재 기본 카탈로그에 저장된 병원의 최신 기본 필드와 상세 캐시를 합칩니다. 상세 3종(진료과·특수진료·전문병원 지정)은 기존처럼 필요할 때 조회하고 완전한 결과를 1시간 캐시합니다. 이때 성공한 외부 상세 조회는 `LIVE`, 저장된 상세 재사용은 `CACHE`이며, 근거마다 출처·조회 시점을 포함합니다. 기본 카탈로그에 없는 ID는 404, 비활성화된 병원은 410입니다. 명시된 샘플 병원 상세는 `SAMPLE`을 유지합니다. 의료진은 `isVirtual:true`인 저장된 가상 자료입니다.

비활성 병원에는 새 가상 의료진·일정 조회와 예약 생성·변경을 허용하지 않습니다. 이미 저장한 예약 조회·취소와 과거 이력은 보존합니다. 원본 `apiFields`, 암호화된 `externalId`와 내용 비교용 해시는 공개 병원 응답에 포함하지 않습니다.

병원 정보의 선택 필드 `latitude`, `longitude`는 HIRA가 제공한 유효 좌표입니다. 프런트 지도는 이 좌표를 우선 사용하고, 없는 경우 카카오 지도 SDK로 병원 주소를 변환합니다. 주소가 모호하거나 위치를 확인하지 못하면 좌표를 추정해 표시하지 않습니다. `SAMPLE` 병원은 실제 위치 안내에서 제외합니다. 지도·외부 길찾기 연결을 위한 새 서버 API나 사용자 현재 위치 수집은 추가하지 않습니다.

상담 요청은 `{messages:[{role,content}],region?,location?:{latitude,longitude}}`이며 위치는 사용자가 명시적으로 제공할 때만 사용합니다. 응답은 기존 `reply,hospitals,departmentCode?,departmentName?,needsMoreInfo,emergency,mode,source?,notice?`에 `carePreference`, `careGuidance`, `followUpOptions`, `specialty`, `stage`, `promptCare`를 추가합니다. `carePreference`는 `NEARBY/SPECIALIST/UNDECIDED`, `stage`는 `CONTEXT/PREFERENCE/LOCATION/RESULTS/EMERGENCY`입니다. `followUpOptions`는 사용자가 선택하면 일반 사용자 메시지로 다시 전송하는 문자열 배열입니다.

`mode`는 `AI/FALLBACK`, 추천 전체 `source`는 `CACHE/WEB/WEB_CACHE`일 수 있습니다. 웹으로 찾은 후보도 실제 MongoDB 병원과 연결하므로 개별 병원 카드의 `source`는 `CACHE`입니다. 카드에는 `hospitalType`, `distanceKm?`(직선거리), `specialty?`, `recommendationReason?`, `recommendationSources?:[{url,title,checkedAt,kind,label}]`가 추가됩니다. `kind`는 `OFFICIAL/NEWS/EXPERIENCE/WEB`이며 출처 유형과 검색 결과의 AI 요약임을 표시합니다. 후기·SNS를 치료 성적이나 병원 간 우열로 확정하지 않습니다. 기본 공공 API를 새로 호출하지 않으며 대화 원문·정밀 위치는 저장하지 않습니다. AI·웹 검색 실패를 성공 추천으로 숨기지 않습니다. [추천 동작](recommendations.md)을 함께 참고하세요.

## 예약 정합성

생성·변경 본문은 `{slotId}`입니다. 생성·변경·취소 모두 `Idempotency-Key` 헤더를 전달합니다. 사용자·키와 정규화한 작업 내용의 SHA-256 해시를 저장합니다. 같은 키·같은 작업은 기존 응답, 다른 작업은 409입니다. 요청 키에는 자동 TTL을 두지 않으며 회원 삭제 시 해당 회원 키를 제거합니다.

예약 상태는 `RESERVED`, `CANCELED`입니다. 변경은 `reservationEvents.action=CHANGE`로 기록하며 `CHANGED` 상태를 만들지 않습니다. 서버에서 소유권·참조 ID·정원·휴진을 확인합니다. 정원 확보/반환, 예약·응답·이력 저장은 하나의 트랜잭션이며 실패하면 롤백합니다. 반복 취소는 정원을 두 번 반환하지 않습니다.

병원에서 수행한 변경·취소 이력은 `HOSPITAL_CHANGE`, `HOSPITAL_CANCEL`로 기록하고 `actorId`는 병원 계정입니다. 개인 신규 예약은 등록 병원의 활성 의료진과 `isVirtual:false`인 일정만 가능합니다. 예전 개인 예약 내역과 취소 기능은 유지합니다.

관리자 일정 생성 본문은 `{doctorId,date,startTime,endTime,intervalMinutes,capacity}`, 변경은 `{capacity,closed}`입니다. 기존 예약보다 작은 정원이나 예약이 있는 일정의 휴진 변경은 409로 거부합니다.

관리자 회원 목록은 가입 완료 회원만 반환합니다. 회원 삭제는 모든 ADMIN 계정을 보호하며 USER 삭제·활성 예약 취소·정원 반환·요청 키 제거를 한 트랜잭션으로 처리합니다. 예약·이벤트 이력은 보존합니다.

## 오류

일반 오류 본문은 `{message}`입니다. 잘못된 입력 400, 인증 없음·만료 401, 권한 부족 403, 없는 자료 404, 중복·정합성 충돌 409, 상담 제한 429를 사용합니다. 가입 중복에서 이름·전화번호 또는 이름·이메일이 함께 일치하는 기존 회원을 확인할 때만 위의 구조화된 코드와 기존 가입 방식을 추가합니다. 프론트는 입력 내용과 안내를 유지하며 인증 재시작·직접 검색·재시도 동작을 제공합니다.
