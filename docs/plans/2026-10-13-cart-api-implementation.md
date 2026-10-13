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
| `RedisCartRepository` | `@SpringBootTest`로 실제 Redis에 저장·TTL 확인 | 예 |

Redis가 필요한 테스트는 `RedisCartRepositoryTest` 하나로 모은다. 테스트 고객 ID는 `test-` 접두사를 쓰고 각 테스트 뒤에 키를 지운다.

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
  - `replaceItems`는 상품 목록을 교체하고 멱등키·`createdAt`을 유지하며 `updatedAt`을 바꾼다
  - 수량 범위(1–99) 밖은 `IllegalArgumentException`
  - `items`는 `productId` 오름차순
- 구현: `cart/Cart.kt` — `customerId`, `idempotencyKey`, `items: Map<String, Int>`, `createdAt`, `updatedAt`, `expiresAt?`

## Task 5: CartService

- 테스트: `cart/CartServiceTest`
  - 장바구니가 없으면 빈 응답(`idempotencyKey` null)
  - 첫 저장은 키 생성기의 키를 쓰고, 이어지는 저장은 같은 키를 유지한다
  - 빈 목록 저장은 장바구니를 삭제하고, 다시 저장하면 새 키가 발급된다
  - 없는 상품이 섞이면 `PRODUCT_NOT_FOUND`이고 기존 장바구니가 그대로다
  - 중복 `productId`는 `InvalidRequestException`
  - 금액은 카탈로그 가격으로 계산된다, 고객 간 격리
- 구현: `cart/CartService.kt`, 응답 모델 `CartView`, `CartItemView`. 키 생성은 `IdempotencyKeyGenerator` 인터페이스(기본 구현: UUID)로 분리해 테스트에서 고정값을 넣는다. 시각은 `Clock`으로 얻는다.

## Task 6: RedisCartRepository

- 테스트: `cart/RedisCartRepositoryTest` (`@SpringBootTest`, 실제 Redis)
  - 저장 후 조회하면 같은 고객·멱등키·수량·시각이다
  - 키 TTL이 설정값 이하·0 초과이고, 응답 `expiresAt`이 TTL과 일치한다(±2초)
  - 조회는 TTL을 바꾸지 않는다
  - 삭제는 해당 고객의 키만 지운다
- 구현: `cart/CartRepository.kt`(인터페이스), `cart/RedisCartRepository.kt`(Spring Boot의 `JsonMapper`로 직렬화, `SET ... EX`), `cart/CartProperties.kt`
- 설정: `payment-playground.cart.ttl=30m`을 main·test `application.properties`에 추가. 애플리케이션 클래스에 `@ConfigurationPropertiesScan` 추가

## Task 7: CartController

- 테스트: `cart/CartControllerTest` (`@WebMvcTest`, `CartService`·`MockProductCatalog`·`InMemoryCartRepository`·고정 키 생성기·고정 `Clock`을 `@Import`)
  - 스펙 7장의 HTTP 수준 수용 조건(상태 코드, 오류 코드, 응답 본문)
- 구현: `cart/CartController.kt`, 요청 DTO `SaveCartRequest(items: List<SaveCartItemRequest>)`

## 검증

```bash
redis-server --daemonize yes   # 또는 docker run -d -p 6379:6379 redis:7
./gradlew test
./gradlew bootRun
curl -s localhost:8080/products
curl -s -X POST localhost:8080/cart -H 'X-Customer-ID: c1' -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":"americano","quantity":2}]}'
redis-cli TTL cart:c1
```
