# 주문·결제 API 구현 계획

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 멱등키 기반 Cart에서만 주문을 생성하고, 단일 `POST /payments` API로 온라인 카드·오프라인 카드·포인트 결제를 처리한다.

**Architecture:** Spring MVC Controller → Transactional Service → Spring Data JPA Repository 구조를 사용한다. Cart는 JSON 주문 초안을 저장하고 주문 전환 시에만 Order/OrderLine으로 복사한다. 결제수단별 Gateway 인터페이스를 유지하되 API는 `method`로 Gateway를 선택한다.

**Tech Stack:** Kotlin, Spring Boot, Spring MVC, Bean Validation, Spring Data JPA, SQLite, MockMvc, JUnit 5.

**Specification:** `docs/specs/order-payment-api.md`

---

## Task 1: 테스트 DB와 Cart API 계약

**Files:**
- Create: `src/test/resources/application.properties`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartApiIntegrationTest.kt`
- Modify: `src/test/kotlin/io/github/dudco/paymentplayground/PaymentPlaygroundApplicationTests.kt`

1. `MockMvc` 기반 통합 테스트에 `POST /order/cart`의 키 발급, 동일 키 갱신, `GET /order/cart` 조회를 작성한다.
2. 실행: `./gradlew test --tests '*CartApiIntegrationTest'`
3. 기대: Controller가 없으므로 404로 실패한다.
4. 테스트 전용 SQLite DB를 `create-drop`으로 구성한다.
5. 테스트가 요구하는 최소 Cart API를 구현한다.
6. 같은 명령으로 GREEN을 확인한다.
7. 커밋: `test: cover cart API contract`

## Task 2: Cart 영속화와 멱등키 상태 전이

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/Cart.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartRepository.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartService.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartController.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartDtos.kt`

1. 빈 라인, 0 이하 수량/단가, 키 없는 조회를 다루는 RED 테스트를 추가한다.
2. 실행: `./gradlew test --tests '*CartApiIntegrationTest'`
3. Cart 엔티티의 `ACTIVE` / `ORDER_CREATED`, JSON 스냅샷, UUID 키 발급을 최소 구현한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add idempotent cart draft API`

## Task 3: Cart에서 불변 Order 생성

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/Order.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderLine.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderRepository.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderService.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderController.kt`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderApiIntegrationTest.kt`

1. Cart 없는 주문 거절, GET 없이 주문 생성, 같은 키 재호출의 기존 주문 반환, 주문 후 Cart 갱신 거절의 RED 테스트를 작성한다.
2. 실행: `./gradlew test --tests '*OrderApiIntegrationTest'`
3. Cart·Order·OrderLine을 하나의 트랜잭션에서 전이하는 최소 구현을 작성한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: create orders from cart drafts`

## Task 4: 결제 도메인과 Gateway 인터페이스

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/Payment.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentCancellation.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/gateway/PaymentGateways.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/gateway/FakePaymentGateways.kt`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/payment/FakePaymentGatewayTest.kt`

1. 온라인 카드·오프라인 카드·포인트 각각의 성공 및 오프라인 거절 RED 테스트를 작성한다.
2. 실행: `./gradlew test --tests '*FakePaymentGatewayTest'`
3. 전용 Gateway 인터페이스와 Fake 구현체를 최소 구현한다. PG 토큰·바코드 원문은 결과나 메타에 넣지 않는다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add fake payment gateways`

## Task 5: 단일 Payment API와 상태 전이

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentRepository.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentService.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentController.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentDtos.kt`
- Create: `src/test/kotlin/io/github/dudco/paymentplayground/payment/PaymentApiIntegrationTest.kt`

1. `POST /payments`에서 `method`별 승인 성공, 잘못된 세부정보, 금액 불일치, 이미 결제된 주문 거절 RED 테스트를 작성한다.
2. 실행: `./gradlew test --tests '*PaymentApiIntegrationTest'`
3. `method` 분기와 Payment/Order 상태 전이를 최소 구현한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add method-based payment API`

## Task 6: 전액 취소와 API 오류 형식

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/support/ApiExceptionHandler.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/support/DomainExceptions.kt`
- Modify: `src/main/kotlin/io/github/dudco/paymentplayground/payment/PaymentService.kt`
- Modify: `src/test/kotlin/io/github/dudco/paymentplayground/payment/PaymentApiIntegrationTest.kt`

1. 승인 결제 취소 성공, 미승인 결제 취소 거절, 공통 오류 JSON RED 테스트를 작성한다.
2. 실행: `./gradlew test --tests '*PaymentApiIntegrationTest'`
3. PaymentCancellation, 전액 취소 상태 전이, `code`/`message` 오류 응답을 최소 구현한다.
4. 같은 명령으로 GREEN을 확인한다.
5. 커밋: `feat: add payment cancellation API`

## Task 7: 전체 회귀 검증과 리뷰

1. 실행: `./gradlew test`
2. 실행: `./gradlew build`
3. 실행: `git diff --check`
4. 민감정보 저장·로그 금지 정책을 추가 코드와 테스트에서 검토한다.
5. 독립 코드 리뷰를 받고, 발견된 차단 이슈만 수정 후 재검증한다.
6. 커밋: `test: verify order payment API`
