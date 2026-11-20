# Backend

Java 21 · Spring Boot 3.5.16 · Gradle 8.13 기반 API입니다. `kr.dmu.hospital` 아래 `auth`, `member`, `integration`, `booking`, `common`, `config` 패키지를 사용합니다. 기능별 controller/service와 MongoTemplate으로 요청·저장·트랜잭션 흐름을 구성합니다.

## 실행

루트 `.env.example`의 변수명에 맞춰 환경변수를 준비합니다. Spring은 `.env`를 직접 읽지 않습니다. 루트 `scripts/start.mjs`는 로컬 설정을 읽어 Java 프로세스에 전달하며, 직접 실행할 때는 셸·IDE·배포 서비스에서 전달합니다. 비밀 값은 커밋하지 않습니다.

```bash
cd backend
./gradlew --no-daemon bootJar
java -jar build/libs/hospital-reservation.jar
```

Windows에서는 `gradlew.bat`을 사용합니다. 기본 API 주소는 `http://127.0.0.1:18081`, 상태 확인은 `/api/health`, OpenAPI UI는 `/swagger-ui.html`입니다. JWT_SECRET과 트랜잭션을 지원하는 MongoDB 연결이 필요합니다. MongoDB 단독 서버로는 예약·회원 삭제·소셜 가입 트랜잭션을 검증할 수 없습니다.

## 테스트

앱 데이터베이스와 분리된 로컬 replica set에만 테스트를 실행합니다. 테스트는 고유 이름의 테스트 DB를 만들고 종료 시 그 DB만 제거합니다. 운영 Atlas URI를 `MONGODB_TEST_URI`로 지정하지 않습니다.

```bash
export MONGODB_TEST_URI='mongodb://127.0.0.1:27118/?replicaSet=hospital-rs'
./gradlew --no-daemon test bootJar
```

PowerShell에서는 `$env:MONGODB_TEST_URI='mongodb://127.0.0.1:27118/?replicaSet=hospital-rs'`로 설정합니다. 이 변수가 없으면 MongoDB 통합 테스트가 생략되므로 전체 통과로 보고하지 않습니다. GitHub Actions는 임시 MongoDB 8.0.16 replica set을 띄우고 테스트 생략이 없는지 확인합니다. 자동 테스트에 실제 OpenAI·HIRA·소셜 인증키는 사용하지 않습니다.

## 구현과 제한

- 일반 회원은 이메일 대신 아이디로 로그인합니다. 비밀번호는 BCrypt로 저장합니다.
- 구글·카카오는 식별번호만 사용하고 이름·이메일·전화번호는 추가 입력으로 받습니다. 가입 완료 전 정보는 만료되는 임시 저장소에 보관합니다.
- 이름·전화번호 조합의 unique 인덱스로 가입 방식 간 중복을 막습니다. 중복이면 기존 가입 방식으로 로그인하도록 안내하며 자동 연결하지 않습니다.
- 예약 생성·변경·취소와 관리자 회원 삭제는 MongoDB 트랜잭션을 사용합니다.
- 실제 병원·진료과 정보는 HIRA를 조회합니다. 의료진·시간표는 저장된 가상 데이터이며 실제 접수는 하지 않습니다.
- 기본 관리자나 테스트 회원을 자동 생성하지 않습니다. `DEMO_ENABLED`는 샘플 병원 생성만 제어합니다.

API 필드·오류는 [API 계약](../docs/api-contract.md), 컬렉션·인덱스는 [DB 설계](../docs/database.md), Vercel 구성은 [배포 안내](../infra/README.md)를 따릅니다.
