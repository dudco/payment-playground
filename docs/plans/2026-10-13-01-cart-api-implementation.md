# 상품·장바구니 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mock 상품 조회 API와, 고객별 장바구니를 전체 교체 방식으로 저장하며 체크아웃 멱등키를 발급하는 장바구니 API(`GET`/`POST /cart`)를 만든다.

**Architecture:** 상품은 `ProductCatalog` 인터페이스와 코드 내 `MockProductCatalog`로 제공한다. 장바구니는 불변 도메인 `Cart`를 `CartService`가 다루고, `RedisCartRepository`가 `cart:{customerId}`에 JSON 한 건을 `SET ... EX`로 저장한다. 고객 ID는 `@CurrentCustomer` 파라미터로 받고, 지금은 `X-Customer-ID` 헤더를 읽는 resolver가 채운다.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.1.1(WebMVC, Data Redis, Validation), Jackson 3(`tools.jackson`), JUnit 5, Mockito(Spring Boot 테스트 스타터에 포함)

**Spec:** [장바구니 API 명세](../specs/cart-api.md), [상품 조회 API 명세](../specs/product-api.md). 결정 배경은 [ADR-0005](../adr/0005-product-api-mock-catalog.md), [ADR-0006](../adr/0006-cart-per-customer.md), [ADR-0007](../adr/0007-checkout-idempotency-key.md).

## 진행 상황 (2026-10-14)

- Task 1–8: 테스트·구현 작성과 커밋 완료, Task별 리뷰 통과. 테스트 실행 단계(⏸)는 이 세션에서 빌드할 수 없어 실행하지 못했다.
- 최종 브랜치 리뷰: "With fixes" — 스펙 보완(`1d740e8`)은 반영 완료, 남은 조건은 로컬 `./gradlew test` 통과.
- Task 9(전체 검증): 미착수. 로컬에서 실행 필요.

---

## Global Constraints

- 루트 패키지 `io.github.dudco.paymentplayground`. 새 의존성을 추가하지 않는다.
- 자동 테스트는 Redis 서버 없이 통과해야 한다. 실제 Redis 저장은 Task 9에서 수동으로 확인한다.
- 오류 본문은 기존 `ApiErrorResponse(code, message)`. 검증 오류 코드는 `VALIDATION_ERROR`, 응답에 거부된 입력값을 싣지 않는다.
- 고객 헤더 이름은 정확히 `X-Customer-ID`.
- Redis 키는 `cart:{customerId}`, TTL 설정 키는 `payment-playground.cart.ttl`, 값 `30m`.
- 수량은 1 이상 99 이하.
- 장바구니 Redis 값에는 `customerId`, `idempotencyKey`, `lines`(`productId`, `quantity`), `createdAt`, `updatedAt`만 저장한다. 상품명·가격은 저장하지 않는다.
- 응답 `lines`는 `productId` 오름차순.
- 시각은 `Clock` 빈에서 얻는다(`Instant.now()` 금지). 응답·저장 시각은 ISO-8601 UTC 문자열(Jackson 3 기본값).
- Mock 상품: `americano` 아메리카노 4500, `latte` 카페라테 5000, `cake` 케이크 6000, `sandwich` 샌드위치 7500, `cookie` 쿠키 2500 (이 순서).

## Review Focus

스펙이 명시하지 않았지만 사용자가 만날 가능성이 높은 경우와 기대 동작이다. 각 항목의 테스트는 담당 Task에 들어 있다.

1. `{"lines": null}` 또는 `lines` 원소가 `null`인 본문 → 500이 아니라 `400 VALIDATION_ERROR` (Task 8).
2. 같은 본문으로 `POST /cart`를 다시 보냄(재시도) → 멱등키와 `lines`가 그대로이고 오류가 없다 (Task 8).
3. 수량 경계값 1과 99 → 허용된다. 0과 100만 거절된다 (Task 8).
4. `X-Customer-ID` 값 앞뒤 공백(`" c1 "`) → 앞뒤 공백을 제거해 `c1`과 같은 고객으로 본다 (Task 3). 스펙에 없는 결정이므로 검토 필요.
5. 저장된 장바구니에 카탈로그에서 사라진 상품 ID가 남아 있음(Mock 목록 변경 후 재배포) → 조회가 500이 되지 않고 그 라인을 응답·금액에서 제외한다 (Task 7). 스펙에 없는 결정이므로 검토 필요.

## 테스트 전략

| 대상 | 방식 |
| --- | --- |
| `Cart`, `CartRedisCodec`, `RedisCartRepository.expiresAt` | 순수 단위 테스트 |
| `RedisCartRepository` | `StringRedisTemplate`·`ValueOperations` Mockito mock. 호출된 명령과 인자만 검증 |
| `CartService` | 단위 테스트. 테스트 소스의 `InMemoryCartRepository`, `MutableClock`, `SequentialIdempotencyKeyGenerator` 사용 |
| `ProductController`, `CartController`, `HeaderCurrentCustomerResolver` | `@WebMvcTest` 슬라이스 |

기존 `@SpringBootTest`(`PaymentPlaygroundApplicationTests`)도 Redis 없이 통과한다. Lettuce는 첫 명령을 보낼 때 연결하므로 Redis 명령을 실행하지 않는 테스트에는 서버가 필요 없다. 실제 Redis 위의 테스트는 동시성 재현 단계의 계획에서 추가한다.

테스트 실행 명령의 형식: `./gradlew test --tests '<FQCN>'`. 고정 시각은 `2026-10-13T11:20:30Z`, 이때 저장한 장바구니의 `expiresAt`은 `2026-10-13T11:50:30Z`.

---

### Task 1: 400 도메인 예외

**Files:**
- Modify: `src/main/kotlin/io/github/dudco/paymentplayground/support/DomainExceptions.kt`
- Modify: `src/main/kotlin/io/github/dudco/paymentplayground/support/ApiExceptionHandler.kt` (`handleDomain`의 `when`)
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/support/ApiExceptionHandlerTest.kt`

**Interfaces:**
- Produces: `class InvalidRequestException(message: String) : DomainException(ApiExceptionHandler.VALIDATION_ERROR, message)` → `400`. Task 3, 7이 사용한다.

- [x] **Step 1: Write the failing test**

`ProbeController`에 `@GetMapping("/invalid") fun invalid(): String = throw InvalidRequestException("요청이 올바르지 않습니다.")`를 추가하고 테스트를 추가한다.

```kotlin
@Test
fun `InvalidRequestException은 400 VALIDATION_ERROR가 된다`() {
    mockMvc.perform(get("/probe/invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("요청이 올바르지 않습니다."))
}
```

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.support.ApiExceptionHandlerTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'InvalidRequestException'`)

- [x] **Step 3: Implement `InvalidRequestException` and map it to `HttpStatus.BAD_REQUEST` in `handleDomain`**

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.support.ApiExceptionHandlerTest'`
Expected: PASS (기존 6개 + 신규 1개)

- [x] **Step 5: Commit** — `d0bd821`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/support src/test/kotlin/io/github/dudco/paymentplayground/support
git commit -m "feat: 400 VALIDATION_ERROR 도메인 예외 추가"
```

---

### Task 2: Mock 상품 카탈로그와 조회 API

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/product/Product.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/product/MockProductCatalog.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/product/ProductController.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/product/ProductControllerTest.kt`

**Interfaces:**
- Produces (`Product.kt`):
  - `data class Product(val id: String, val name: String, val price: Long)`
  - `interface ProductCatalog { fun findAll(): List<Product>; fun findById(productId: String): Product? }`
  - `fun productNotFound(): NotFoundException` — code `PRODUCT_NOT_FOUND`, message `상품을 찾을 수 없습니다.` Task 7이 사용한다.
- Produces: `@Component class MockProductCatalog : ProductCatalog` — Global Constraints의 5개 상품을 그 순서로 가진다.
- Produces: `data class ProductResponse(val productId: String, val name: String, val price: Long)`, `GET /products`, `GET /products/{productId}`

- [x] **Step 1: Write the failing test**

```kotlin
@WebMvcTest(controllers = [ProductController::class])
@Import(MockProductCatalog::class)
class ProductControllerTest(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `상품 목록은 Mock 카탈로그 전체를 정의된 순서로 반환한다`() {
        mockMvc.perform(get("/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].productId").value("americano"))
            .andExpect(jsonPath("$[0].name").value("아메리카노"))
            .andExpect(jsonPath("$[0].price").value(4500))
            .andExpect(jsonPath("$[*].productId").value(contains("americano", "latte", "cake", "sandwich", "cookie")))
    }

    @Test
    fun `상품 단건을 조회한다`() {
        mockMvc.perform(get("/products/cake"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.productId").value("cake"))
            .andExpect(jsonPath("$.name").value("케이크"))
            .andExpect(jsonPath("$.price").value(6000))
    }

    @Test
    fun `없는 상품은 404 PRODUCT_NOT_FOUND다`() {
        mockMvc.perform(get("/products/unknown"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
    }
}
```

(`contains`는 `org.hamcrest.Matchers.contains`)

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.product.ProductControllerTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'ProductController'`)

- [x] **Step 3: Implement `Product.kt`, `MockProductCatalog`, `ProductController` per Interfaces**

`MockProductCatalog`는 목록을 `List<Product>`로, 단건 조회는 `associateBy { it.id }` 맵으로 한다.

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.product.ProductControllerTest'`
Expected: PASS (3개)

- [x] **Step 5: Commit** — `54a81a7`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/product src/test/kotlin/io/github/dudco/paymentplayground/product
git commit -m "feat: Mock 상품 카탈로그와 조회 API 추가"
```

---

### Task 3: 현재 고객 resolver

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/customer/CurrentCustomer.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/customer/HeaderCurrentCustomerResolver.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/customer/CustomerWebConfig.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/customer/HeaderCurrentCustomerResolverTest.kt`

**Interfaces:**
- Consumes: `InvalidRequestException` (Task 1)
- Produces:
  - `@Target(AnnotationTarget.VALUE_PARAMETER) @Retention(AnnotationRetention.RUNTIME) annotation class CurrentCustomer`
  - `@Component class HeaderCurrentCustomerResolver : HandlerMethodArgumentResolver` — `String` 타입이고 `@CurrentCustomer`가 붙은 파라미터에 `X-Customer-ID` 헤더 값을 앞뒤 공백을 제거해 넣는다. 없거나 공백이면 `InvalidRequestException("X-Customer-ID 헤더가 없거나 비어 있습니다.")`. `companion object { const val HEADER = "X-Customer-ID" }`
  - `@Configuration class CustomerWebConfig(private val resolver: HeaderCurrentCustomerResolver) : WebMvcConfigurer` — `addArgumentResolvers`에서 등록
  - Task 8의 `CartController`가 `@CurrentCustomer customerId: String`으로 사용한다. 인증 도입 시 이 패키지의 resolver만 교체한다.

- [x] **Step 1: Write the failing test**

```kotlin
@WebMvcTest(controllers = [HeaderCurrentCustomerResolverTest.ProbeController::class])
@Import(HeaderCurrentCustomerResolverTest.ProbeController::class, CustomerWebConfig::class, HeaderCurrentCustomerResolver::class)
class HeaderCurrentCustomerResolverTest(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `X-Customer-ID 헤더 값이 CurrentCustomer 파라미터로 들어온다`() {
        mockMvc.perform(get("/probe/customer").header("X-Customer-ID", "customer-001"))
            .andExpect(status().isOk())
            .andExpect(content().string("customer-001"))
    }

    @Test
    fun `헤더 값의 앞뒤 공백은 제거한다`() {
        mockMvc.perform(get("/probe/customer").header("X-Customer-ID", "  customer-001 "))
            .andExpect(content().string("customer-001"))
    }

    @Test
    fun `헤더가 없거나 공백이면 400 VALIDATION_ERROR다`() {
        mockMvc.perform(get("/probe/customer"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        mockMvc.perform(get("/probe/customer").header("X-Customer-ID", "   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @RestController
    class ProbeController {
        @GetMapping("/probe/customer")
        fun customer(@CurrentCustomer customerId: String): String = customerId
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.customer.HeaderCurrentCustomerResolverTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'CurrentCustomer'`)

- [x] **Step 3: Implement `CurrentCustomer`, `HeaderCurrentCustomerResolver`, `CustomerWebConfig` per Interfaces**

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.customer.HeaderCurrentCustomerResolverTest'`
Expected: PASS (3개)

- [x] **Step 5: Commit** — `a02b38f`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/customer src/test/kotlin/io/github/dudco/paymentplayground/customer
git commit -m "feat: X-Customer-ID 기반 현재 고객 resolver 추가"
```

---

### Task 4: Cart 도메인과 저장소 인터페이스

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/Cart.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartRepository.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartTest.kt`

**Interfaces:**
- Produces (`Cart.kt`):
  - `data class Cart(val customerId: String, val idempotencyKey: String, val lines: SortedMap<String, Int>, val createdAt: Instant, val updatedAt: Instant)` — `lines`는 상품 ID → 수량. `init`에서 `customerId`·`idempotencyKey`가 공백이 아님, `lines`가 비어 있지 않음, 모든 수량이 `MIN_QUANTITY..MAX_QUANTITY`임을 `require`로 검사한다.
  - `fun replaceLines(lines: Map<String, Int>, now: Instant): Cart` — `idempotencyKey`·`createdAt` 유지, `lines`·`updatedAt` 교체
  - `companion object { const val MIN_QUANTITY = 1; const val MAX_QUANTITY = 99; fun create(customerId: String, idempotencyKey: String, lines: Map<String, Int>, now: Instant): Cart }` — `createdAt = updatedAt = now`, `lines`는 `toSortedMap()`
- Produces (`CartRepository.kt`):
  - `data class StoredCart(val cart: Cart, val expiresAt: Instant?)`
  - `interface CartRepository { fun find(customerId: String): StoredCart?; fun save(cart: Cart): Instant; fun delete(customerId: String) }` — `save`는 새 만료 시각을 반환한다. Task 6이 구현, Task 7이 사용.

- [x] **Step 1: Write the failing test**

```kotlin
class CartTest {
    private val t0 = Instant.parse("2026-10-13T11:20:30Z")
    private val t1 = Instant.parse("2026-10-13T11:25:00Z")

    @Test
    fun `create는 라인을 상품 ID 오름차순으로 두고 생성·수정 시각을 같게 한다`() {
        val cart = Cart.create("customer-001", "key-1", mapOf("latte" to 1, "americano" to 2), t0)

        assertEquals(listOf("americano", "latte"), cart.lines.keys.toList())
        assertEquals(t0, cart.createdAt)
        assertEquals(t0, cart.updatedAt)
    }

    @Test
    fun `replaceLines는 멱등키와 createdAt을 유지하고 라인과 updatedAt을 바꾼다`() {
        val cart = Cart.create("customer-001", "key-1", mapOf("americano" to 2), t0)

        val replaced = cart.replaceLines(mapOf("cake" to 1), t1)

        assertEquals("key-1", replaced.idempotencyKey)
        assertEquals(t0, replaced.createdAt)
        assertEquals(t1, replaced.updatedAt)
        assertEquals(mapOf("cake" to 1), replaced.lines)
    }

    @Test
    fun `수량 경계값 1과 99는 허용하고 범위 밖과 빈 라인은 IllegalArgumentException이다`() {
        Cart.create("c", "k", mapOf("a" to 1, "b" to 99), t0)

        assertFailsWith<IllegalArgumentException> { Cart.create("c", "k", mapOf("a" to 0), t0) }
        assertFailsWith<IllegalArgumentException> { Cart.create("c", "k", mapOf("a" to 100), t0) }
        assertFailsWith<IllegalArgumentException> { Cart.create("c", "k", emptyMap(), t0) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'Cart'`)

- [x] **Step 3: Implement `Cart.kt` and `CartRepository.kt` per Interfaces**

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartTest'`
Expected: PASS (3개)

- [x] **Step 5: Commit** — `f68b560`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/cart src/test/kotlin/io/github/dudco/paymentplayground/cart
git commit -m "feat: Cart 도메인과 저장소 인터페이스 추가"
```

---

### Task 5: Cart JSON 코덱

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartRedisCodec.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartRedisCodecTest.kt`

**Interfaces:**
- Consumes: `Cart` (Task 4)
- Produces: `@Component class CartRedisCodec(private val jsonMapper: JsonMapper) { fun encode(cart: Cart): String; fun decode(json: String): Cart }` — `JsonMapper`는 `tools.jackson.databind.json.JsonMapper`(Spring Boot가 빈으로 제공). 저장 형식은 스펙 3장의 JSON(`lines`는 `{productId, quantity}` 배열)이며, 이를 위해 이 파일 안에 `private` 문서 클래스(`CartDocument`, `CartLineDocument`)를 두고 `Cart`와 변환한다. Task 6이 사용한다.

- [x] **Step 1: Write the failing test**

```kotlin
class CartRedisCodecTest {
    private val codec = CartRedisCodec(JsonMapper.builder().findAndAddModules().build())
    private val cart = Cart.create(
        "customer-001", "6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",
        mapOf("americano" to 2, "cake" to 1), Instant.parse("2026-10-13T11:20:30Z"),
    ).replaceLines(mapOf("americano" to 2, "cake" to 1), Instant.parse("2026-10-13T11:25:00Z"))

    @Test
    fun `encode한 JSON을 decode하면 같은 Cart가 된다`() {
        assertEquals(cart, codec.decode(codec.encode(cart)))
    }

    @Test
    fun `JSON은 스펙의 저장 형식을 따르고 상품명·가격을 담지 않는다`() {
        val json = codec.encode(cart)

        assertTrue(""""lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}]""" in json, json)
        assertTrue(""""createdAt":"2026-10-13T11:20:30Z"""" in json, json)
        assertTrue(""""updatedAt":"2026-10-13T11:25:00Z"""" in json, json)
        assertFalse("productName" in json || "price" in json || "Amount" in json, json)
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartRedisCodecTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'CartRedisCodec'`)

- [x] **Step 3: Implement `CartRedisCodec` per Interfaces**

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartRedisCodecTest'`
Expected: PASS (2개)

- [x] **Step 5: Commit** — `a065daf`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/cart/CartRedisCodec.kt src/test/kotlin/io/github/dudco/paymentplayground/cart/CartRedisCodecTest.kt
git commit -m "feat: 장바구니 Redis JSON 코덱 추가"
```

---

### Task 6: Redis 저장소와 TTL 설정

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartProperties.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/RedisCartRepository.kt`
- Modify: `src/main/kotlin/io/github/dudco/paymentplayground/PaymentPlaygroundApplication.kt` (`@ConfigurationPropertiesScan` 추가)
- Modify: `src/main/resources/application.properties`, `src/test/resources/application.properties` (`payment-playground.cart.ttl=30m` 추가)
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/cart/RedisCartRepositoryTest.kt`

**Interfaces:**
- Consumes: `Cart`, `StoredCart`, `CartRepository` (Task 4), `CartRedisCodec` (Task 5)
- Produces:
  - `@ConfigurationProperties("payment-playground.cart") data class CartProperties(val ttl: Duration = Duration.ofMinutes(30))`
  - `@Repository class RedisCartRepository(private val redis: StringRedisTemplate, private val codec: CartRedisCodec, private val properties: CartProperties, private val clock: Clock) : CartRepository`
    - `save`: `redis.opsForValue().set(key, codec.encode(cart), properties.ttl)` 한 번, 반환값 `now + ttl`
    - `find`: `opsForValue().get(key)`가 `null`이면 `null`, 아니면 `StoredCart(codec.decode(json), expiresAt(redis.getExpire(key, TimeUnit.SECONDS), now))`. TTL을 바꾸는 명령을 호출하지 않는다.
    - `delete`: `redis.delete(key)`
    - `companion object { fun key(customerId: String): String = "cart:$customerId"; fun expiresAt(ttlSeconds: Long?, now: Instant): Instant? }` — 양수면 `now + ttlSeconds초`, `null`·`0`·`-1`(TTL 없음)·`-2`(키 없음)이면 `null`
    - `now`는 `clock.instant()`를 초 단위로 자른 값(Redis TTL이 초 단위)

- [x] **Step 1: Write the failing test**

```kotlin
class RedisCartRepositoryTest {
    private val now = Instant.parse("2026-10-13T11:20:30Z")
    private val codec = CartRedisCodec(JsonMapper.builder().findAndAddModules().build())
    private val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)
    @Suppress("UNCHECKED_CAST")
    private val ops: ValueOperations<String, String> = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val repository = RedisCartRepository(redis, codec, CartProperties(Duration.ofMinutes(30)), Clock.fixed(now, ZoneOffset.UTC))
    private val cart = Cart.create("customer-001", "key-1", mapOf("americano" to 2), now)

    @BeforeEach
    fun setUp() {
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
    }

    @Test
    fun `저장은 cart 키에 JSON과 설정 TTL로 SET 한 번을 실행하고 만료 시각을 돌려준다`() {
        val expiresAt = repository.save(cart)

        Mockito.verify(ops).set("cart:customer-001", codec.encode(cart), Duration.ofMinutes(30))
        Mockito.verifyNoMoreInteractions(ops)
        assertEquals(Instant.parse("2026-10-13T11:50:30Z"), expiresAt)
    }

    @Test
    fun `조회는 GET과 TTL 확인만 하고 TTL을 연장하지 않는다`() {
        Mockito.`when`(ops.get("cart:customer-001")).thenReturn(codec.encode(cart))
        Mockito.`when`(redis.getExpire("cart:customer-001", TimeUnit.SECONDS)).thenReturn(1200L)

        val stored = repository.find("customer-001")

        assertEquals(StoredCart(cart, Instant.parse("2026-10-13T11:40:30Z")), stored)
        Mockito.verify(redis).opsForValue()
        Mockito.verify(ops).get("cart:customer-001")
        Mockito.verify(redis).getExpire("cart:customer-001", TimeUnit.SECONDS)
        Mockito.verifyNoMoreInteractions(redis, ops)
    }

    @Test
    fun `저장된 값이 없으면 null이다`() {
        Mockito.`when`(ops.get("cart:customer-001")).thenReturn(null)

        assertNull(repository.find("customer-001"))
    }

    @Test
    fun `삭제는 해당 고객의 키만 지운다`() {
        repository.delete("customer-001")

        Mockito.verify(redis).delete("cart:customer-001")
    }

    @Test
    fun `남은 TTL이 양수일 때만 만료 시각을 계산한다`() {
        assertEquals(now.plusSeconds(1200), RedisCartRepository.expiresAt(1200L, now))
        listOf(null, 0L, -1L, -2L).forEach { assertNull(RedisCartRepository.expiresAt(it, now), "ttl=$it") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.RedisCartRepositoryTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'RedisCartRepository'`)

- [x] **Step 3: Implement `CartProperties`, `RedisCartRepository`; add `@ConfigurationPropertiesScan` and `payment-playground.cart.ttl=30m` to both properties files**

- [ ] **Step 4: Run tests to verify they pass, including the existing context test without Redis** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.RedisCartRepositoryTest' --tests 'io.github.dudco.paymentplayground.PaymentPlaygroundApplicationTests'`
Expected: PASS (Redis 서버를 띄우지 않은 상태에서)

- [x] **Step 5: Commit** — `ac33a66`

```bash
git add src/main src/test/kotlin/io/github/dudco/paymentplayground/cart/RedisCartRepositoryTest.kt src/test/resources/application.properties
git commit -m "feat: Redis 장바구니 저장소와 TTL 설정 추가"
```

---

### Task 7: CartService

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/IdempotencyKeyGenerator.kt`
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartService.kt`
- Create (test fixtures): `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartTestFixtures.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartServiceTest.kt`

**Interfaces:**
- Consumes: `ProductCatalog`, `productNotFound()`, `MockProductCatalog` (Task 2), `InvalidRequestException` (Task 1), `Cart`, `StoredCart`, `CartRepository` (Task 4)
- Produces (`IdempotencyKeyGenerator.kt`): `fun interface IdempotencyKeyGenerator { fun generate(): String }`, `@Component class UuidIdempotencyKeyGenerator : IdempotencyKeyGenerator` (`UUID.randomUUID().toString()`)
- Produces (`CartService.kt`):
  - `data class CartLineCommand(val productId: String, val quantity: Int)`
  - `data class CartView(val customerId: String, val idempotencyKey: String?, val lines: List<CartLineView>, val totalAmount: Long, val expiresAt: Instant?)` — 빈 장바구니는 `CartView(customerId, null, emptyList(), 0, null)`
  - `data class CartLineView(val productId: String, val productName: String, val unitPrice: Long, val quantity: Int, val lineAmount: Long)`
  - `@Service class CartService(private val cartRepository: CartRepository, private val productCatalog: ProductCatalog, private val keyGenerator: IdempotencyKeyGenerator, private val clock: Clock)`
    - `fun get(customerId: String): CartView`
    - `fun save(customerId: String, lines: List<CartLineCommand>): CartView` — 순서: ① `productId` 중복이면 `InvalidRequestException("같은 상품이 두 번 이상 있습니다.")` ② 카탈로그에 없는 상품이 있으면 `productNotFound()` (아무것도 쓰지 않음) ③ 비어 있으면 `delete` 후 빈 응답 ④ 기존 장바구니가 있으면 `replaceLines`, 없으면 `Cart.create(..., keyGenerator.generate(), ...)` ⑤ `save`의 반환값을 `expiresAt`으로 응답
    - 응답 변환 시 카탈로그에 없는 라인은 제외한다(Review Focus 5)
- Produces (test fixtures, `CartTestFixtures.kt`) — Task 8도 사용:
  - `class MutableClock(var now: Instant) : Clock()` — `instant()`는 `now`를 반환, `zone`은 UTC
  - `class SequentialIdempotencyKeyGenerator : IdempotencyKeyGenerator` — `key-1`, `key-2`, … 순서로 발급
  - `class InMemoryCartRepository(private val clock: Clock) : CartRepository` — 메모리 맵. `save`는 `clock.instant() + 30분`을 반환하고 `find`에 같은 값을 돌려준다. 추가로 `fun contains(customerId: String): Boolean`, `fun clear()`
  - `@Component`를 붙이지 않는다(`@SpringBootTest` 스캔에서 `RedisCartRepository`와 충돌 방지)

- [x] **Step 1: Write the failing test**

```kotlin
class CartServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-13T11:20:30Z"))
    private val repository = InMemoryCartRepository(clock)
    private val service = CartService(repository, MockProductCatalog(), SequentialIdempotencyKeyGenerator(), clock)

    @Test
    fun `장바구니가 없으면 빈 응답이다`() {
        assertEquals(CartView("customer-001", null, emptyList(), 0, null), service.get("customer-001"))
    }

    @Test
    fun `첫 저장은 멱등키를 발급하고 카탈로그 가격으로 금액을 계산한다`() {
        val view = service.save("customer-001", listOf(CartLineCommand("cake", 1), CartLineCommand("americano", 2)))

        assertEquals("key-1", view.idempotencyKey)
        assertEquals(
            listOf(
                CartLineView("americano", "아메리카노", 4500, 2, 9000),
                CartLineView("cake", "케이크", 6000, 1, 6000),
            ),
            view.lines,
        )
        assertEquals(15000L, view.totalAmount)
        assertEquals(Instant.parse("2026-10-13T11:50:30Z"), view.expiresAt)
    }

    @Test
    fun `이어지는 저장은 멱등키와 createdAt을 유지하고 라인을 교체한다`() {
        service.save("customer-001", listOf(CartLineCommand("americano", 2), CartLineCommand("cake", 1)))
        clock.now = Instant.parse("2026-10-13T11:25:00Z")

        val view = service.save("customer-001", listOf(CartLineCommand("americano", 3), CartLineCommand("latte", 1)))

        assertEquals("key-1", view.idempotencyKey)
        assertEquals(listOf("americano" to 3, "latte" to 1), view.lines.map { it.productId to it.quantity })
        val stored = assertNotNull(repository.find("customer-001")).cart
        assertEquals(Instant.parse("2026-10-13T11:20:30Z"), stored.createdAt)
        assertEquals(Instant.parse("2026-10-13T11:25:00Z"), stored.updatedAt)
    }

    @Test
    fun `빈 라인 저장은 장바구니를 지우고 다시 담으면 새 멱등키가 발급된다`() {
        service.save("customer-001", listOf(CartLineCommand("cookie", 1)))

        assertEquals(CartView("customer-001", null, emptyList(), 0, null), service.save("customer-001", emptyList()))
        assertFalse(repository.contains("customer-001"))
        assertEquals("key-2", service.save("customer-001", listOf(CartLineCommand("cookie", 1))).idempotencyKey)
    }

    @Test
    fun `없는 상품이 섞이면 PRODUCT_NOT_FOUND이고 기존 장바구니가 그대로다`() {
        service.save("customer-001", listOf(CartLineCommand("americano", 1)))

        val error = assertFailsWith<NotFoundException> {
            service.save("customer-001", listOf(CartLineCommand("cake", 1), CartLineCommand("unknown", 1)))
        }

        assertEquals("PRODUCT_NOT_FOUND", error.code)
        assertEquals(mapOf("americano" to 1), assertNotNull(repository.find("customer-001")).cart.lines)
    }

    @Test
    fun `같은 상품이 두 번 있으면 InvalidRequestException이다`() {
        assertFailsWith<InvalidRequestException> {
            service.save("customer-001", listOf(CartLineCommand("cake", 1), CartLineCommand("cake", 2)))
        }
        assertFalse(repository.contains("customer-001"))
    }

    @Test
    fun `고객마다 장바구니와 멱등키가 분리된다`() {
        service.save("customer-001", listOf(CartLineCommand("americano", 1)))
        service.save("customer-002", listOf(CartLineCommand("cake", 2)))

        service.save("customer-001", emptyList())

        val other = service.get("customer-002")
        assertEquals("key-2", other.idempotencyKey)
        assertEquals(12000L, other.totalAmount)
    }

    @Test
    fun `카탈로그에서 사라진 상품의 라인은 응답과 금액에서 제외한다`() {
        repository.save(Cart.create("customer-001", "key-x", mapOf("americano" to 1, "discontinued" to 5), clock.now))

        val view = service.get("customer-001")

        assertEquals(listOf("americano"), view.lines.map { it.productId })
        assertEquals(4500L, view.totalAmount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartServiceTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'CartService'`)

- [x] **Step 3: Implement `IdempotencyKeyGenerator.kt`, `CartService.kt`, and the test fixtures per Interfaces**

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartServiceTest'`
Expected: PASS (8개)

- [x] **Step 5: Commit** — `9ad9ca8`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/cart src/test/kotlin/io/github/dudco/paymentplayground/cart
git commit -m "feat: 장바구니 저장·조회 서비스와 멱등키 발급 추가"
```

---

### Task 8: CartController

**Files:**
- Create: `src/main/kotlin/io/github/dudco/paymentplayground/cart/CartController.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/cart/CartControllerTest.kt`

**Interfaces:**
- Consumes: `CartService`, `CartView`, `CartLineCommand`, test fixtures (Task 7), `CurrentCustomer`, `CustomerWebConfig`, `HeaderCurrentCustomerResolver` (Task 3), `MockProductCatalog` (Task 2)
- Produces:
  - `@RestController @RequestMapping("/cart") class CartController(private val cartService: CartService)`
    - `@GetMapping fun get(@CurrentCustomer customerId: String): CartView`
    - `@PostMapping fun save(@CurrentCustomer customerId: String, @Valid @RequestBody request: SaveCartRequest): CartView` — 둘 다 `200 OK`
  - `data class SaveCartRequest(@field:Valid val lines: List<SaveCartLineRequest>)`
  - `data class SaveCartLineRequest(@field:NotBlank val productId: String, @field:Min(1) @field:Max(99) val quantity: Int)`
  - `lines` 누락·`null`·`null` 원소는 Jackson Kotlin 모듈이 역직렬화 단계에서 거절하고, 기존 `HttpMessageNotReadableException` 처리로 `400 VALIDATION_ERROR`가 된다.

- [x] **Step 1: Write the failing test**

```kotlin
@WebMvcTest(controllers = [CartController::class])
@Import(
    CartService::class, MockProductCatalog::class, CustomerWebConfig::class, HeaderCurrentCustomerResolver::class,
    CartControllerTest.Fixtures::class,
)
class CartControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: InMemoryCartRepository,
) {

    @BeforeEach
    fun setUp() = repository.clear()

    @TestConfiguration
    class Fixtures {
        @Bean fun clock(): Clock = MutableClock(Instant.parse("2026-10-13T11:20:30Z"))
        @Bean fun keyGenerator(): IdempotencyKeyGenerator = SequentialIdempotencyKeyGenerator()
        @Bean fun cartRepository(clock: Clock): InMemoryCartRepository = InMemoryCartRepository(clock)
    }

    @Test
    fun `장바구니가 없으면 빈 장바구니를 반환한다`() {
        get()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerId").value("customer-001"))
            .andExpect(jsonPath("$.idempotencyKey").value(nullValue()))
            .andExpect(jsonPath("$.lines.length()").value(0))
            .andExpect(jsonPath("$.totalAmount").value(0))
            .andExpect(jsonPath("$.expiresAt").value(nullValue()))
    }

    @Test
    fun `첫 저장은 멱등키와 계산된 금액, 정렬된 라인, 만료 시각을 반환한다`() {
        post("""{"lines":[{"productId":"cake","quantity":1},{"productId":"americano","quantity":2}]}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idempotencyKey").isNotEmpty())
            .andExpect(jsonPath("$.lines[0].productId").value("americano"))
            .andExpect(jsonPath("$.lines[0].productName").value("아메리카노"))
            .andExpect(jsonPath("$.lines[0].unitPrice").value(4500))
            .andExpect(jsonPath("$.lines[0].lineAmount").value(9000))
            .andExpect(jsonPath("$.lines[1].productId").value("cake"))
            .andExpect(jsonPath("$.totalAmount").value(15000))
            .andExpect(jsonPath("$.expiresAt").value("2026-10-13T11:50:30Z"))
    }

    @Test
    fun `잘못된 요청은 400 VALIDATION_ERROR다`() {
        listOf(
            """{"lines":[{"productId":"cake","quantity":0}]}""",
            """{"lines":[{"productId":"cake","quantity":100}]}""",
            """{"lines":[{"productId":"","quantity":1}]}""",
            """{"lines":[{"productId":"cake","quantity":1},{"productId":"cake","quantity":2}]}""",
            """{}""",
            """{"lines":null}""",          // Review Focus 1
            """{"lines":[null]}""",        // Review Focus 1
        ).forEach { body ->
            post(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        }
        mockMvc.perform(MockMvcRequestBuilders.get("/cart"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    private fun get(customerId: String = "customer-001"): ResultActions =
        mockMvc.perform(MockMvcRequestBuilders.get("/cart").header("X-Customer-ID", customerId))

    private fun post(body: String, customerId: String = "customer-001"): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/cart").header("X-Customer-ID", customerId)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
}
```

같은 클래스에 다음 테스트를 추가한다. 응답의 멱등키는 `JsonPath.read<String>(result.andReturn().response.contentAsString, "$.idempotencyKey")`로 읽는다. 키 생성기는 `SequentialIdempotencyKeyGenerator`라 첫 키는 `key-1`이지만, 슬라이스 컨텍스트가 테스트 사이에 재사용되어 순번이 이어지므로 키 값 자체가 아니라 같음·다름만 단언한다.

- `추가·수량 변경·제거를 전체 교체로 반영하고 멱등키는 유지한다`: `americano` 2·`cake` 1로 POST → `americano` 3·`latte` 1로 POST. 두 번째 응답의 키가 첫 응답과 같고, `$.lines[*].productId`가 `["americano","latte"]`, `$.lines[0].quantity`가 3, `$.totalAmount`가 `18500`.
- `같은 본문을 다시 보내도 멱등키와 라인이 그대로다` (Review Focus 2): 같은 본문으로 두 번 POST. 두 응답 모두 `200`, 키가 같고 `$.lines`가 같다.
- `빈 lines 저장은 장바구니를 비우고 다시 담으면 다른 멱등키가 발급된다`: POST → `{"lines":[]}` POST(응답 `$.idempotencyKey`가 null, `$.lines.length()` 0) → 다시 POST. 마지막 키가 첫 키와 다르다.
- `없는 상품이 섞이면 404 PRODUCT_NOT_FOUND이고 기존 장바구니가 그대로다`: `americano` 1로 POST → `cake`·`unknown`으로 POST(`404`, `$.code` `PRODUCT_NOT_FOUND`) → GET의 `$.lines[*].productId`가 `["americano"]`.
- `수량 경계값 1과 99는 허용된다` (Review Focus 3): `cake` 1, `latte` 99로 POST → `200`, `$.lines[0].quantity` 1, `$.lines[1].quantity` 99.
- `다른 고객의 장바구니와 멱등키에 영향을 주지 않는다`: `customer-001`과 `customer-002`가 각각 POST → 두 키가 다르고, `customer-001`이 `{"lines":[]}`로 비운 뒤에도 `customer-002`의 GET은 키와 라인이 그대로다.

- [ ] **Step 2: Run test to verify it fails** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartControllerTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'CartController'`)

- [x] **Step 3: Implement `CartController`, `SaveCartRequest`, `SaveCartLineRequest` per Interfaces**

`save`는 `request.lines.map { CartLineCommand(it.productId, it.quantity) }`를 `cartService.save`에 넘긴다.

- [ ] **Step 4: Run test to verify it passes** — ⏸ 미실행: 이 세션에서는 빌드할 수 없음(Java 11, Maven/Gradle 접근 불가). 로컬에서 실행 필요

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartControllerTest'`
Expected: PASS (9개)

- [x] **Step 5: Commit** — `1c8b796`

```bash
git add src/main/kotlin/io/github/dudco/paymentplayground/cart/CartController.kt src/test/kotlin/io/github/dudco/paymentplayground/cart/CartControllerTest.kt
git commit -m "feat: 장바구니 GET/POST API 추가"
```

---

### Task 9: 전체 검증

**Files:** 없음 (확인만)

- [ ] **Step 1: Redis 없이 전체 테스트 실행**

Redis 서버가 꺼진 상태에서 Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, 실패 0

- [ ] **Step 2: 애플리케이션을 띄워 실제 Redis 저장을 수동 확인**

```bash
redis-server --daemonize yes   # 또는 docker run -d -p 6379:6379 redis:7
./gradlew bootRun
curl -s localhost:8080/products
curl -s -X POST localhost:8080/cart -H 'X-Customer-ID: c1' -H 'Content-Type: application/json' \
  -d '{"lines":[{"productId":"americano","quantity":2}]}'
curl -s localhost:8080/cart -H 'X-Customer-ID: c1'
redis-cli GET cart:c1
redis-cli TTL cart:c1
```

Expected:
- `POST`와 `GET` 응답의 `idempotencyKey`가 같고 `totalAmount`가 `9000`
- `redis-cli GET` 결과 JSON에 `lines`·`idempotencyKey`가 있고 `productName`·`price`가 없다
- `redis-cli TTL`이 `1800` 이하의 양수이고, `GET /cart`를 다시 호출해도 늘어나지 않는다

- [ ] **Step 3: 스펙 상태 갱신 여부를 사람에게 확인**

구현이 스펙과 다르게 된 부분이 있으면 스펙을 먼저 고친다. Review Focus 4·5의 결정이 승인되었으면 장바구니 스펙 4장에 반영한다.
