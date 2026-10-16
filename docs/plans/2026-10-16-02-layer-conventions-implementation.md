# 계층·파일 컨벤션 리팩터링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품·장바구니·주문 코드를 [코드 컨벤션](../conventions.md)에 맞춘다. HTTP 계약(경로, 헤더, JSON 필드, 상태 코드, 오류 코드)과 동작은 바꾸지 않는다.

**Architecture:** 기능마다 `XxxApi.kt`(Request·Response), `XxxService.kt`(서비스와 Command·Result), 도메인 모델, 저장소 인터페이스·구현으로 나눈다. 서비스는 엔티티·도메인 모델 대신 `Result`를 반환하고, 컨트롤러가 `XxxResponse.from(result)`로 응답을 만든다. 상품 조회 창구는 `ProductRepository`/`FixedProductRepository`로 이름을 바꾸고 `ProductService`를 둔다. 장바구니의 Redis 저장 형식(코덱·문서 클래스)은 `RedisCartRepository` 안의 private 구현으로 합친다. 멱등키 생성기는 `support`로 옮긴다.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.1.1(WebMVC, Data JPA, Data Redis), Jackson 3(`tools.jackson`), JUnit 5, Mockito

**Spec:** [코드 컨벤션](../conventions.md). 동작 기준은 [상품 조회](../specs/product-api.md)·[장바구니](../specs/cart-api.md)·[주문](../specs/order-api.md) API 명세.

## Global Constraints

- 동작을 바꾸지 않는다. 경로, 헤더, 요청·응답 JSON 필드 이름과 값, 상태 코드, 오류 코드·메시지, Redis 키·값 형식, DB 스키마가 그대로여야 한다.
- 컨트롤러 테스트(`ProductControllerTest`, `CartControllerTest`, `OrderControllerTest`)의 단언은 고치지 않는다. 바뀌어도 되는 것은 import, `@Import` 목록, 테스트 설정 빈의 타입·이름뿐이다. 이 테스트들이 HTTP 계약이 그대로라는 증거다.
- 새 의존성을 추가하지 않는다. 파일 이동·이름 변경은 `git mv`로 해서 이력을 남긴다.
- 이름 규칙(컨벤션 2.4): `XxxRequest`, `XxxResponse`, `XxxCommand`, `XxxResult`. `View`·`Dto`·`Info` 접미사를 남기지 않는다.
- `Response` 클래스의 프로퍼티 선언 순서는 지금의 `View`/`Response`와 같게 둔다(JSON 필드 순서 유지).
- 서비스는 엔티티·도메인 모델(`Product`, `Cart`, `Order`, `OrderLine`)을 반환하지 않는다. 컨트롤러는 그 타입을 import하지 않는다.
- 기존 Kotlin 소스는 탭으로 들여쓴다. 주석(KDoc)은 한국어로, 옮기는 코드의 기존 주석은 유지한다.
- 테스트는 Redis 서버 없이 통과해야 한다.

## Review Focus

동작을 바꾸지 않는 리팩터링에서 사용자가 만날 가능성이 높은 깨짐과 그 기대 동작이다.

1. 리팩터링 전에 Redis에 저장된 장바구니 JSON → 배포 후에도 그대로 읽힌다. 코덱을 저장소 안으로 옮기면서 문서 클래스 필드 이름이 바뀌면 기존 장바구니가 모두 깨진다 (Task 3, 스펙 예시 JSON 리터럴을 읽는 테스트).
2. 빈 장바구니 응답의 `idempotencyKey`·`expiresAt` → 필드가 사라지지 않고 `null`로 나온다 (기존 `CartControllerTest`가 `nullValue()`로 단언, 단언 수정 금지로 보호).
3. 주문 응답의 `status` → 계속 문자열 `"CREATED"`다. Response에서 enum을 문자열로 바꾸며 형식이 달라지면 안 된다 (기존 `OrderControllerTest`, Task 4).
4. 앱 기동 시 `ProductRepository` 구현이 하나뿐이다. 주문 테스트 컨텍스트에서는 `@Primary` 테스트 구현이 이긴다 (기존 `PaymentPlaygroundApplicationTests.contextLoads`와 주문 테스트, Task 1).
5. 시각 필드(`expiresAt`, `createdAt`)의 JSON 형식 → ISO-8601 UTC 문자열 그대로다 (기존 컨트롤러 테스트가 `"2026-10-13T11:50:30Z"`, `"2026-10-14T03:10:00Z"`로 단언).

## 테스트 실행

- 형식: `./gradlew test --tests '<FQCN>'`, 전체는 `./gradlew test`.
- 시작 시점 기준 전체 76개 통과. 각 Task의 Step 4 기대 개수는 그 Task가 더하고 빼는 테스트를 반영한다.

---

### Task 1: 상품 — 저장소 이름 변경, 서비스·HTTP 계약 분리

**Files:**
- Rename: `src/main/kotlin/io/github/dudco/paymentplayground/product/MockProductCatalog.kt` → `product/FixedProductRepository.kt`
- Create: `product/ProductRepository.kt`, `product/ProductErrors.kt`, `product/ProductService.kt`, `product/ProductApi.kt`
- Modify: `product/Product.kt`(인터페이스·오류 함수 제거), `product/ProductController.kt`, `cart/CartService.kt`, `order/OrderService.kt`(의존 타입 이름만)
- Modify (test): `product/ProductControllerTest.kt`(`@Import`만), `cart/CartServiceTest.kt`, `cart/CartControllerTest.kt`, `order/OrderTestFixtures.kt`, `order/OrderServiceTest.kt`, `order/OrderControllerTest.kt`
- Test: `src/test/kotlin/io/github/dudco/paymentplayground/product/ProductServiceTest.kt`

**Interfaces:**
- Produces:
  - `interface ProductRepository { fun findAll(): List<Product>; fun findById(productId: String): Product? }` — `Product.kt`의 `ProductCatalog`를 옮기고 이름만 바꾼다(KDoc 유지).
  - `@Repository class FixedProductRepository : ProductRepository` — 지금 `MockProductCatalog`의 5개 상품과 순서 그대로.
  - `ProductErrors.kt`: `fun productNotFound(): NotFoundException` — 코드·메시지 그대로(`PRODUCT_NOT_FOUND`, `상품을 찾을 수 없습니다.`).
  - `ProductService.kt`: `data class ProductResult(val productId: String, val name: String, val price: Long)`, `@Service class ProductService(private val productRepository: ProductRepository) { fun findAll(): List<ProductResult>; fun get(productId: String): ProductResult }` — `get`은 없으면 `productNotFound()`.
  - `ProductApi.kt`: `data class ProductResponse(val productId: String, val name: String, val price: Long)`와 `companion object { fun from(result: ProductResult): ProductResponse }`.
  - `ProductController(private val productService: ProductService)` — 경로·반환 JSON 그대로.
  - `CartService`·`OrderService`의 생성자 파라미터는 `productRepository: ProductRepository`로 이름과 타입을 바꾼다(동작 그대로).
  - 테스트 지원: `OrderTestFixtures.kt`의 `MutableProductCatalog` → `class MutableProductRepository : ProductRepository`(`reset()`은 `FixedProductRepository().findAll()`로 되돌림), 빈 메서드 `@Bean @Primary fun mutableProductRepository(): MutableProductRepository`.

- [x] **Step 1: Write the failing test**

```kotlin
class ProductServiceTest {
	private val service = ProductService(FixedProductRepository())

	@Test
	fun `전체 상품을 저장소 순서대로 결과로 돌려준다`() {
		val results = service.findAll()

		assertEquals(ProductResult("americano", "아메리카노", 4500), results.first())
		assertEquals(listOf("americano", "latte", "cake", "sandwich", "cookie"), results.map { it.productId })
	}

	@Test
	fun `상품 하나를 결과로 돌려주고 없는 상품은 PRODUCT_NOT_FOUND다`() {
		assertEquals(ProductResult("cake", "케이크", 6000), service.get("cake"))
		assertEquals("PRODUCT_NOT_FOUND", assertFailsWith<NotFoundException> { service.get("unknown") }.code)
	}
}
```

- [x] **Step 2: Run test to verify it fails** — RED 확인: 컴파일 오류 `Unresolved reference 'ProductService'` (2026-10-16)

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.product.ProductServiceTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'ProductService'`)

- [x] **Step 3: Implement per Interfaces, then update every reference**

`git mv`로 `MockProductCatalog.kt`를 옮긴 뒤 이름을 바꾼다. `ProductControllerTest`는 `@Import(ProductService::class, FixedProductRepository::class)`로 바꾸고 단언은 그대로 둔다. 장바구니·주문 테스트는 `MockProductCatalog`/`MutableProductCatalog`를 새 이름으로 바꾼다. 끝나면 `grep -rn "Catalog" src`가 아무것도 찾지 않아야 한다.

- [x] **Step 4: Run all tests** — 통과: 전체 78/78 (2026-10-16)

Run: `./gradlew test`
Expected: PASS, 78개(76 + `ProductServiceTest` 2)

- [x] **Step 5: Commit** — `c83fdd5`

```bash
git add -A src
git commit -m "refactor: 상품 조회 창구를 ProductRepository로 바꾸고 ProductService·ProductApi 분리"
```

---

### Task 2: 장바구니 — 서비스 결과와 HTTP 계약 분리, 멱등키 생성기를 support로 이동

**Files:**
- Create: `cart/CartApi.kt`
- Modify: `cart/CartService.kt`, `cart/CartController.kt`
- Rename: `cart/IdempotencyKeyGenerator.kt` → `support/IdempotencyKeyGenerator.kt`(패키지 `support`)
- Create (test): `src/test/kotlin/io/github/dudco/paymentplayground/support/SequentialIdempotencyKeyGenerator.kt`
- Modify (test): `cart/CartTestFixtures.kt`(`SequentialIdempotencyKeyGenerator` 제거), `cart/CartServiceTest.kt`, `cart/CartControllerTest.kt`(import·`Fixtures` 타입만)

**Interfaces:**
- Consumes: `ProductRepository` (Task 1)
- Produces:
  - `support/IdempotencyKeyGenerator.kt`: `fun interface IdempotencyKeyGenerator { fun generate(): String }`, `@Component class UuidIdempotencyKeyGenerator` — 패키지만 바뀐다.
  - 테스트 `support/SequentialIdempotencyKeyGenerator.kt`: `class SequentialIdempotencyKeyGenerator : IdempotencyKeyGenerator`(`key-1`, `key-2`, …) — `CartTestFixtures.kt`에서 옮긴다.
  - `CartService.kt`: `CartLineCommand` 그대로. `CartView` → `data class CartResult(val customerId: String, val idempotencyKey: String?, val lines: List<CartLineResult>, val totalAmount: Long, val expiresAt: Instant?)`, `CartLineView` → `data class CartLineResult(val productId: String, val productName: String, val unitPrice: Long, val quantity: Int, val lineAmount: Long)`. `get`·`save`가 `CartResult`를 반환한다.
  - `CartApi.kt`: `SaveCartRequest`, `SaveCartLineRequest`를 `CartController.kt`에서 그대로 옮긴다(KDoc·검증 애노테이션 포함). `data class CartResponse(...)`, `data class CartLineResponse(...)` — 필드는 `CartResult`/`CartLineResult`와 같은 이름·순서. `companion object { fun from(result: CartResult): CartResponse }`.
  - `CartController`: `get`·`save`가 `CartResponse`를 반환한다. `null` 원소 → `InvalidRequestException` 처리는 그대로 컨트롤러에 둔다.

- [ ] **Step 1: Update the service test to the new types (failing)**

`CartServiceTest`의 `CartView`·`CartLineView`를 `CartResult`·`CartLineResult`로 바꾸고, `SequentialIdempotencyKeyGenerator` import를 `io.github.dudco.paymentplayground.support`로 바꾼다. 단언 값은 그대로 둔다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.CartServiceTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'CartResult'`)

- [ ] **Step 3: Implement per Interfaces**

생성기 파일은 `git mv` 후 패키지 선언을 바꾸고, 쓰는 곳(`CartService`, 테스트)의 import를 고친다.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: PASS, 78개. `CartControllerTest`의 단언은 수정하지 않았다.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "refactor: 장바구니 서비스 결과와 HTTP 계약을 분리하고 멱등키 생성기를 support로 이동"
```

---

### Task 3: 장바구니 — Redis 저장 형식을 저장소 구현 안으로 합침

**Files:**
- Modify: `cart/RedisCartRepository.kt`
- Delete: `cart/CartRedisCodec.kt`, 테스트 `cart/CartRedisCodecTest.kt`
- Modify (test): `cart/RedisCartRepositoryTest.kt`

**Interfaces:**
- Produces: `@Repository class RedisCartRepository(private val redis: StringRedisTemplate, private val jsonMapper: JsonMapper, private val properties: CartProperties, private val clock: Clock) : CartRepository` — `CartRedisCodec`의 `CartDocument`·`CartLineDocument`를 이 파일의 `private` 타입으로, `encode`·`decode`를 `private fun`으로 옮긴다. 필드 이름(`customerId`, `idempotencyKey`, `lines`, `productId`, `quantity`, `createdAt`, `updatedAt`)과 JSON 형식은 그대로다. `companion object`의 `key`·`expiresAt`도 그대로다. `CartRepository` 인터페이스와 `StoredCart`는 바뀌지 않는다.

- [ ] **Step 1: Update the repository test (failing)**

생성자에 `CartRedisCodec` 대신 `JsonMapper.builder().findAndAddModules().build()`를 넘긴다. `codec.encode(cart)`를 기대값으로 쓰던 저장 테스트는 `ArgumentCaptor<String>`로 `set`에 넘어간 값을 잡아 키(`cart:customer-001`)와 TTL(`Duration.ofMinutes(30)`)을 단언하도록 바꾼다. 조회 테스트는 아래 리터럴을 `ops.get`의 반환값으로 쓴다. 그리고 `CartRedisCodecTest`의 두 테스트를 옮겨 다음 세 개를 추가한다.

```kotlin
private val storedJson = """{"customerId":"customer-001","idempotencyKey":"6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",""" +
	""""lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}],""" +
	""""createdAt":"2026-10-13T11:20:30Z","updatedAt":"2026-10-13T11:25:00Z"}"""

@Test
fun `저장한 JSON을 다시 읽으면 같은 Cart다`() { /* save → set 값 캡처 → ops.get이 그 값을 반환 → find(...).cart == cart */ }

@Test
fun `저장 JSON은 스펙 형식을 따르고 상품명·가격을 담지 않는다`() {
	// 캡처한 값에 대해 (CartRedisCodecTest의 단언을 그대로)
	// ""lines":[{"productId":"americano","quantity":2}]" 포함, ""createdAt":"2026-10-13T11:20:30Z"" 포함,
	// "productName"·"price"·"Amount" 미포함
}

@Test
fun `리팩터링 전 형식으로 저장된 JSON을 읽는다`() { // Review Focus 1
	// ops.get("cart:customer-001")이 storedJson을 반환 →
	// find(...).cart == Cart(customer-001, 6f1c2a52-…, sortedMapOf("americano" to 2, "cake" to 1),
	//                       2026-10-13T11:20:30Z, 2026-10-13T11:25:00Z)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.cart.RedisCartRepositoryTest'`
Expected: FAIL (컴파일 오류: 생성자 인자 타입 불일치 `JsonMapper` / `CartRedisCodec`)

- [ ] **Step 3: Move the codec into `RedisCartRepository` and delete `CartRedisCodec.kt`, `CartRedisCodecTest.kt`**

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: PASS, 79개(78 − 코덱 테스트 2 + 저장소 테스트 3). `grep -rn "CartRedisCodec" src`가 아무것도 찾지 않는다.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "refactor: 장바구니 Redis 저장 형식을 RedisCartRepository 안으로 합침"
```

---

### Task 4: 주문 — 서비스 결과와 HTTP 계약 분리

**Files:**
- Create: `order/OrderApi.kt`
- Modify: `order/OrderService.kt`, `order/OrderController.kt`
- Modify (test): `order/OrderServiceTest.kt`(타입 이름만)

**Interfaces:**
- Consumes: `ProductRepository` (Task 1)
- Produces:
  - `OrderService.kt`: `OrderLineView` → `data class OrderLineResult(val productId: String, val productName: String, val unitPrice: Long, val quantity: Int, val lineAmount: Long)`, `OrderView` → `data class OrderResult(val orderId: String, val customerId: String, val idempotencyKey: String, val status: OrderStatus, val lines: List<OrderLineResult>, val totalAmount: Long, val createdAt: Instant)`, `OrderPlacement` → `data class PlaceOrderResult(val order: OrderResult, val created: Boolean)`. `private fun Order.toView()` → `toResult()`. `place`는 `PlaceOrderResult`, `get`은 `OrderResult`를 반환한다. 트랜잭션·장바구니 정리 로직은 그대로다.
  - `OrderApi.kt`: `data class OrderLineResponse(val productId: String, val productName: String, val unitPrice: Long, val quantity: Int, val lineAmount: Long)`, `data class OrderResponse(val orderId: String, val customerId: String, val idempotencyKey: String, val status: String, val lines: List<OrderLineResponse>, val totalAmount: Long, val createdAt: Instant)` — `status`는 `result.status.name`(Review Focus 3, 컨트롤러가 도메인 enum을 알지 않게 한다). `companion object { fun from(result: OrderResult): OrderResponse }`.
  - `OrderController`: `place`는 `ResponseEntity<OrderResponse>`(201 + `Location: /orders/{orderId}` / 200 판단은 그대로), `get`은 `OrderResponse`.

- [ ] **Step 1: Update the service test to the new types (failing)**

`OrderServiceTest`의 `OrderLineView`를 `OrderLineResult`로 바꾼다. 단언 값은 그대로 둔다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.dudco.paymentplayground.order.OrderServiceTest'`
Expected: FAIL (컴파일 오류 `Unresolved reference 'OrderLineResult'`)

- [ ] **Step 3: Implement per Interfaces**

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: PASS, 79개. `OrderControllerTest`의 단언(`$.status` `"CREATED"`, `$.createdAt`, `Location`)은 수정하지 않았다.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "refactor: 주문 서비스 결과와 HTTP 계약을 분리"
```

---

### Task 5: 전체 검증과 문서 정리

**Files:**
- Modify: `docs/specs/product-api.md`(2장 패키지 목록과 본문의 `ProductCatalog`·`MockProductCatalog`), `docs/specs/cart-api.md`·`docs/specs/order-api.md`(`ProductCatalog` 언급), `docs/data-model.md`(`MockProductCatalog` 언급), `docs/adr/0005-product-api-mock-catalog.md`(상단 관계 줄에 이름 변경 한 줄만 추가, 본문은 고치지 않음), `docs/conventions.md`(상태), `docs/README.md`(계획 목록)

- [ ] **Step 1: 계약이 그대로인지 확인**

Run: `git diff main -- src/test/kotlin/io/github/dudco/paymentplayground/product/ProductControllerTest.kt src/test/kotlin/io/github/dudco/paymentplayground/cart/CartControllerTest.kt src/test/kotlin/io/github/dudco/paymentplayground/order/OrderControllerTest.kt`
Expected: import, `@Import`, 테스트 설정 빈의 타입·이름 변경만 있다. `andExpect` 줄은 하나도 바뀌지 않았다.

- [ ] **Step 2: 컨벤션 위반이 남지 않았는지 확인**

Run: `grep -rnE "View\b|Catalog|CartRedisCodec|OrderPlacement" src` 와 `grep -nwE "Product|Cart|Order|OrderLine|OrderStatus" src/main/kotlin/io/github/dudco/paymentplayground/*/*Controller.kt`
Expected: 둘 다 결과 없음. 두 번째는 같은 패키지라 import 없이 쓰는 도메인 타입까지 잡기 위해 단어 단위로 찾는다(주석에 걸리면 주석 표현을 고친다).

- [ ] **Step 3: Redis 없이 전체 테스트**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, 79개 통과, 실패 0

- [ ] **Step 4: 문서 갱신**

- 스펙·데이터 모델의 `ProductCatalog` → `ProductRepository`, `MockProductCatalog` → `FixedProductRepository`. 상품 스펙 2장은 Task 1의 파일 구성(`ProductRepository`, `FixedProductRepository`, `ProductService`, `ProductApi`, `ProductErrors`)으로 바꾼다.
- ADR-0005 상단에 "관계: 코드 이름은 [코드 컨벤션](../conventions.md)에 따라 `ProductRepository`/`FixedProductRepository`로 바뀌었다." 한 줄을 추가한다. 결정 본문은 고치지 않는다(문서 가이드의 ADR 규칙).
- `docs/conventions.md` 상태를 `승인됨`으로 바꾼다(사람 승인 후). 문서 가이드의 계획 목록에서 이 계획에 `(완료)`를 붙인다.

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs: 컨벤션 리팩터링에 맞춰 상품 저장소 이름과 스펙 정리"
```
