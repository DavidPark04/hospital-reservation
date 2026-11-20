# MongoDB 설계

[기준 기획서](https://app.notion.com/p/879b8822a70f82f789298129ce73194d)의 컬렉션·정합성 기준입니다. 이 저장소에는 아직 컬렉션·인덱스 생성 및 Spring 구현이 없습니다. 실제 타입·validator·인덱스 생성 코드와 검증 결과는 Issue #3 및 구현 PR에서 확정합니다.

DB명: `hospital_reservation`. 다중 문서 트랜잭션이 가능한 replica set에서 먼저 검증하고 Atlas에 적용합니다.

| 컬렉션 | 주요 필드·역할 | 인덱스 기준 |
| --- | --- | --- |
| `users` | `emailNormalized`, `passwordHash`, `role`(USER/ADMIN), `createdAt` | `emailNormalized` unique |
| `hospitals` | `provider`, `externalId`, `name`, `address`, `tel`, `location`, `fetchedAt` | `(provider, externalId)` unique |
| `hospitalEvidence` | `hospitalId`, `kind`, `value`, `sourceUrl`, `basePeriod`, `fetchedAt` | 실제 조회 조건에 따른 인덱스는 구현 전 확정 |
| `doctors` | `hospitalId`, `departmentCode`, `sampleCode`, `name`, `isVirtual` | 배정 조합 unique, 정확한 조합은 Issue #3에서 확정 |
| `timeSlots` | `doctorId`, `startAt`, `capacity`, `reservedCount`, `closed` | `(doctorId, startAt)` unique |
| `reservations` | `userId`, `slotId`, `startAt`, `status`, `snapshot`, `createdAt` | 활성 예약의 `(userId, startAt)` partial unique |
| `requests` | `userId`, `key`, `requestHash`, `responseSnapshot` | `(userId, key)` unique |
| `reservationEvents` | `reservationId`, `actorId`, `action`, `before`, `after`, `createdAt` | 조회·보관 기준을 구현 전 확정 |

가상 의료진은 병원에 배정한 뒤 저장해 재방문 시 동일하게 조회합니다. 모든 참조 ID는 서버에서 검증하며 시간은 UTC로 저장하고 한국 시간으로 표시합니다. 증상 원문은 기본 저장하지 않습니다. 상담 이력은 시험 이후 동의·보관 기간을 검토하는 확장 기능입니다.

## 예약 상태와 요청 키

- 예약 상태는 `RESERVED`, `CANCELED`입니다. 변경을 뜻하는 `CHANGED`를 예약 상태로 추가하지 않습니다. 변경 이력은 `reservationEvents.action`의 `CREATE` / `CHANGE` / `CANCEL`로 구분합니다.
- 활성 예약은 `status = RESERVED`인 문서입니다. `(userId, startAt)` partial unique로 같은 시작 시각의 활성 예약을 막고, 취소된 이력이 재예약을 막지 않게 합니다.
- 같은 사용자·요청 키·본문이면 저장한 기존 응답을 반환합니다. 같은 키에 다른 본문이면 `409`로 거부합니다. `requestHash`의 본문 정규화·작업 구분과 요청 키 보관 정책은 API 계약에서 확정합니다.

## 트랜잭션 경계

1. 생성: 요청 키·본문을 확인하고 `closed = false`, `reservedCount < capacity` 조건으로 정원을 1 증가시킵니다. 예약·요청 응답·이벤트 저장까지 하나의 트랜잭션으로 커밋합니다.
2. 변경: 본인 예약과 대상 일정을 확인하고 새 자리 확보, 이전 자리 반환, 예약·이벤트 갱신을 함께 처리합니다. 실패하면 전체를 롤백합니다.
3. 취소: 본인 활성 예약만 취소 상태로 전환하고 정원을 한 번 반환합니다. 반복 취소·재시도에도 정원이 두 번 반환되지 않게 합니다.

관리자 정원 변경·휴진이 기존 예약과 충돌할 때의 정책, 트랜잭션 재시도·실패 응답은 구현 전 합의합니다. 구현 PR에는 마지막 한 자리 동시 요청, 동일 키 재시도, 다른 본문 충돌, 활성 중복, 취소 후 재예약, 변경 실패 롤백, 타인 예약 접근의 검증 증거를 남깁니다.

기준 기획서의 로컬 MongoDB PoC 10개 시나리오 기록은 참고 근거입니다. Spring·Atlas 연동 테스트는 별도로 수행해야 하며 현재 저장소 검사 CI는 정합성을 검증하지 않습니다.
