# 상품·장바구니 API 구현 계획

> 작성일: 2026-10-13 · 기준 문서: [상품 조회 API 명세](../specs/product-api.md), [장바구니 API 명세](../specs/cart-api.md)

각 Task는 RED(실패하는 테스트) → GREEN(최소 구현) → REFACTOR 순서로 진행한다. 루트 패키지는 `io.github.dudco.paymentplayground`다. 스펙과 이 계획의 검토가 끝난 뒤 시작한다.

## 테스트 전략

| 대상 | 방식 | Redis 필요 |
| --- | --- | --- |
| `Cart` | 순수 단위 테스트 | 아니오 |
| `CartService` | 단위 테스트. `InMemoryCartRepository`(테스트 소스), `MockProductCatalog`, 고정 `Clock`, 고정 키 생성기 사용 | 아니오 |
| `HeaderCurrentCustomerResolver` | `@WebMvcTest` + 테스트용 프로브 Controller | 아니오 |
| `ProductController`, `CartController` | `@WebMvcTest` 슬라이스. Cart 저장소는 `InMemoryCartRepository`로 대체 | 아니오 |
| `CartRedisCodec` | 순수 단위 테스트. `Cart` ↔ JSON 왕복 | 아니오 |
| `RedisCartRepository` | 단위 테스트. `StringRedisTemplate`을 Mockito mock으로 두고 호출된 명령·인자만 검증 | 아니오 |

이 단계의 테스트는 Redis 없이 실행한다. 실제 Redis에 저장되는지는 확인하지 않고, 실제 Redis에서 깨지기 쉬운 두 부분은 Redis와 분리해 순수 단위 테스트로 검증한다.

- JSON 직렬화·역직렬화: `CartRedisCodec`
- TTL(남은 초)을 `expiresAt`으로 바꾸는 계산: `RedisCartRepository.expiresAt(ttlSeconds, now)` 같은 순수 함수

`@SpringBootTest`(기존 `contextLoads` 등)는 Redis 없이도 컨텍스트가 뜬다. Lettuce 연결 팩토리는 첫 명령을 보낼 때 연결하므로, Redis 명령을 실행하지 않는 테스트는 Redis 서버가 필요 없다.

실제 Redis 위의 테스트는 동시성 한계(동시 저장의 마지막 쓰기 승리, 첫 저장 경합)를 재현하는 단계에서 추가한다. mock으로는 경합을 재현할 수 없기 때문이다. 그때 Testcontainers 또는 로컬 Redis 중 하나를 그 단계의 계획에서 정한다.

## Task 1: 400 도메인 예외

- 테스트: `support/ApiExceptionHandlerTest`에 `InvalidRequestException`이 `400 VALIDATION_ERROR`가 되는 케이스 추가
- 구현: `support/DomainExceptions.kt`에 `InvalidRequestException(message)` 추가(코드 `VALIDATION_ERROR` 고정), `ApiExceptionHandler` 상태 매핑에 `BAD_REQUEST` 추가
- 용도: 공백 헤더, 중복 `productId`처럼 Bean Validation이 잡지 못하는 요청 오류

## Task 2: 상품 Mock 카탈로그와 조회 API

- 테스트: `product/ProductControllerTest` (`@WebMvcTest`, `MockProductCatalog`를 `@Import`)
  - 목록이 스펙 3장의 순서대로 반환된다
  - 단건 조회, 없는 ID는 `404 PRODUCT_NOT_FOUND`
- 구현: `product/Product.kt`(`Product`, `ProductCatalog`, `productNotFound()`), `product/MockProductCatalog.kt`, `product/ProductController.kt`

## Task 3: 현재 고객 resolver

- 테스트: `customer/HeaderCurrentCustomerResolverTest` (`@WebMvcTest` + 테스트 안의 프로브 Controller)
  - `X-Customer-ID` 값이 `@CurrentCustomer` 파라미터로 들어온다
  - 헤더가 없거나 공백이면 `400 VALIDATION_ERROR`
- 구현: `customer/CurrentCustomer.kt`(파라미터 애노테이션), `customer/HeaderCurrentCustomerResolver.kt`(`HandlerMethodArgumentResolver`), `customer/CustomerWebConfig.kt`(`WebMvcConfigurer`로 resolver 등록)
- 인증 도입 시 이 패키지의 resolver만 교체한다.

## Task 4: Cart 도메인

- 테스트: `cart/CartTest`
  - `replaceLines`는 장바구니 라인을 교체하고 멱등키·`createdAt`을 유지하며 `updatedAt`을 바꾼다
  - 수량 범위(1–99) 밖은 `IllegalArgumentException`
  - `lines`는 `productId` 오름차순
- 구현: `cart/Cart.kt` — `customerId`, `idempotencyKey`, `lines: Map<String, Int>`, `createdAt`, `updatedAt`, `expiresAt?`

## Task 5: CartService

- 테스트: `cart/CartServiceTest`
  - 장바구니가 없으면 빈 응답(`idempotencyKey` null)
  - 첫 저장은 키 생성기의 키를 쓰고, 이어지는 저장은 같은 키를 유지한다
  - 빈 목록 저장은 장바구니를 삭제하고, 다시 저장하면 새 키가 발급된다
  - 없는 상품이 섞이면 `PRODUCT_NOT_FOUND`이고 기존 장바구니가 그대로다
  - 중복 `productId`는 `InvalidRequestException`
  - 금액은 카탈로그 가격으로 계산된다, 고객 간 격리
- 구현: `cart/CartService.kt`, 응답 모델 `CartView`, `CartLineView`. 키 생성은 `IdempotencyKeyGenerator` 인터페이스(기본 구현: UUID)로 분리해 테스트에서 고정값을 넣는다. 시각은 `Clock`으로 얻는다.

## Task 6: Redis 저장소

- 테스트: `cart/CartRedisCodecTest` (순수 단위 테스트)
  - `Cart`를 JSON으로 바꾼 뒤 다시 읽으면 고객·멱등키·라인·`createdAt`·`updatedAt`이 같다
  - JSON에 상품명·가격 필드가 없다
- 테스트: `cart/RedisCartRepositoryTest` (Mockito로 `StringRedisTemplate`과 `ValueOperations`를 mock)
  - 저장은 `opsForValue().set("cart:{customerId}", <codec이 만든 JSON>, 설정 TTL)`을 한 번 호출한다
  - 빈 장바구니 저장과 `delete`는 `delete("cart:{customerId}")`를 호출하고 `set`은 호출하지 않는다
  - 조회는 `get` 결과를 codec으로 읽고, `getExpire(key, SECONDS)` 결과로 `expiresAt`을 채운다. `expire`·`set`은 호출하지 않는다(TTL 비연장)
  - `get`이 `null`이면 `null`을 반환한다
  - `expiresAt` 계산: 남은 초가 양수면 `now + 초`, `-1`(TTL 없음)·`-2`(키 없음)이면 `null` (고정 `Clock`)
- 구현: `cart/CartRepository.kt`(인터페이스), `cart/CartRedisCodec.kt`(Spring Boot의 `JsonMapper`로 직렬화), `cart/RedisCartRepository.kt`(`SET ... EX`), `cart/CartProperties.kt`
- 설정: `payment-playground.cart.ttl=30m`을 main·test `application.properties`에 추가. 애플리케이션 클래스에 `@ConfigurationPropertiesScan` 추가

## Task 7: CartController

- 테스트: `cart/CartControllerTest` (`@WebMvcTest`, `CartService`·`MockProductCatalog`·`InMemoryCartRepository`·고정 키 생성기·고정 `Clock`을 `@Import`)
  - 스펙 7장의 HTTP 수준 수용 조건(상태 코드, 오류 코드, 응답 본문)
- 구현: `cart/CartController.kt`, 요청 DTO `SaveCartRequest(lines: List<SaveCartLineRequest>)`

## 검증

자동 테스트는 Redis 없이 실행한다.

```bash
./gradlew test
```

실제 Redis 저장과 TTL은 `bootRun` 후 수동으로 확인한다.

```bash
redis-server --daemonize yes   # 또는 docker run -d -p 6379:6379 redis:7
./gradlew bootRun
curl -s localhost:8080/products
curl -s -X POST localhost:8080/cart -H 'X-Customer-ID: c1' -H 'Content-Type: application/json' \
  -d '{"lines":[{"productId":"americano","quantity":2}]}'
redis-cli GET cart:c1   # JSON에 lines·idempotencyKey가 있고 상품명·가격이 없다
redis-cli TTL cart:c1   # 1800 이하
```
