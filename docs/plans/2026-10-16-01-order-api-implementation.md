# 주문 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 장바구니의 체크아웃 멱등키로 주문을 만들고(`POST /orders`, 같은 키 재호출은 기존 주문 반환), 주문 한 건을 조회하는(`GET /orders/{orderId}`) API를 만든다.

**Architecture:** `Order`·`OrderLine` JPA 엔티티를 Flyway `V1__create_orders.sql`로 만든 `orders`·`order_lines`에 저장한다. `OrderService`가 `CartRepository`로 장바구니를 읽고 `ProductCatalog`로 상품명·단가를 스냅샷해, `TransactionTemplate` 안에서 주문을 저장한 뒤 트랜잭션 밖에서 장바구니를 지운다. `OrderController`는 `@CurrentCustomer`와 `Idempotency-Key` 헤더를 받아 `201`/`200`을 구분해 응답한다.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.1.1(WebMVC, Data JPA, Flyway), Hibernate 7 + `SQLiteDialect`, sqlite-jdbc, JUnit 5

**Spec:** [주문 API 명세](../specs/order-api.md). 결정 배경은 [ADR-0004](../adr/0004-flyway-schema-migration.md), [ADR-0007](../adr/0007-checkout-idempotency-key.md). 장바구니 쪽 계약은 [장바구니 API 명세](../specs/cart-api.md).

---

## Global Constraints

- 루트 패키지 `io.github.dudco.paymentplayground`, 새 코드는 `order` 패키지. 새 의존성을 추가하지 않는다. `order → cart`, `order → product` 방향만 허용하고 `cart`는 `order`를 참조하지 않는다.
- 자동 테스트는 Redis 서버 없이 통과해야 한다. 주문 테스트는 `CartRepository`를 테스트용 `InMemoryCartRepository`로 대체한다. RDB는 기존처럼 Flyway를 적용한 SQLite 파일 DB(`build/test-db`)다.
- 헤더 이름은 정확히 `X-Customer-ID`, `Idempotency-Key`.
- 마이그레이션 파일은 `src/main/resources/db/migration/V1__create_orders.sql` 하나. 테이블 `orders`, `order_lines`. 엔티티 클래스 이름은 `Order`, `OrderLine`이고, JPQL 예약어 `ORDER`와 겹치지 않도록 `@Entity(name = "CustomerOrder")`로 둔다.
- 열 타입은 문자열·ID·시각 `TEXT`, 숫자 `INTEGER` 두 가지만 쓴다. 모든 엔티티 `@Column`/`@JoinColumn`에 마이그레이션과 같은 `columnDefinition`(`"TEXT"` 또는 `"INTEGER"`)을 적는다. `ddl-auto=validate`가 JDBC 타입 코드 대신 선언 타입 이름으로 일치를 판단하게 하기 위해서다.
- 시각(`created_at`, `updated_at`)은 `Instant`를 ISO-8601 UTC 문자열(`Instant.toString()`)로 저장한다. 변환은 `support/InstantTextConverter`가 한다. 현재 시각은 `Clock` 빈에서 얻는다.
- `order_lines.order_id`의 FK는 선언만 하고 SQLite `foreign_keys` pragma는 켜지 않는다(기본값 꺼짐). `DatabaseCleaner`가 테이블을 순서 없이 비우기 때문이다.
- 주문 ID·주문 라인 ID는 Hibernate가 발급하는 UUID 문자열(`@GeneratedValue(strategy = GenerationType.UUID)`, Kotlin 타입 `String?`).
- 상태 값은 `CREATED`만 쓴다. DB `CHECK`는 `('CREATED', 'PAID')`를 허용한다.
- 수량 1–99, 단가 0 이상, `line_amount = unit_price * quantity`, `total_amount = Σ line_amount`.
- 오류 코드와 메시지(`order/OrderErrors.kt`):
  - `404 CART_NOT_FOUND` — `주문할 장바구니가 없습니다.`
  - `404 ORDER_NOT_FOUND` — `주문을 찾을 수 없습니다.`
  - `409 IDEMPOTENCY_KEY_MISMATCH` — `멱등키가 현재 장바구니와 맞지 않습니다.`
  - 공백 `Idempotency-Key` — `InvalidRequestException("Idempotency-Key 헤더가 비어 있습니다.")`. 헤더 누락은 기존 `MissingRequestHeaderException` 처리(`필수 헤더가 없습니다: Idempotency-Key`)를 그대로 쓴다.
  - 오류 메시지에 멱등키 값을 넣지 않는다.
- 응답 JSON 필드: `orderId`, `customerId`, `idempotencyKey`, `status`, `lines[]`(`productId`, `productName`, `unitPrice`, `quantity`, `lineAmount`), `totalAmount`, `createdAt`. `lines`는 `productId` 오름차순.

## Review Focus

스펙이 명시했지만 수용 조건 목록에는 따로 없거나, 사용자가 만날 가능성이 높은 경우와 기대 동작이다. 각 항목의 테스트는 담당 Task에 들어 있다.

1. `Idempotency-Key: " key-1 "`(앞뒤 공백) → 공백을 제거해 장바구니 키 `key-1`과 같다고 보고 주문을 만든다 (Task 4).
2. `POST /orders`에 JSON 본문이 함께 옴 → 본문을 무시하고 장바구니 내용으로 `201` (Task 4).
3. 주문 후 고객이 새로 담아 장바구니가 새 키로 다시 생긴 상태에서 이전 키로 재요청 → 기존 주문을 `200`으로 돌려주고, 새 장바구니는 지우지 않는다 (Task 3).
4. 장바구니의 모든 라인이 카탈로그에서 사라짐 → `404 CART_NOT_FOUND`, 주문이 만들어지지 않는다. 일부만 사라지면 남은 라인으로 주문한다 (Task 3).
5. 라인을 상품 ID 순서가 아닌 순서로 넣어 저장한 뒤 DB에서 다시 읽음 → `productId` 오름차순으로 읽힌다 (Task 2).

## 테스트 전략

| 대상 | 방식 |
| --- | --- |
| `Order`, `OrderLine`, `InstantTextConverter` | 순수 단위 테스트 |
| 마이그레이션·엔티티 매핑·`OrderRepository` | `@SpringBootTest`. 저장 후 다시 읽기, `JdbcTemplate`으로 DB 제약 위반 확인 |
| `OrderService` | `@SpringBootTest` + `OrderTestConfiguration`(`@Primary` 테스트 빈: `MutableClock`, `InMemoryCartRepository`, `MutableProductCatalog`) |
| `OrderController` | `@SpringBootTest` + `@AutoConfigureMockMvc` + 같은 `OrderTestConfiguration` |

주문 테스트는 모두 `@BeforeEach`에서 `DatabaseCleaner.clean()`, `InMemoryCartRepository.clear()`, `MutableProductCatalog.reset()`을 호출하고 시계를 고정 시각으로 되돌린다. 같은 `@Import(OrderTestConfiguration::class)`를 쓰면 Spring 테스트 컨텍스트가 재사용된다.

테스트 실행 명령의 형식: `./gradlew test --tests '<FQCN>'`. 고정 시각은 `2026-10-14T03:10:00Z`. 기본 장바구니는 `customer-001`, 키 `key-1`, `americano` 2·`cake` 1(합계 `15000`)이다.

---

### Task 1: 주문 엔티티, 스키마 마이그레이션, 시각 변환기

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/support/InstantTextConverter.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/Order.kt` (`Order`, `OrderLine`, `OrderStatus`)
- Create: `src/main/resources/db/migration/V1__create_orders.sql`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderTest.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/support/InstantTextConverterTest.kt`

**Interfaces:**
- Produces (`InstantTextConverter.kt`): `@Converter class InstantTextConverter : AttributeConverter<Instant, String>` — `Instant.toString()` / `Instant.parse()`. `null`은 `null`로 둔다.
- Produces (`Order.kt`):
  - `enum class OrderStatus { CREATED }` — `PAID`는 결제 스펙에서 추가
  - `@Entity @Table(name = "order_lines") class OrderLine(productId: String, productName: String, unitPrice: Long, quantity: Int)` — 읽기 전용 프로퍼티 `id: String?`(UUID 생성), `productId`, `productName`, `unitPrice`, `quantity`, `lineAmount: Long = unitPrice * quantity`. `init`에서 `productId`·`productName`이 공백이 아님, `unitPrice >= 0`, `quantity in 1..99`를 `require`로 검사한다.
  - `@Entity(name = "CustomerOrder") @Table(name = "orders") class Order(idempotencyKey: String, customerId: String, lines: List<OrderLine>, now: Instant)` — 읽기 전용 프로퍼티 `id: String?`(UUID 생성), `idempotencyKey`, `customerId`, `status: OrderStatus`(`@Enumerated(EnumType.STRING)`, 생성 시 `CREATED`), `lines: List<OrderLine>`, `totalAmount: Long`, `createdAt: Instant`, `updatedAt: Instant`. 생성 시 `lines`를 `productId` 오름차순으로 정렬해 담고, `totalAmount = Σ lineAmount`, `createdAt = updatedAt = now`. `init`에서 `idempotencyKey`·`customerId`가 공백이 아님, `lines`가 비어 있지 않음, `productId`가 중복되지 않음을 `require`로 검사한다.
  - 매핑: `lines`는 `@OneToMany(cascade = [CascadeType.ALL]) @JoinColumn(name = "order_id", nullable = false, updatable = false, columnDefinition = "TEXT") @OrderBy("productId ASC")`(단방향, `OrderLine`은 `Order`를 참조하지 않는다). 시각 필드는 `@Convert(converter = InstantTextConverter::class)`. 열 이름은 스펙 3장의 snake_case이며 Global Constraints의 `columnDefinition` 규칙을 따른다.
  - Task 2–4가 사용한다.
- Produces (`V1__create_orders.sql`):
  - 스펙 3장의 두 테이블. `orders`: `id TEXT PRIMARY KEY`, `idempotency_key TEXT NOT NULL UNIQUE`, `customer_id TEXT NOT NULL`, `status TEXT NOT NULL CHECK (status IN ('CREATED', 'PAID'))`, `total_amount INTEGER NOT NULL CHECK (total_amount >= 0)`, `created_at TEXT NOT NULL`, `updated_at TEXT NOT NULL`. `order_lines`: `id TEXT PRIMARY KEY`, `order_id TEXT NOT NULL REFERENCES orders(id)`, `product_id TEXT NOT NULL`, `product_name TEXT NOT NULL`, `unit_price INTEGER NOT NULL CHECK (unit_price >= 0)`, `quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99)`, `line_amount INTEGER NOT NULL CHECK (line_amount = unit_price * quantity)`, `UNIQUE (order_id, product_id)`. 인덱스 `CREATE INDEX idx_order_lines_order_id ON order_lines (order_id)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class OrderTest {
    private val now = Instant.parse("2026-10-14T03:10:00Z")

    @Test
    fun `라인 금액과 총액을 계산하고 라인을 상품 ID 오름차순으로 둔다`() {
        val order = Order(
            "key-1", "customer-001",
            listOf(OrderLine("cake", "케이크", 6000, 1), OrderLine("americano", "아메리카노", 4500, 2)),
            now,
        )

        assertEquals(listOf("americano", "cake"), order.lines.map { it.productId })
        assertEquals(listOf(9000L, 6000L), order.lines.map { it.lineAmount })
        assertEquals(15000L, order.totalAmount)
        assertEquals(OrderStatus.CREATED, order.status)
        assertEquals(now, order.createdAt)
        assertEquals(now, order.updatedAt)
        assertNull(order.id)
    }

    @Test
    fun `수량 경계값 1과 99, 단가 0은 허용하고 범위 밖과 빈 이름은 IllegalArgumentException이다`() {
        OrderLine("cake", "케이크", 0, 1)
        OrderLine("cake", "케이크", 6000, 99)

        assertFailsWith<IllegalArgumentException> { OrderLine("cake", "케이크", 6000, 0) }
        assertFailsWith<IllegalArgumentException> { OrderLine("cake", "케이크", 6000, 100) }
        assertFailsWith<IllegalArgumentException> { OrderLine("cake", "케이크", -1, 1) }
        assertFailsWith<IllegalArgumentException> { OrderLine(" ", "케이크", 6000, 1) }
        assertFailsWith<IllegalArgumentException> { OrderLine("cake", "", 6000, 1) }
    }

    @Test
    fun `빈 라인, 중복 상품, 빈 멱등키·고객 ID는 IllegalArgumentException이다`() {
        val line = { OrderLine("cake", "케이크", 6000, 1) }

        assertFailsWith<IllegalArgumentException> { Order("key-1", "customer-001", emptyList(), now) }
        assertFailsWith<IllegalArgumentException> { Order("key-1", "customer-001", listOf(line(), line()), now) }
        assertFailsWith<IllegalArgumentException> { Order(" ", "customer-001", listOf(line()), now) }
        assertFailsWith<IllegalArgumentException> { Order("key-1", "", listOf(line()), now) }
    }
}
```

```kotlin
class InstantTextConverterTest {
    private val converter = InstantTextConverter()

    @Test
    fun `Instant를 ISO-8601 UTC 문자열로 바꾸고 되돌린다`() {
        val instant = Instant.parse("2026-10-14T03:10:00.123456Z")

        assertEquals("2026-10-14T03:10:00Z", converter.convertToDatabaseColumn(Instant.parse("2026-10-14T03:10:00Z")))
        assertEquals(instant, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(instant)))
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderTest' --tests 'io.github.dudco.paymentplayground.support.InstantTextConverterTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'Order'`, `Unresolved reference 'InstantTextConverter'`)

- [ ] **Step 3: Implement `InstantTextConverter`, `Order.kt`, and `V1__create_orders.sql` per Interfaces**

엔티티는 기존 `allOpen`·`kotlin("plugin.jpa")` 설정을 그대로 쓴다. 생성 후 바뀌면 안 되는 필드는 `protected set`으로 막는다. 엔티티와 마이그레이션을 같은 커밋에 넣는다. 둘 중 하나만 있으면 `ddl-auto=validate`가 기동을 실패시킨다.

- [ ] **Step 4: Run tests, including the existing context test, to verify they pass**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderTest' --tests 'io.github.dudco.paymentplayground.support.InstantTextConverterTest' --tests 'io.github.dudco.paymentplayground.PaymentPlaygroundApplicationTests'`
Expected: PASS (`OrderTest` 3개, `InstantTextConverterTest` 1개, `PaymentPlaygroundApplicationTests` 4개 — Flyway가 V1을 적용하고 `validate`가 통과한다). `Schema-validation: wrong column type` 오류가 나면 해당 `@Column`의 `columnDefinition`이 마이그레이션의 선언 타입과 같은지 확인한다.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/order src/main/kotlin/io/github/dudco/paymentplayground/support/InstantTextConverter.kt src/main/resources/db/migration/V1__create_orders.sql src/test/kotlin/io/github/dudco/paymentplayground/order/OrderTest.kt src/test/kotlin/io/github/dudco/paymentplayground/support/InstantTextConverterTest.kt
git commit -m "feat: 주문 엔티티와 주문 테이블 마이그레이션 추가"
```

---

### Task 2: 주문 저장소와 DB 제약 검증

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderRepository.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderPersistenceTest.kt`

**Interfaces:**
- Consumes: `Order`, `OrderLine`, `OrderStatus`, `V1__create_orders.sql` (Task 1), `DatabaseCleaner` (기존 테스트 지원)
- Produces:
  - `interface OrderRepository : JpaRepository<Order, String> { fun findByIdempotencyKey(idempotencyKey: String): Order? }` — Task 3이 사용한다.

- [ ] **Step 1: Write the failing test**

```kotlin
@SpringBootTest
class OrderPersistenceTest(
    @Autowired private val orderRepository: OrderRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) {
    private val now = Instant.parse("2026-10-14T03:10:00Z")

    @BeforeEach
    fun setUp() = databaseCleaner.clean()

    @Test
    fun `주문과 라인을 저장하고 다시 읽으면 같은 값이고 라인은 상품 ID 오름차순이다`() {
        val id = assertNotNull(orderRepository.save(order()).id)

        transactionTemplate.executeWithoutResult {
            val found = assertNotNull(orderRepository.findByIdempotencyKey("key-1"))
            assertEquals(id, found.id)
            assertEquals("customer-001", found.customerId)
            assertEquals(OrderStatus.CREATED, found.status)
            assertEquals(15000L, found.totalAmount)
            assertEquals(now, found.createdAt)
            assertEquals(now, found.updatedAt)
            assertEquals(
                listOf(Triple("americano", 4500L, 2), Triple("cake", 6000L, 1)),
                found.lines.map { Triple(it.productId, it.unitPrice, it.quantity) },
            )
        }
        assertEquals("2026-10-14T03:10:00Z", jdbc.queryForObject("SELECT created_at FROM orders", String::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Int::class.java, id))
    }

    @Test
    fun `없는 멱등키는 null이다`() {
        assertNull(orderRepository.findByIdempotencyKey("unknown"))
    }

    @Test
    fun `DB 제약이 잘못된 행을 거절한다`() {
        insertOrder("o1", "key-1")

        assertFailsWith<DataAccessException> { insertOrder("o2", "key-1") }
        assertFailsWith<DataAccessException> { insertOrder("o3", "key-3", status = "UNKNOWN") }
        assertFailsWith<DataAccessException> { insertLine("l1", "o1", unitPrice = 6000, quantity = 0, lineAmount = 0) }
        assertFailsWith<DataAccessException> { insertLine("l2", "o1", unitPrice = 6000, quantity = 100, lineAmount = 600000) }
        assertFailsWith<DataAccessException> { insertLine("l3", "o1", unitPrice = 6000, quantity = 2, lineAmount = 6000) }
        insertLine("l4", "o1", unitPrice = 6000, quantity = 2, lineAmount = 12000)
        assertFailsWith<DataAccessException> { insertLine("l5", "o1", unitPrice = 6000, quantity = 1, lineAmount = 6000) }
    }
}
```

`order()`는 Task 1 테스트와 같은 `key-1`/`customer-001`/`cake` 1·`americano` 2 주문(라인을 `cake` 먼저 넣어 Review Focus 5를 검증)을 만든다. `insertOrder(id, key, status = "CREATED")`는 `jdbc.update`로 `orders`에 `customer-001`, 총액 `0`, 시각 `2026-10-14T03:10:00Z` 행을 넣는다. `insertLine(id, orderId, unitPrice, quantity, lineAmount)`은 상품 `cake`/`케이크` 라인을 넣는다(`l4`와 `l5`는 같은 `(order_id, product_id)`라 `l5`가 unique 위반). sqlite-jdbc 예외는 Spring이 `DataIntegrityViolationException`이 아닌 다른 `DataAccessException` 하위 타입으로 바꿀 수 있으므로 상위 타입으로 단언한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderPersistenceTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'OrderRepository'`)

- [ ] **Step 3: Implement `OrderRepository` per Interfaces**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderPersistenceTest'`
Expected: PASS (3개)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/order/OrderRepository.kt src/test/kotlin/io/github/dudco/paymentplayground/order/OrderPersistenceTest.kt
git commit -m "feat: 주문 저장소와 DB 제약 테스트 추가"
```

---

### Task 3: OrderService

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderErrors.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderService.kt`
- Modify: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartTestFixtures.kt` (`InMemoryCartRepository`에 삭제 실패 스위치 추가)
- Create (test fixtures): `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderTestFixtures.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderServiceTest.kt`

**Interfaces:**
- Consumes: `Order`, `OrderLine`, `OrderStatus` (Task 1), `OrderRepository` (Task 2), `CartRepository`, `StoredCart`, `Cart`, 장바구니 테스트 지원(`MutableClock`, `InMemoryCartRepository`), `ProductCatalog`, `Product`, `MockProductCatalog`, `NotFoundException`, `ConflictException`
- Produces (`CartTestFixtures.kt`): `InMemoryCartRepository`에 `var failOnDelete: Boolean = false` 추가. `true`면 `delete`가 `IllegalStateException("장바구니 삭제 실패(테스트)")`을 던진다. `clear()`가 `failOnDelete`도 `false`로 되돌린다. 기존 장바구니 테스트 동작은 바뀌지 않는다.
- Produces (`OrderTestFixtures.kt`) — 이 Task와 Task 4가 사용한다:
  - `class MutableProductCatalog : ProductCatalog` — `reset()`은 `MockProductCatalog().findAll()`의 5개 상품으로 되돌린다(생성 시에도 호출). `fun put(product: Product)`(같은 ID면 교체), `fun remove(productId: String)`.
  - `@TestConfiguration class OrderTestConfiguration` — `@Bean @Primary fun testClock(): MutableClock`(`2026-10-14T03:10:00Z`), `@Bean @Primary fun inMemoryCartRepository(testClock: MutableClock): InMemoryCartRepository`, `@Bean @Primary fun mutableProductCatalog(): MutableProductCatalog`. 빈 이름은 기존 `clock`, `redisCartRepository`, `mockProductCatalog`와 겹치지 않는다(Spring Boot는 빈 덮어쓰기를 허용하지 않는다).
  - `val ORDER_TEST_NOW: Instant = Instant.parse("2026-10-14T03:10:00Z")`
  - `fun InMemoryCartRepository.saveDefaultCart(customerId: String = "customer-001", key: String = "key-1")` — `Cart.create(customerId, key, mapOf("americano" to 2, "cake" to 1), ORDER_TEST_NOW)`을 저장한다.
- Produces (`OrderErrors.kt`): `fun cartNotFound(): NotFoundException`, `fun orderNotFound(): NotFoundException`, `fun idempotencyKeyMismatch(): ConflictException` — 코드·메시지는 Global Constraints
- Produces (`OrderService.kt`):
  - `data class OrderLineView(val productId: String, val productName: String, val unitPrice: Long, val quantity: Int, val lineAmount: Long)`
  - `data class OrderView(val orderId: String, val customerId: String, val idempotencyKey: String, val status: OrderStatus, val lines: List<OrderLineView>, val totalAmount: Long, val createdAt: Instant)`
  - `data class OrderPlacement(val order: OrderView, val created: Boolean)` — `created`가 `false`면 재호출
  - `@Service class OrderService(private val orderRepository: OrderRepository, private val cartRepository: CartRepository, private val productCatalog: ProductCatalog, private val transactionTemplate: TransactionTemplate, private val clock: Clock)`
    - `fun place(customerId: String, idempotencyKey: String): OrderPlacement` — 인자는 Controller가 공백 제거·빈 값 검사를 끝낸 값이다. 스펙 5.1의 2–7단계 순서 그대로:
      ① `transactionTemplate` 안에서 `findByIdempotencyKey` → 있으면 고객이 같을 때 `OrderView`로 바꿔 반환 준비(트랜잭션 안에서 바꿔야 지연 로딩 `lines`를 읽을 수 있다), 다르면 `idempotencyKeyMismatch()`. 재호출이면 트랜잭션 밖에서 `cartRepository.find(customerId)?.cart?.idempotencyKey == idempotencyKey`일 때만 `delete`하고(삭제 예외는 ⑤와 같이 처리) `OrderPlacement(view, false)`.
      ② `cartRepository.find(customerId)` 없음 → `cartNotFound()`. 키가 다름 → `idempotencyKeyMismatch()`.
      ③ 카탈로그에 있는 라인만 `OrderLine(product.id, product.name, product.price, quantity)`로 만들고, 없으면 `cartNotFound()`.
      ④ `transactionTemplate.execute { orderRepository.save(Order(...)).toView() }`.
      ⑤ 트랜잭션이 끝난 뒤 `cartRepository.delete(customerId)`. 예외는 잡아서 `warn` 로그(주문 ID 포함)만 남긴다.
    - `@Transactional(readOnly = true) fun get(customerId: String, orderId: String): OrderView` — 없거나 고객이 다르면 `orderNotFound()`
- Task 4가 사용한다.

- [ ] **Step 1: Write the failing test**

```kotlin
@SpringBootTest
@Import(OrderTestConfiguration::class)
class OrderServiceTest(
    @Autowired private val service: OrderService,
    @Autowired private val orderRepository: OrderRepository,
    @Autowired private val carts: InMemoryCartRepository,
    @Autowired private val catalog: MutableProductCatalog,
    @Autowired private val clock: MutableClock,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) {
    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
        carts.clear()
        catalog.reset()
        clock.now = ORDER_TEST_NOW
    }

    @Test
    fun `장바구니로 주문을 만들고 커밋 후 장바구니를 지운다`() {
        carts.saveDefaultCart()

        val placement = service.place("customer-001", "key-1")

        assertTrue(placement.created)
        with(placement.order) {
            assertEquals("customer-001", customerId)
            assertEquals("key-1", idempotencyKey)
            assertEquals(OrderStatus.CREATED, status)
            assertEquals(
                listOf(
                    OrderLineView("americano", "아메리카노", 4500, 2, 9000),
                    OrderLineView("cake", "케이크", 6000, 1, 6000),
                ),
                lines,
            )
            assertEquals(15000L, totalAmount)
            assertEquals(ORDER_TEST_NOW, createdAt)
        }
        assertEquals(1, orderRepository.count())
        assertFalse(carts.contains("customer-001"))
    }

    @Test
    fun `같은 키 재호출은 새 주문을 만들지 않고 기존 주문을 반환한다`() {
        carts.saveDefaultCart()
        val first = service.place("customer-001", "key-1")
        clock.now = ORDER_TEST_NOW.plusSeconds(60)

        val second = service.place("customer-001", "key-1")

        assertFalse(second.created)
        assertEquals(first.order, second.order)
        assertEquals(1, orderRepository.count())
    }
}
```

같은 클래스에 다음 테스트를 추가한다. 오류는 `assertFailsWith<NotFoundException>`/`assertFailsWith<ConflictException>` 후 `code`를 단언한다.

- `재호출 때 같은 키의 장바구니가 남아 있으면 지우고 기존 주문을 반환한다`: `place` 후 `carts.save(Cart.create("customer-001", "key-1", mapOf("cookie" to 5), ORDER_TEST_NOW))` → 재호출. `created` false, `lines`는 처음 주문(`americano`, `cake`)과 같고, `carts.contains("customer-001")`가 false.
- `재호출 때 장바구니가 새 키로 다시 생겼으면 지우지 않는다` (Review Focus 3): `place` 후 `key-2` 장바구니 저장 → `key-1` 재호출. `created` false, `key-2` 장바구니가 그대로 남는다.
- `장바구니가 없으면 CART_NOT_FOUND이고 주문을 만들지 않는다`: `CART_NOT_FOUND`, `orderRepository.count()` 0.
- `장바구니 키와 다른 키는 IDEMPOTENCY_KEY_MISMATCH이고 장바구니는 그대로다`: `key-1` 장바구니, `key-2`로 요청. `IDEMPOTENCY_KEY_MISMATCH`, 주문 0건, 장바구니 남음.
- `다른 고객의 주문 키는 IDEMPOTENCY_KEY_MISMATCH다`: `customer-001`이 `key-1`로 주문 → `customer-002`(장바구니 `key-9`)가 `key-1`로 요청. `IDEMPOTENCY_KEY_MISMATCH`, 주문 1건, `customer-002` 장바구니 남음.
- `카탈로그에 없는 상품 라인은 빼고 주문한다` (Review Focus 4): `catalog.remove("cake")` → `lines`는 `americano`만, `totalAmount` 9000.
- `주문 가능한 라인이 없으면 CART_NOT_FOUND다` (Review Focus 4): `cake` 1만 담긴 장바구니, `catalog.remove("cake")` → `CART_NOT_FOUND`, 주문 0건.
- `주문 생성이 실패하면 장바구니를 지우지 않는다`: `catalog.put(Product("americano", "아메리카노", -1))` → `place`가 `IllegalArgumentException`(`OrderLine` 단가 검증), 주문 0건, 장바구니 남음. 실제 커밋 실패(동시 요청의 unique 위반)는 이 단계의 재현 대상이 아니므로, 저장 전 예외로 "삭제는 주문 확정 뒤에만"을 검증한다.
- `장바구니 삭제가 실패해도 주문은 만들어진다`: `carts.failOnDelete = true` → `created` true, 주문 1건.
- `주문 후 가격이 바뀌어도 조회는 주문 시점 상품명·단가다`: `place` → `catalog.put(Product("americano", "아이스 아메리카노", 9999))` → `get("customer-001", orderId)`의 `americano` 라인이 `아메리카노`/4500/9000, `totalAmount` 15000.
- `없는 주문, 다른 고객의 주문, UUID가 아닌 ID는 ORDER_NOT_FOUND다`: `get("customer-002", orderId)`, `get("customer-001", UUID.randomUUID().toString())`, `get("customer-001", "not-a-uuid")` 모두 `ORDER_NOT_FOUND`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderServiceTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'OrderService'`)

- [ ] **Step 3: Implement `OrderErrors.kt`, `OrderService`, and the test fixtures per Interfaces**

`OrderService.place`에는 `@Transactional`을 붙이지 않는다. 장바구니 삭제가 커밋 뒤에 일어나야 하기 때문이다.

- [ ] **Step 4: Run the service test and the existing cart tests**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderServiceTest' --tests 'io.github.dudco.paymentplayground.cart.*'`
Expected: PASS (`OrderServiceTest` 13개, 장바구니 테스트는 기존과 같은 개수)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/order src/test/kotlin/io/github/dudco/paymentplayground/cart/CartTestFixtures.kt src/test/kotlin/io/github/dudco/paymentplayground/order/OrderTestFixtures.kt src/test/kotlin/io/github/dudco/paymentplayground/order/OrderServiceTest.kt
git commit -m "feat: 장바구니 멱등키로 주문을 만드는 주문 서비스 추가"
```

---

### Task 4: OrderController

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/order/OrderController.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/order/OrderControllerTest.kt`

**Interfaces:**
- Consumes: `OrderService`, `OrderView`, `OrderPlacement`, test fixtures (Task 3), `CurrentCustomer`, `InvalidRequestException`
- Produces:
  - `@RestController @RequestMapping("/orders") class OrderController(private val orderService: OrderService)`
    - `@PostMapping fun place(@CurrentCustomer customerId: String, @RequestHeader("Idempotency-Key") idempotencyKey: String): ResponseEntity<OrderView>` — 키 앞뒤 공백 제거, 비면 `InvalidRequestException`. `created`면 `201`과 `Location: /orders/{orderId}`, 아니면 `200`. 요청 본문 파라미터를 두지 않는다.
    - `@GetMapping("/{orderId}") fun get(@CurrentCustomer customerId: String, @PathVariable orderId: String): OrderView`

- [ ] **Step 1: Write the failing test**

```kotlin
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderTestConfiguration::class)
class OrderControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val carts: InMemoryCartRepository,
    @Autowired private val catalog: MutableProductCatalog,
    @Autowired private val clock: MutableClock,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) {
    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
        carts.clear()
        catalog.reset()
        clock.now = ORDER_TEST_NOW
    }

    @Test
    fun `주문을 만들면 201, Location, 주문 본문을 반환한다`() {
        carts.saveDefaultCart()

        val result = place("key-1")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").isNotEmpty())
            .andExpect(jsonPath("$.customerId").value("customer-001"))
            .andExpect(jsonPath("$.idempotencyKey").value("key-1"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.lines[0].productId").value("americano"))
            .andExpect(jsonPath("$.lines[0].productName").value("아메리카노"))
            .andExpect(jsonPath("$.lines[0].unitPrice").value(4500))
            .andExpect(jsonPath("$.lines[0].quantity").value(2))
            .andExpect(jsonPath("$.lines[0].lineAmount").value(9000))
            .andExpect(jsonPath("$.lines[1].productId").value("cake"))
            .andExpect(jsonPath("$.totalAmount").value(15000))
            .andExpect(jsonPath("$.createdAt").value("2026-10-14T03:10:00Z"))
            .andReturn()

        val orderId = JsonPath.read<String>(result.response.contentAsString, "$.orderId")
        assertEquals("/orders/$orderId", result.response.getHeader("Location"))
    }

    private fun place(key: String?, customerId: String? = "customer-001", body: String? = null): ResultActions =
        mockMvc.perform(
            post("/orders").apply {
                customerId?.let { header("X-Customer-ID", it) }
                key?.let { header("Idempotency-Key", it) }
                body?.let { contentType(MediaType.APPLICATION_JSON).content(it) }
            },
        )

    private fun get(orderId: String, customerId: String = "customer-001"): ResultActions =
        mockMvc.perform(MockMvcRequestBuilders.get("/orders/$orderId").header("X-Customer-ID", customerId))
}
```

같은 클래스에 다음 테스트를 추가한다. 주문 ID는 위와 같이 `JsonPath.read`로 읽는다.

- `같은 키 재호출은 200과 같은 주문을 반환한다`: 두 번째 `place("key-1")`이 `200`, `$.orderId`가 첫 응답과 같다.
- `멱등키 앞뒤 공백은 제거한다` (Review Focus 1): `place(" key-1 ")` → `201`, `$.idempotencyKey` `key-1`.
- `요청 본문은 무시하고 장바구니로 주문한다` (Review Focus 2): `place("key-1", body = """{"lines":[{"productId":"cookie","quantity":5}]}""")` → `201`, `$.lines[*].productId`가 `["americano","cake"]`.
- `헤더가 없거나 비면 400 VALIDATION_ERROR다`: `place(null)`, `place("   ")`, `place("key-1", customerId = null)` 모두 `400`, `$.code` `VALIDATION_ERROR`.
- `장바구니가 없으면 404 CART_NOT_FOUND다`: `place("key-1")` → `404`, `$.code` `CART_NOT_FOUND`.
- `키가 다르면 409 IDEMPOTENCY_KEY_MISMATCH이고 메시지에 키가 없다`: `key-1` 장바구니, `place("key-2")` → `409`, `$.code` `IDEMPOTENCY_KEY_MISMATCH`, 응답 본문에 `key-2`가 없다.
- `본인 주문을 조회한다`: 주문 후 `get(orderId)` → `200`, `$.orderId`, `$.totalAmount` 15000, `$.lines.length()` 2.
- `다른 고객의 주문이나 없는 주문은 404 ORDER_NOT_FOUND다`: `get(orderId, "customer-002")`, `get("not-a-uuid")` → `404`, `$.code` `ORDER_NOT_FOUND`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderControllerTest'`
Expected: FAIL (`POST /orders`가 404 — 컨트롤러 없음)

- [ ] **Step 3: Implement `OrderController` per Interfaces**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderControllerTest'`
Expected: PASS (9개)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/order/OrderController.kt src/test/kotlin/io/github/dudco/paymentplayground/order/OrderControllerTest.kt
git commit -m "feat: 주문 생성·조회 API 추가"
```

---

### Task 5: 전체 검증과 문서 정리

**Files:**
- Modify (Step 3, 승인 후): `docs/data-model.md` 4·5장, `docs/specs/order-api.md` 상단 상태, `docs/prd.md` 단계표

- [ ] **Step 1: Redis 없이 전체 테스트 실행**

Redis 서버가 꺼진 상태에서 Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, 실패 0. 기존 44개 + 주문 관련 신규 테스트.

- [ ] **Step 2: 애플리케이션을 띄워 실제 Redis·SQLite로 수동 확인**

```bash
redis-server --daemonize yes   # 또는 docker run -d -p 6379:6379 redis:7
./gradlew bootRun
KEY=$(curl -s -X POST localhost:8080/cart -H 'X-Customer-ID: c1' -H 'Content-Type: application/json' \
  -d '{"lines":[{"productId":"americano","quantity":2}]}' | jq -r .idempotencyKey)
curl -si -X POST localhost:8080/orders -H 'X-Customer-ID: c1' -H "Idempotency-Key: $KEY"
curl -si -X POST localhost:8080/orders -H 'X-Customer-ID: c1' -H "Idempotency-Key: $KEY"
redis-cli EXISTS cart:c1
sqlite3 data/payment-playground.db 'SELECT * FROM orders; SELECT * FROM order_lines; SELECT version FROM flyway_schema_history;'
```

Expected:
- 첫 주문은 `201`과 `Location`, 두 번째는 `200`이고 `orderId`가 같다. `totalAmount` 9000
- `redis-cli EXISTS cart:c1`이 `0`
- `orders` 1행(`status` `CREATED`, `created_at`이 ISO-8601 문자열), `order_lines` 1행, Flyway 버전 `1`

- [ ] **Step 3: 스펙·데이터 모델 갱신 여부를 사람에게 확인**

구현이 스펙과 다르게 된 부분이 있으면 스펙을 먼저 고친다. 스펙이 승인되면 `data-model.md` 4·5장을 스펙 3장 기준으로 확정하고(초안 안내 문구 정리, FK 미강제·`TEXT` 시각 저장 반영), 스펙 상태와 PRD 단계표를 갱신한다.
