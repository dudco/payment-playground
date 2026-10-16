# 장바구니 API 명세

> 상태: 승인됨 · 작성일: 2026-10-13 · 최종 갱신일: 2026-10-16 (2장 패키지와 책임을 [코드 컨벤션](../conventions.md)에 맞춤)

> [PRD](../prd.md), [데이터 모델](../data-model.md), [ADR-0003](../adr/0003-cart-in-redis-with-ttl.md), [ADR-0005](../adr/0005-product-api-mock-catalog.md), [ADR-0006](../adr/0006-cart-per-customer.md), [ADR-0007](../adr/0007-checkout-idempotency-key.md)을 바탕으로 한 첫 번째 기능 명세다. 트래픽이 거의 없는 환경을 전제로 하며, 동시성 제어는 넣지 않는다([PRD 8. 알려진 한계](../prd.md#8-알려진-한계)).

## 1. 범위

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/cart` | 현재 고객의 장바구니 조회 |
| `POST` | `/cart` | 현재 고객의 장바구니 전체를 요청 내용으로 저장 |

담기·수량 변경·빼기·비우기를 위한 API는 따로 두지 않는다. 클라이언트는 변경된 장바구니 전체를 `POST /cart`로 다시 보낸다.

장바구니는 처음 저장될 때 체크아웃 멱등키(`idempotencyKey`)를 발급받는다. 이 키는 이후 주문 생성과 결제 요청에서 같은 구매 흐름을 식별하는 데 쓰인다([ADR-0007](../adr/0007-checkout-idempotency-key.md)). 주문·결제 API 자체는 이 스펙의 범위가 아니다.

## 2. 패키지와 책임

[코드 컨벤션](../conventions.md)을 따른다.

```text
io.github.dudco.paymentplayground
├── cart
│   ├── CartController       헤더·본문 검증, SaveCartRequest → CartLineCommand, CartResult → CartResponse
│   ├── CartApi              SaveCartRequest, SaveCartLineRequest, CartResponse, CartLineResponse
│   ├── CartService          상품 확인, 멱등키 발급·유지, 금액 계산. CartLineCommand를 받고 CartResult를 반환
│   ├── Cart                 고객 ID, 멱등키, 장바구니 라인(상품별 수량)
│   ├── CartRepository       저장소 인터페이스, StoredCart
│   ├── RedisCartRepository  Redis JSON + TTL 구현. 저장 형식(JSON 문서)은 이 파일 안의 private 타입
│   └── CartProperties       payment-playground.cart.* 설정
├── customer
│   ├── CurrentCustomer             컨트롤러 파라미터에 붙이는 애노테이션
│   └── HeaderCurrentCustomerResolver  X-Customer-ID 헤더에서 고객 ID를 꺼내는 임시 구현
└── support
    └── IdempotencyKeyGenerator     체크아웃 멱등키 생성(UUID). 장바구니·주문·결제가 공유하는 개념이라 support에 둔다
```

- Controller는 고객 ID를 헤더에서 직접 읽지 않고 `@CurrentCustomer customerId: String` 파라미터로 받는다. 인증이 생기면 resolver 구현만 바꾸고 Controller와 Service는 그대로 둔다([ADR-0006](../adr/0006-cart-per-customer.md)).
- Service는 `ProductRepository`로 상품을 확인하고(읽기 전용), 결과에 상품명·단가·금액을 채운다. `Cart`는 서비스 밖으로 나가지 않는다.
- 주문 기능에는 `findForCheckout(customerId): CheckoutCartResult?`(멱등키와 `productId` 오름차순 라인, 가격 계산 없음)와 `clear(customerId)`를 제공한다. 장바구니 삭제는 상태 변경이므로 주문은 반드시 `clear`를 거친다. 조회도 주문에 넘길 모양이 정해져 있어 `findForCheckout`을 쓴다.
- Repository는 저장과 TTL만 다룬다. 금액 계산을 하지 않는다.

## 3. 저장 모델

- Redis 키: `cart:{customerId}`, 값: JSON 문자열 하나.

  ```json
  {
    "customerId": "customer-001",
    "idempotencyKey": "6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",
    "lines": [ { "productId": "americano", "quantity": 2 } ],
    "createdAt": "2026-10-13T11:20:30Z",
    "updatedAt": "2026-10-13T11:25:00Z"
  }
  ```

- 저장은 `SET cart:{customerId} <json> EX <ttl>` 한 명령이다. 저장할 때마다 TTL이 다시 설정된다.
- TTL: `payment-playground.cart.ttl`(기본 `30m`).
- 상품명과 가격은 저장하지 않는다.
- 상품이 하나도 없는 장바구니는 저장하지 않고 키를 삭제한다.

## 4. 공통 규칙

- 고객 ID는 `X-Customer-ID` 헤더에서 얻는다(임시). 앞뒤 공백은 제거한다(`" c1 "`과 `"c1"`은 같은 고객). 없거나 공백이면 `400 VALIDATION_ERROR`.
- 수량은 상품당 1 이상 99 이하다.
- 상품명·단가는 요청 시점의 `ProductRepository` 값이다. `lineAmount = unitPrice × quantity`, `totalAmount = Σ lineAmount`.
- 저장된 라인 중 카탈로그에 더 이상 없는 상품은 응답의 `lines`와 `totalAmount`에서 제외한다(저장값은 다음 `POST /cart`에서 교체된다).
- 응답의 `lines`는 `productId` 오름차순이다.
- `expiresAt`은 Redis TTL로 계산한 만료 시각이다. 장바구니가 없으면 `null`이다.

### 4.1 멱등키 규칙

- 장바구니가 없는 상태에서 상품이 있는 `POST /cart`가 오면 서버가 새 UUID 멱등키를 발급한다.
- 장바구니가 있는 동안의 `POST /cart`는 내용만 바꾸고 멱등키는 유지한다. 장바구니 내용이 바뀌어도 같은 구매 흐름으로 본다.
- 장바구니가 사라지면(빈 목록으로 저장, TTL 만료) 멱등키도 함께 사라진다. 이후 다시 담으면 새 키가 발급된다.
- 클라이언트는 멱등키를 직접 지정할 수 없다.
- 주문이 생성된 뒤의 장바구니 처리(삭제, 새 키 발급)는 주문 스펙에서 정한다([ADR-0007](../adr/0007-checkout-idempotency-key.md)).

### 장바구니 응답 본문

`GET /cart`, `POST /cart`는 모두 `200 OK`와 다음 본문을 반환한다.

```json
{
  "customerId": "customer-001",
  "idempotencyKey": "6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",
  "lines": [
    { "productId": "americano", "productName": "아메리카노", "unitPrice": 4500, "quantity": 2, "lineAmount": 9000 },
    { "productId": "cake", "productName": "케이크", "unitPrice": 6000, "quantity": 1, "lineAmount": 6000 }
  ],
  "totalAmount": 15000,
  "expiresAt": "2026-10-13T11:55:00Z"
}
```

장바구니가 없으면 `idempotencyKey: null`, `lines: []`, `totalAmount: 0`, `expiresAt: null`이다.

## 5. API 계약

### 5.1 `GET /cart`

저장된 장바구니를 반환한다. 없거나 만료되었으면 빈 장바구니를 반환한다. `404`를 쓰지 않는다. 조회는 TTL을 연장하지 않는다.

### 5.2 `POST /cart`

```json
{
  "lines": [
    { "productId": "americano", "quantity": 2 },
    { "productId": "cake", "quantity": 1 }
  ]
}
```

1. `lines`가 없거나, 원소의 `productId`가 비었거나, `quantity`가 1–99 범위 밖이거나, 같은 `productId`가 두 번 이상 나오면 `400 VALIDATION_ERROR`.
2. 카탈로그에 없는 상품이 하나라도 있으면 `404 PRODUCT_NOT_FOUND`이고 장바구니는 바뀌지 않는다.
3. `lines`가 비어 있으면 장바구니를 삭제하고 빈 장바구니를 반환한다.
4. 기존 장바구니가 있으면 멱등키와 `createdAt`을 유지하고 `lines`·`updatedAt`을 교체한다. 없으면 새 멱등키를 발급한다.
5. 저장하고 TTL을 다시 설정한 뒤 장바구니를 반환한다.

같은 본문을 여러 번 보내도 결과 상태가 같다. 요청 자체가 멱등이므로 `POST /cart`에는 별도 `Idempotency-Key` 헤더를 받지 않는다.

## 6. 오류

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `X-Customer-ID` 누락·공백, 본문 누락·형식 오류, 수량 범위 밖, 빈 `productId`, 중복 `productId` |
| 404 | `PRODUCT_NOT_FOUND` | 카탈로그에 없는 상품 |

오류 본문은 기존 공통 형식(`code`, `message`)을 따른다.

## 7. 테스트 수용 조건

1. 장바구니가 없으면 `GET /cart`는 `idempotencyKey: null`, `lines: []`, `totalAmount: 0`, `expiresAt: null`이다.
2. 첫 `POST /cart`는 멱등키를 발급하고, Mock 카탈로그의 이름·단가로 `lineAmount`, `totalAmount`를 계산한다.
3. 이어지는 `POST /cart`는 상품 추가·수량 변경·상품 제거를 모두 전체 교체로 반영하고, 멱등키는 바뀌지 않는다.
4. 빈 `lines`로 `POST /cart`하면 장바구니가 삭제된다. 이후 다시 담으면 다른 멱등키가 발급된다.
5. 없는 상품이 섞인 요청은 `404 PRODUCT_NOT_FOUND`이고 기존 장바구니가 그대로 남는다.
6. `X-Customer-ID` 누락·공백, 수량 0·100, 빈 `productId`, 중복 `productId`, `lines` 누락은 `400 VALIDATION_ERROR`이다.
7. 저장은 설정값 TTL을 담은 `SET` 한 명령으로 실행되고, 응답의 `expiresAt`은 남은 TTL로 계산된다. `GET /cart`는 TTL을 바꾸는 명령을 실행하지 않는다. 이 단계의 자동 테스트는 Redis 명령 호출과 인자까지만 검증하고, 실제 Redis 저장은 수동으로 확인한다([구현 계획](../plans/2026-10-13-01-cart-api-implementation.md#테스트-전략)).
8. 응답 `lines`는 `productId` 오름차순이다.
9. 고객 A의 저장이 고객 B의 장바구니와 멱등키에 영향을 주지 않는다.
