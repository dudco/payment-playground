# 장바구니 API 명세

> 상태: 검토 중 · 작성일: 2026-10-13 · 최종 갱신일: 2026-10-13

> [PRD](../prd.md), [데이터 모델](../data-model.md), [ADR-0003](../adr/0003-cart-in-redis-with-ttl.md), [ADR-0005](../adr/0005-product-api-mock-catalog.md), [ADR-0006](../adr/0006-cart-per-customer.md)을 바탕으로 한 첫 번째 기능 명세다. 트래픽이 거의 없는 환경을 전제로 하며, 동시성 제어는 넣지 않는다([PRD 8. 알려진 한계](../prd.md#8-알려진-한계)).

## 1. 범위

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/cart` | 장바구니 조회 |
| `POST` | `/cart/items` | 상품 담기 (이미 있으면 수량을 더함) |
| `PATCH` | `/cart/items/{productId}` | 담긴 상품의 수량 변경 |
| `DELETE` | `/cart/items/{productId}` | 담긴 상품 빼기 |
| `DELETE` | `/cart` | 장바구니 비우기 |

주문 생성, 장바구니 병합, 비회원 장바구니, 인증은 구현하지 않는다.

## 2. 패키지와 책임

```text
io.github.dudco.paymentplayground.cart
├── Cart                 고객 ID와 상품별 수량. 담기·수량 변경·빼기 규칙을 가진다.
├── CartRepository       저장소 인터페이스
├── RedisCartRepository  Redis Hash + TTL 구현
├── CartProperties       payment-playground.cart.* 설정
├── CartService          상품 존재 확인, Cart 변경, 응답용 금액 계산
└── CartController       HTTP 요청 검증과 응답 변환
```

- Controller는 헤더·본문 검증과 응답 변환만 한다.
- `Cart`는 수량 규칙을 지키고, 위반하면 도메인 예외를 던진다.
- Service는 `ProductCatalog`로 상품을 확인하고, 응답에 상품명·단가·금액을 채운다.
- Repository는 저장과 TTL만 다룬다. 금액 계산을 하지 않는다.

## 3. 저장 모델

- Redis 키: `cart:{customerId}`, 타입: Hash. 필드는 상품 ID, 값은 수량(10진수 문자열).
- TTL: `payment-playground.cart.ttl`(기본 `7d`). 쓰기마다 다시 설정한다.
- 저장은 "키 삭제 → 전체 필드 쓰기 → TTL 설정" 순서다. 상품이 하나도 없으면 키를 삭제만 한다.
- 상품명과 가격은 저장하지 않는다.

## 4. 공통 규칙

- 모든 요청에 `X-Customer-Id` 헤더가 필요하다. 없거나 공백이면 `400 VALIDATION_ERROR`.
- 수량은 상품당 1 이상 99 이하다.
- 상품명·단가는 요청 시점의 `ProductCatalog` 값이다. `lineAmount = unitPrice × quantity`, `totalAmount = Σ lineAmount`.
- 응답의 `items`는 `productId` 오름차순이다.
- `expiresAt`은 Redis TTL로 계산한 만료 시각이다. 장바구니가 비어 있으면 `null`이다.

### 장바구니 응답 본문

`GET /cart`, `POST /cart/items`, `PATCH /cart/items/{productId}`, `DELETE /cart/items/{productId}`는 모두 `200 OK`와 다음 본문을 반환한다.

```json
{
  "customerId": "customer-001",
  "items": [
    { "productId": "americano", "productName": "아메리카노", "unitPrice": 4500, "quantity": 2, "lineAmount": 9000 },
    { "productId": "cake", "productName": "케이크", "unitPrice": 6000, "quantity": 1, "lineAmount": 6000 }
  ],
  "totalAmount": 15000,
  "expiresAt": "2026-10-20T11:20:30Z"
}
```

## 5. API 계약

### 5.1 `GET /cart`

장바구니가 없거나 만료되었으면 빈 장바구니(`items: []`, `totalAmount: 0`, `expiresAt: null`)를 반환한다. `404`를 쓰지 않는다.

### 5.2 `POST /cart/items`

```json
{ "productId": "americano", "quantity": 2 }
```

1. `productId`가 비었거나 `quantity`가 1–99 범위 밖이면 `400 VALIDATION_ERROR`.
2. 카탈로그에 없는 상품이면 `404 PRODUCT_NOT_FOUND`.
3. 이미 담긴 상품이면 기존 수량에 더한다. 합이 99를 넘으면 `422 CART_ITEM_QUANTITY_EXCEEDED`이고 장바구니는 바뀌지 않는다.
4. 저장하고 TTL을 다시 설정한 뒤 장바구니를 반환한다.

### 5.3 `PATCH /cart/items/{productId}`

```json
{ "quantity": 3 }
```

1. `quantity`가 1–99 범위 밖이면 `400 VALIDATION_ERROR`. 수량 0으로 빼는 동작은 지원하지 않는다(`DELETE` 사용).
2. 장바구니에 없는 상품이면 `404 CART_ITEM_NOT_FOUND`.
3. 수량을 요청 값으로 바꾸고 저장한 뒤 장바구니를 반환한다.

### 5.4 `DELETE /cart/items/{productId}`

1. 장바구니에 없는 상품이면 `404 CART_ITEM_NOT_FOUND`.
2. 상품을 빼고 저장한 뒤 장바구니를 반환한다. 마지막 상품이었다면 Redis 키가 삭제되고 빈 장바구니를 반환한다.

### 5.5 `DELETE /cart`

장바구니 키를 삭제하고 `204 No Content`를 반환한다. 장바구니가 없어도 `204`다.

## 6. 오류

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `X-Customer-Id` 누락·공백, 본문 누락·형식 오류, 수량 범위 밖, 빈 `productId` |
| 404 | `PRODUCT_NOT_FOUND` | 담으려는 상품이 카탈로그에 없음 |
| 404 | `CART_ITEM_NOT_FOUND` | 수량을 바꾸거나 빼려는 상품이 장바구니에 없음 |
| 422 | `CART_ITEM_QUANTITY_EXCEEDED` | 담은 뒤 수량이 99를 넘음 |

오류 본문은 기존 공통 형식(`code`, `message`)을 따른다.

## 7. 테스트 수용 조건

1. 빈 장바구니 조회는 `items: []`, `totalAmount: 0`, `expiresAt: null`이다.
2. 상품을 담으면 Mock 카탈로그의 이름·단가로 `lineAmount`, `totalAmount`가 계산된다.
3. 같은 상품을 다시 담으면 수량이 더해진다. 합이 99를 넘으면 `422`이고 기존 수량이 유지된다.
4. 없는 상품을 담으면 `404 PRODUCT_NOT_FOUND`이다.
5. 수량 변경은 값을 교체한다. 담기지 않은 상품의 변경·빼기는 `404 CART_ITEM_NOT_FOUND`이다.
6. 마지막 상품을 빼면 Redis 키가 삭제된다.
7. 비우기는 장바구니 유무와 관계없이 `204`이고, 이후 조회는 빈 장바구니다.
8. `X-Customer-Id` 누락·공백, 수량 0·100, 빈 `productId`는 `400 VALIDATION_ERROR`이다.
9. 쓰기 후 Redis 키에 설정값 TTL이 걸리고, 응답의 `expiresAt`이 그 TTL과 일치한다.
10. 응답 `items`는 `productId` 오름차순이다.
11. 고객 A의 변경이 고객 B의 장바구니에 영향을 주지 않는다.
