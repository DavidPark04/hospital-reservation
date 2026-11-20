# Backend

Spring Boot 소스를 이 폴더에서 시작합니다. 현재는 실행 코드가 없는 준비 단계입니다.

첫 작업 브랜치: `chore/backend/1-project-init`.

초기 PR 완료 기준:

- Java 21과 호환되는 Spring Boot·Gradle 버전 고정
- Spring Security·Validation·Spring Data MongoDB 기본 구성
- Gradle Wrapper 커밋, `src/main/java`, `src/main/resources`, `src/test/java` 구성
- 도메인별 패키지와 환경변수 연결, 외부 키 없이 가능한 테스트
- 로컬 실행·테스트 명령을 이 문서에 작성
- GitHub Actions에서 BE 테스트·빌드 추가

`.env.example`은 변수 목록 초안입니다. Spring이 이 파일을 자동 로드한다고 가정하지 않고, IDE·셸·컨테이너의 환경변수 전달 방식을 초기 PR에서 정합니다.
