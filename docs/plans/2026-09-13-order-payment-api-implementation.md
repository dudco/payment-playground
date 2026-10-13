# 주문·결제 API 초안 구현 계획

> 작성일: 2026-09-13 · 최종 갱신일: 2026-10-09 (작업 순서 재정렬, Cart Redis 이전, 취소 제외, Flyway 도입)

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 멱등키 기반 Cart(Redis)에서만 주문을 생성하고, 단일 `POST /payments` API로 온라인 카드·오프라인 카드·포인트 결제를 처리하는 최소 흐름을 만든다. 트래픽이 거의 없는 환경을 전제로 하며 동시성 제어는 넣지 않는다.

**Architecture:** Spring MVC Controller → Transactional Service → Spring Data JPA Repository / Redis 저장소. Cart는 Redis JSON으로 저장하고 주문 전환 시에만 `orders`/`order_lines`로 복사한다. 결제수단별 Gateway 인터페이스를 유지하되 API는 `method`로 Gateway를 선택한다.

**Tech Stack:** Kotlin, Spring Boot, Spring MVC, Bean Validation, Spring Data JPA, SQLite, Flyway, Spring Data Redis, MockMvc, JUnit 5.

**Specification:** `docs/specs/order-payment-api.md` (초안, 2026-10-09 갱신)

**Documentation Guide:** `docs/README.md`

**전제:** 테스트도 로컬 Redis(`localhost:6379`)를 사용한다. 각 테스트 전에 `cart:*` 키를 지운다.

**스키마:** Flyway가 `src/main/resources/db/migration`의 SQL로 스키마를 만들고, JPA는 `ddl-auto=validate`로 엔티티와 스키마가 맞는지만 확인한다([ADR-0004](../adr/0004-flyway-schema-migration.md)). 테스트도 같은 마이그레이션을 실행하므로, 마이그레이션 SQL이 테스트로 함께 검증된다. 테이블은 그 테이블을 처음 쓰는 Task에서 새 버전 파일로 추가한다.

---

## Task 1: Flyway·테스트 환경과 공통 오류 응답

이후 모든 작업이 마이그레이션된 스키마와 `code`/`message` 오류 본문을 전제로 하므로 가장 먼저 만든다.

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/resources/db/migration/.gitkeep`
- Create: `src/test/resources/application.properties`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/support/DatabaseCleaner.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/support/DomainExceptions.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/support/ApiExceptionHandler.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/support/TimeConfig.kt`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/support/ApiExceptionHandlerTest.kt`
- Modify: `src/test/kotlin/io/github/dudco/paymentplayground/PaymentPlaygroundApplicationTests.kt`

1. `build.gradle.kts`에 `spring-boot-starter-flyway`를 추가한다. 다른 스타터와 같은 방식으로 테스트용 `spring-boot-starter-flyway-test`도 함께 넣는다. Spring Boot 4부터 Flyway 자동 설정은 `spring-boot-flyway` 모듈로 분리되어 `flyway-core`만으로는 동작하지 않는다. SQLite 지원은 `flyway-core`에 포함되어 별도 `flyway-database-*` 모듈이 필요 없다. `./gradlew dependencies --configuration runtimeClasspath | grep flyway`로 `flyway-core`가 함께 들어오는지 확인한다.
2. 테스트 DB는 `jdbc:sqlite:./build/test-db/payment-playground.db` 파일로 두고 `ddl-auto=validate`, `spring.flyway.enabled=true`를 쓴다. SQLite 인메모리 DB는 커넥션마다 다른 DB가 되므로 쓰지 않는다. 테스트 간 격리는 `DatabaseCleaner`가 `@BeforeEach`에서 Flyway 이력 테이블을 제외한 테이블을 비우는 방식으로 한다.
3. 테스트용 Controller로 `NotFound`/`Conflict`/`Unprocessable` 도메인 예외가 각각 `404`/`409`/`422`로, Bean Validation 실패·읽을 수 없는 본문·필수 헤더 누락이 `400 VALIDATION_ERROR`로, 모두 `code`/`message` JSON으로 변환되는지 RED 테스트를 작성한다. 검증 실패 응답에 거부된 값이 실리지 않는지도 확인한다.
4. 실행: `./gradlew test --tests '*ApiExceptionHandlerTest'`
5. 도메인 예외 계층과 `@RestControllerAdvice`, 고정 가능한 `Clock` 빈을 최소 구현한다.
6. 같은 명령으로 GREEN을 확인하고 `./gradlew test`로 context 로딩과 Flyway 실행(마이그레이션 0건)을 확인한다.
7. `./gradlew bootRun`이 `./data/payment-playground.db`에 `flyway_schema_history`를 만들고 기동하는지 확인한다.
8. 커밋: `feat: add flyway and common API error response`

## Task 2: Redis Cart API

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/Cart.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartStore.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartProperties.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartService.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartController.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartDtos.kt`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartApiIntegrationTest.kt`

1. RED 테스트를 작성한다.
   - 키 없는 생성 → `201`, 응답 헤더·본문의 키, `expiresAt`, Redis TTL 존재
   - 같은 키 갱신 → `200`, 최신 라인, TTL 재설정
   - 같은 키 다른 `customerId` → `409 CART_CUSTOMER_MISMATCH`
   - `GET` 조회 성공, 없는 키 `404 CART_NOT_FOUND`, 헤더 없는 조회 `400`
   - 빈 라인, 0 이하 수량, 음수 단가 → `400`
2. 실행: `./gradlew test --tests '*CartApiIntegrationTest'`
3. `StringRedisTemplate` 기반 `CartStore`(get/save with TTL/delete/ttl)와 Service·Controller를 최소 구현한다. TTL은 `payment-playground.cart.ttl`(기본 `1h`)로 설정한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add redis cart draft API`

## Task 3: Cart에서 불변 Order 생성

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/Order.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderLine.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderRepository.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderService.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderController.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderDtos.kt`
- Modify: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartService.kt`
- Create: `src/main/resources/db/migration/V1__create_orders.sql`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderApiIntegrationTest.kt`

1. RED 테스트를 작성한다.
   - Cart 없는 주문 → `404 CART_NOT_FOUND`
   - `GET` 없이 주문 생성 → `201`, 라인·총액 일치, `orders`/`order_lines` 저장
   - 같은 키 재호출 → `200`, 같은 `orderId`, 주문 1건
   - 주문 후 Redis Cart 삭제, Cart 조회 `404`
   - 주문 후 같은 키 Cart 갱신 → `409 CART_ALREADY_ORDERED`
2. 실행: `./gradlew test --tests '*OrderApiIntegrationTest'`
3. `V1__create_orders.sql`로 `orders`·`order_lines` 테이블, `orders.idempotency_key` unique, 금액·수량 `CHECK` 제약, `order_lines.order_id` 인덱스를 만든다. 이어서 `@Table(name = "orders")` 엔티티, `findByIdempotencyKey`, 트랜잭션 커밋 후 Cart 삭제, `CartService`의 주문 존재 검사를 최소 구현한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: create orders from cart drafts`

## Task 4: Gateway 인터페이스와 Fake 구현

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/gateway/PaymentGateways.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/gateway/PaymentApprovalResult.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/gateway/FakePaymentGateways.kt`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/gateway/FakePaymentGatewayTest.kt`

1. RED 단위 테스트를 작성한다.
   - 온라인 카드·포인트(OFFLINE/ONLINE)·오프라인 카드 각각의 승인
   - 온라인 카드·포인트의 `fake-decline` 거절, 오프라인 카드의 `responseCode != "0000"` 거절
   - 결과와 메타데이터에 PG 토큰·바코드 원문이 없음
2. 실행: `./gradlew test --tests '*FakePaymentGatewayTest'`
3. 전용 Gateway 인터페이스, 승인/거절을 표현하는 `PaymentApprovalResult`, Fake 구현체를 최소 구현한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add fake payment gateways`

## Task 5: 단일 Payment API와 상태 전이

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/Payment.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentRepository.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentService.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentController.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentDtos.kt`
- Create: `src/main/resources/db/migration/V2__create_payments.sql`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/payment/PaymentApiIntegrationTest.kt`

1. RED 테스트를 작성한다.
   - `method`별 승인 → `201`, Payment `APPROVED`, 주문 `PAID`
   - Gateway 거절 → `422 PAYMENT_DECLINED`, 본문의 `paymentId`로 DB에 `FAILED` 행 존재, 주문 `CREATED`
   - 거절 후 새 결제 승인 가능
   - 금액 불일치 `422 ORDER_AMOUNT_MISMATCH`, 세부정보 오류 `422 PAYMENT_DETAILS_INVALID`, `PAID` 주문 `409 ORDER_ALREADY_PAID`, 없는 주문 `404` — 모두 Payment 0건
2. 실행: `./gradlew test --tests '*PaymentApiIntegrationTest'`
3. `V2__create_payments.sql`로 `payments` 테이블, `(method, provider_transaction_id)` unique, `order_id` 인덱스, 상태·금액 `CHECK` 제약을 만든다. 이어서 `method` 분기와 상태 전이를 최소 구현한다. 거절은 예외가 아닌 결과값으로 반환해 `FAILED` 행이 롤백되지 않게 한다(스펙 5절).
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add method-based payment API`

## Task 6: 전체 회귀 검증과 리뷰

1. 실행: `./gradlew test`
2. 실행: `./gradlew build`
3. 실행: `git diff --check`
4. 이미 적용된 마이그레이션 파일(`V1`, `V2`)을 수정하지 않았는지 확인한다. 스키마 변경은 항상 새 버전 파일로 한다.
5. 민감정보 저장·로그 금지 정책을 엔티티, Redis Cart, 응답, 로그에서 검토한다.
6. PRD의 알려진 한계가 실제 구현과 일치하는지 확인하고, 구현 중 새로 발견한 한계를 PRD에 추가한다.
7. 독립 코드 리뷰를 받고, 발견된 차단 이슈만 수정 후 재검증한다.
8. 커밋: `test: verify order payment API`
