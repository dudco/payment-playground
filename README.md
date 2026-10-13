# Payment Playground

상품·장바구니·주문·결제의 최소 API에서 시작해, 대용량 트래픽에서 생기는 문제를 실제로 재현하고 하나씩 해결하는 Spring Boot 실험 프로젝트입니다.

## 현재 범위

트래픽이 거의 없는 환경에서 올바르게 동작하는 최소 API가 목표다. 속도·트래픽 대응 장치는 아직 넣지 않는다. 이 단계가 의도적으로 막지 않는 문제는 [PRD의 알려진 한계](docs/prd.md#8-알려진-한계)에 기록한다.

- `GET /products`, `GET /products/{productId}`: 코드에 고정된 Mock 상품 조회
- `GET /cart`, `POST /cart/items`, `PATCH|DELETE /cart/items/{productId}`, `DELETE /cart`: 고객별 장바구니 (`X-Customer-Id` 헤더, Redis Hash + TTL)
- 주문·결제는 다음 스펙에서 추가한다.

## 기술 스택

- Kotlin, Spring Boot, Java 21
- SQLite: 주문·결제 데이터를 로컬 파일로 저장
- Flyway: SQLite 스키마 마이그레이션 (`src/main/resources/db/migration`)
- Redis: 고객별 장바구니를 TTL과 함께 저장
- Gradle Wrapper

## 실행

```bash
./gradlew test
./gradlew bootRun
```

`bootRun` 시 Flyway가 `./data/payment-playground.db`에 마이그레이션을 자동 적용합니다. 애플리케이션과 테스트 모두 Redis가 기본 주소(`localhost:6379`)에서 실행 중이어야 합니다. 다른 주소는 `REDIS_HOST`, `REDIS_PORT` 환경 변수로 지정합니다.

## 문서 구조

문서의 역할·갱신 시점·새 기능의 문서화 순서는 [문서 가이드](docs/README.md)를 확인합니다.

- [PRD](docs/prd.md): 현재 제품 범위, 사용자 흐름, 공개 API 수준의 계약과 수용 조건
- [데이터 모델](docs/data-model.md): 공유 영속 모델, 상태·금액 정합성, 민감정보 보존 규칙
- [ADR](docs/adr/): 되돌리기 어려운 기술적 결정과 대안
- [기능 스펙](docs/specs/): 기능별 상세 API·상태·오류·테스트 계약
- [구현 계획](docs/plans/): 구현 직전의 날짜 기반 TDD 실행 계획

## 기능 확장 원칙

문제를 먼저 재현하고, 그 문제를 해결하는 최소 기능만 다음 단계에 추가합니다.

1. 동시 결제·동시 주문 재현 → 락 또는 낙관적 버전, 결제 요청 멱등키
2. 승인 후 되돌림 요구 재현 → 결제 취소(전액)
3. 단일 결제 한계 재현 → 분할 결제, 결제 배분, 부분 취소
4. 실제 비동기 PG/VAN 연동 재현 → `PENDING` 상태, 웹훅 중복 제거, 상태 대사
5. 지연·역순·응답 유실 재현 → 상태 머신, `UNKNOWN`, 대사
6. 작업 처리 실패 재현 → Outbox, 재시도, DLQ
7. 장기 장바구니 요구 재현 → Cart 병합, 고객당 활성 Cart 단일화
