# 상품·장바구니 API 구현 계획

> 작성일: 2026-10-13 · 기준 문서: [상품 조회 API 명세](../specs/product-api.md), [장바구니 API 명세](../specs/cart-api.md)

각 Task는 RED(실패하는 테스트) → GREEN(최소 구현) → REFACTOR 순서로 진행한다. 루트 패키지는 `io.github.dudco.paymentplayground`다.

## 테스트 전략

| 대상 | 방식 | Redis 필요 |
| --- | --- | --- |
| `Cart` | 순수 단위 테스트 | 아니오 |
| `CartService` | 단위 테스트. `InMemoryCartRepository`(테스트 소스)와 `MockProductCatalog` 사용 | 아니오 |
| `ProductController`, `CartController` | `@WebMvcTest` 슬라이스. Cart는 `InMemoryCartRepository`로 대체 | 아니오 |
| `RedisCartRepository` | `@SpringBootTest`로 실제 Redis에 저장·TTL 확인 | 예 |

Redis가 필요한 테스트는 `RedisCartRepositoryTest` 하나로 모은다. 테스트 고객 ID는 `test-` 접두사를 쓰고 각 테스트 뒤에 키를 지운다.

## Task 1: 400 도메인 예외

- 테스트: `support/ApiExceptionHandlerTest`에 `InvalidRequestException`이 `400 VALIDATION_ERROR`가 되는 케이스 추가
- 구현: `support/DomainExceptions.kt`에 `InvalidRequestException(message)` 추가(코드 `VALIDATION_ERROR` 고정), `ApiExceptionHandler`의 상태 매핑에 `BAD_REQUEST` 추가
- 용도: 공백 `X-Customer-Id`처럼 Bean Validation이 잡지 못하는 요청 오류

## Task 2: 상품 Mock 카탈로그와 조회 API

- 테스트: `product/ProductControllerTest` (`@WebMvcTest`, `MockProductCatalog`를 `@Import`)
  - 목록이 스펙 3장의 순서대로 반환된다
  - 단건 조회, 없는 ID는 `404 PRODUCT_NOT_FOUND`
- 구현: `product/Product.kt`(`Product`, `ProductCatalog`), `product/MockProductCatalog.kt`, `product/ProductController.kt`

## Task 3: Cart 도메인

- 테스트: `cart/CartTest`
  - 담기는 수량을 더하고, 합이 99를 넘으면 `CART_ITEM_QUANTITY_EXCEEDED`이며 상태가 바뀌지 않는다
  - 수량 변경은 교체, 없는 상품의 변경·빼기는 `CART_ITEM_NOT_FOUND`
  - `items`는 `productId` 오름차순
- 구현: `cart/Cart.kt`. 수량 범위(1–99) 위반은 `IllegalArgumentException`(Controller 검증을 통과한 뒤에는 발생하지 않아야 함)

## Task 4: CartService

- 테스트: `cart/CartServiceTest` (`InMemoryCartRepository` + `MockProductCatalog`)
  - 빈 장바구니 조회, 담기 후 금액 계산, 없는 상품 `PRODUCT_NOT_FOUND`
  - 마지막 상품을 빼면 저장소에서 삭제된다
  - 고객 간 격리
- 구현: `cart/CartService.kt`, 응답 모델 `CartView`, `CartItemView`

## Task 5: RedisCartRepository

- 테스트: `cart/RedisCartRepositoryTest` (`@SpringBootTest`, 실제 Redis)
  - 저장 후 조회하면 같은 수량이고, 키 TTL이 설정값 이하·0 초과다
  - 빈 Cart 저장은 키를 지운다
  - 응답 `expiresAt`이 TTL과 일치한다(±2초)
- 구현: `cart/CartRepository.kt`(인터페이스), `cart/RedisCartRepository.kt`, `cart/CartProperties.kt`
- 설정: `payment-playground.cart.ttl=7d`를 main·test `application.properties`에 추가. 애플리케이션 클래스에 `@ConfigurationPropertiesScan` 추가

## Task 6: CartController

- 테스트: `cart/CartControllerTest` (`@WebMvcTest`, `CartService`·`MockProductCatalog`·`InMemoryCartRepository`를 `@Import`)
  - 스펙 7장의 HTTP 수준 수용 조건(상태 코드, 오류 코드, 응답 본문, 헤더 검증)
- 구현: `cart/CartController.kt`, 요청 DTO `AddCartItemRequest`, `ChangeCartItemQuantityRequest`

## 검증

```bash
redis-server --daemonize yes   # 또는 docker run -d -p 6379:6379 redis:7
./gradlew test
./gradlew bootRun
curl -s localhost:8080/products
curl -s -X POST localhost:8080/cart/items -H 'X-Customer-Id: c1' -H 'Content-Type: application/json' -d '{"productId":"americano","quantity":2}'
redis-cli TTL cart:c1
```
