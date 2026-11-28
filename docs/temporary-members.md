# 임시 개인 회원 생성과 관리

운영자가 명시적으로 요청한 시연용 회원만 별도 스크립트로 생성합니다. 앱 시작·배포·병원 목록 갱신에서는 실행하지 않습니다.

## 이번 고정 배치

| 항목 | 값 |
| --- | --- |
| 수량 | 2,000명 |
| 이름·로그인 아이디 | `test0001` ~ `test2000` |
| 이메일 | `ex0001@gmail.com` ~ `ex2000@gmail.com` |
| 전화번호 | `test0001` → `010-0001-0001` ~ `test2000` → `010-2000-2000`. DB에는 하이픈 없이 저장 |
| 권한·가입 방식 | `USER`, `LOCAL`(아이디 로그인) |
| 배치 식별자 | `temporary-personal-test0001-test2000-v1` |

비밀번호는 운영자가 별도로 지정하며 계정마다 BCrypt 해시를 저장합니다. 원문 비밀번호와 해시 파일은 Git·문서·실행 로그에 기록하지 않습니다. 이메일·문자는 보내지 않습니다. 관리자 목록에는 `임시 회원` 배지를 표시합니다.

`testUser:true`, `memberIdentityClaimed:true`, `emailVerified:false`, `phoneVerified:false`, `emailSource:TEST`로 저장합니다. 전화번호와 이메일은 각각 2,000개 모두 다르며 일반 회원과 동일한 중복 검사와 unique 인덱스를 적용합니다. 임시 회원이라는 이유로 중복 번호를 허용하지 않습니다. `memberIdentityClaimed`는 중복 제한을 위한 값이며 연락처 인증 완료를 뜻하지 않습니다. 아이디 로그인은 가능하지만 임시 연락처에 의존하는 비밀번호 변경/찾기·이메일 변경·본인 탈퇴는 거절합니다. 삭제는 관리자 회원 목록에서 수행합니다. 문자 인증 후 기존 가입 방식 안내에는 임시 회원을 포함하지 않습니다. 서버 재시작 후에도 이메일·전화번호 미인증 상태는 그대로 유지합니다.

## 실행 순서

1. 임시 회원도 일반 회원과 동일한 연락처 중복 제한을 적용하는 코드가 배포되고 기동되는지 확인합니다.
2. 루트 의존성을 설치하고 `MONGODB_URI`를 대상 환경에 설정합니다. 연결 비밀값은 출력하지 않습니다.
3. 먼저 읽기 전용 검사를 실행합니다. 아이디·이메일·개별 연락처·고정 ID 충돌이 있으면 중단합니다.

```text
node scripts/seed-temporary-members.mjs --expected-database MediPick
```

4. 별도 비공개 파일에 `{ "passwordHashes": [ ... ] }` 형식으로 2,000개의 BCrypt 해시(cost 10~16)를 준비합니다. 평문 비밀번호는 입력받지 않습니다. 파일은 `.runtime/`처럼 Git에서 제외되는 위치에 둡니다.
5. 대상 DB 이름과 배포 확인 옵션을 지정해 실행합니다.

```text
node scripts/seed-temporary-members.mjs --apply --expected-database MediPick --test-user-support-deployed --password-hashes-file .runtime/test-member-password-hashes.json --output .runtime/member-seed-result.json
```

사전 검사·삽입·2,000건 확인은 하나의 트랜잭션입니다. 기존 계정은 덮어쓰지 않습니다. 재실행할 때 같은 배치의 정상 계정은 유지하고 누락된 계정만 생성하며, 수정된 기존 계정이 있으면 중단합니다. 관리자가 의도적으로 삭제한 임시 회원도 재실행 시 다시 만들어지므로 정리 후에는 시드를 재실행하지 않습니다. 생성 결과에는 배치·건수·대상 DB·시각만 기록합니다. `hospitals`·병원 회원·예약 자료는 변경하지 않습니다.

로컬 검증은 `MONGODB_TEST_URI`가 loopback replica set일 때 `node --test scripts/tests/temporary-members.test.mjs`로 실행합니다. 운영 DB는 테스트 대상으로 허용하지 않습니다.
