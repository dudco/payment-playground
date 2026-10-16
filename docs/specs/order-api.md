# 주문 API 명세

> 상태: 승인됨 · 작성일: 2026-10-14 · 최종 갱신일: 2026-10-16 (2장 패키지와 책임을 [코드 컨벤션](../conventions.md)에 맞춤)

> [PRD](../prd.md), [데이터 모델](../data-model.md), [장바구니 API 명세](cart-api.md), [ADR-0003](../adr/0003-cart-in-redis-with-ttl.md), [ADR-0004](../adr/0004-flyway-schema-migration.md), [ADR-0007](../adr/0007-checkout-idempotency-key.md)을 바탕으로 한 두 번째 기능 명세다. 트래픽이 거의 없는 환경을 전제로 하며, 동시성 제어는 넣지 않는다([PRD 8. 알려진 한계](../prd.md#8-알려진-한계)).

## 1. 범위

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/orders` | 현재 고객의 장바구니로 주문 생성. 같은 멱등키 재호출은 기존 주문 반환 |
| `GET` | `/orders/{orderId}` | 현재 고객의 주문 한 건 조회 |

주문은 장바구니에서만 만든다. 요청 본문으로 주문 라인을 받지 않는다. 주문의 상품·수량은 요청 시점에 Redis에 저장된 장바구니, 상품명·단가는 요청 시점의 `ProductRepository` 값으로 정해지고 이후 바뀌지 않는다(스냅샷).

범위 밖: 결제(다음 스펙), 주문 취소, 주문 목록 조회, 재고 차감, 쿠폰·할인, 동시 요청 제어.

> 경로: [ADR-0007](../adr/0007-checkout-idempotency-key.md)의 흐름 예시는 `POST /order`로 적혀 있지만, 주문은 고객별로 여러 건이 쌓이는 컬렉션이므로 `/products`, `/payments`와 같이 복수형 `/orders`를 쓴다. 고객당 하나뿐인 `/cart`만 단수형이다.

## 2. 패키지와 책임

[코드 컨벤션](../conventions.md)을 따른다.

```text
io.github.dudco.paymentplayground.order
├── controller
│   ├── OrderController    헤더 검증, PlaceOrderResult → 201/200 + OrderResponse
│   └── OrderApi           OrderResponse, OrderLineResponse
├── service
│   ├── PlaceOrderService  장바구니 확인, 스냅샷 생성, 멱등 재호출 처리, 커밋 후 장바구니 삭제. PlaceOrderResult(order, created) 반환
│   ├── GetOrderService    주문 한 건 조회, OrderResult 반환
│   └── OrderResult        OrderResult, OrderLineResult (두 서비스가 공유)
├── domain
│   ├── Order              주문 엔티티 (@Table(name = "orders")), OrderLine 목록을 가진다. OrderLine, OrderStatus도 같은 파일
│   └── OrderErrors        cartNotFound(), orderNotFound(), idempotencyKeyMismatch()
└── repository
    └── OrderRepository    Spring Data JPA. findByIdempotencyKey, findById
```

- 의존 방향은 `order → cart`, `order → product`다. `PlaceOrderService`는 장바구니를 `CartRepository`로 읽고(읽기 전용) `CartService.clear`로 지우며, `ProductRepository`로 상품명·단가를 읽는다(읽기 전용). `cart` 패키지는 `order`를 알지 못한다.
- 고객 ID는 장바구니와 같이 `@CurrentCustomer customerId: String`으로 받는다([ADR-0006](../adr/0006-cart-per-customer.md)).
- 멱등키는 Controller에서 `Idempotency-Key` 요청 헤더로 받아 앞뒤 공백을 제거한 값을 서비스에 넘긴다([ADR-0007](../adr/0007-checkout-idempotency-key.md#키-전달-방식)). 공통 필터·인터셉터로 옮기는 것은 이후 단계에서 실험한다.
- `Order` 엔티티는 서비스 밖으로 나가지 않는다. 지연 로딩되는 `lines`는 트랜잭션 안에서 `OrderResult`로 옮긴다(`Order.toResult()`).
- 현재 시각은 주입받은 `Clock`에서 얻는다.

## 3. 저장 모델

스키마는 Flyway 마이그레이션 `V1__create_orders.sql`로 만든다([ADR-0004](../adr/0004-flyway-schema-migration.md)). 열 타입과 시각 저장 형식은 구현 계획에서 Hibernate SQLite 매핑을 확인한 뒤 확정한다.

### 3.1 `orders`

| 열 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 ID(UUID 문자열) | PK |
| `idempotency_key` | 원본 장바구니의 체크아웃 멱등키 | `NOT NULL`, `UNIQUE` |
| `customer_id` | 고객 ID | `NOT NULL` |
| `status` | 주문 상태 | `NOT NULL`, `CHECK (status IN ('CREATED', 'PAID'))` |
| `total_amount` | 주문 총액(KRW) | `NOT NULL`, `CHECK (total_amount >= 0)` |
| `created_at` | 주문 생성 시각 | `NOT NULL` |
| `updated_at` | 마지막 상태 변경 시각 | `NOT NULL` |

- `PAID`는 이 스펙에서 쓰지 않는다. SQLite는 `CHECK` 제약을 바꾸려면 테이블을 다시 만들어야 하므로, 결제 스펙에서 쓸 값을 미리 허용해 둔다. `CREATED → PAID` 전이 규칙은 결제 스펙에서 정한다.
- 생성 시 `updated_at = created_at`이다.

### 3.2 `order_lines`

| 열 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 라인 ID(UUID 문자열) | PK |
| `order_id` | 소속 주문 | `NOT NULL`, FK → `orders.id` (선언만 하고 이 단계에서는 강제하지 않는다. SQLite `foreign_keys`가 꺼져 있고, 테스트 DB 정리기가 테이블을 순서 없이 비운다.) |
| `product_id` | 상품 ID | `NOT NULL` |
| `product_name` | 주문 시점 상품명 | `NOT NULL` |
| `unit_price` | 주문 시점 단가 | `NOT NULL`, `CHECK (unit_price >= 0)` |
| `quantity` | 수량 | `NOT NULL`, `CHECK (quantity BETWEEN 1 AND 99)` |
| `line_amount` | `unit_price × quantity` | `NOT NULL`, `CHECK (line_amount = unit_price * quantity)` |

- `UNIQUE (order_id, product_id)`: 한 주문에 같은 상품 라인은 하나다(장바구니 규칙과 같다).
- 인덱스: `order_lines.order_id`.
- `orders.total_amount = Σ order_lines.line_amount`는 DB 제약으로 걸 수 없으므로 `Order` 생성자에서 검증한다.
- 주문과 주문 라인은 생성 후 수정하지 않는다. 상태 변경(결제)만 `orders.status`, `orders.updated_at`을 바꾼다.

## 4. 공통 규칙

- 고객 ID 규칙은 장바구니와 같다(`X-Customer-ID`, 앞뒤 공백 제거, 없거나 공백이면 `400 VALIDATION_ERROR`).
- 응답의 `lines`는 `productId` 오름차순이다.
- 금액은 KRW 정수다. `lineAmount = unitPrice × quantity`, `totalAmount = Σ lineAmount`.

### 주문 응답 본문

`POST /orders`, `GET /orders/{orderId}`는 다음 본문을 반환한다.

```json
{
  "orderId": "0b5d1c1e-3f7a-4a0e-9a59-2f8f3f1d7c11",
  "customerId": "customer-001",
  "idempotencyKey": "6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",
  "status": "CREATED",
  "lines": [
    { "productId": "americano", "productName": "아메리카노", "unitPrice": 4500, "quantity": 2, "lineAmount": 9000 },
    { "productId": "cake", "productName": "케이크", "unitPrice": 6000, "quantity": 1, "lineAmount": 6000 }
  ],
  "totalAmount": 15000,
  "createdAt": "2026-10-14T03:10:00Z"
}
```

`idempotencyKey`를 응답에 싣는 이유는 다음 단계의 결제 요청이 같은 키를 `Idempotency-Key`로 보내기 때문이다([ADR-0007](../adr/0007-checkout-idempotency-key.md)). 장바구니는 주문 후 사라지므로, 클라이언트는 주문 응답에서 키를 다시 얻을 수 있어야 한다.

## 5. API 계약

### 5.1 `POST /orders`

요청 헤더:

```http
X-Customer-ID: customer-001
Idempotency-Key: 6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10
```

본문은 없다. 본문이 와도 읽지 않는다.

처리 순서:

1. `Idempotency-Key`가 없거나 공백이면 `400 VALIDATION_ERROR`. 앞뒤 공백은 제거한다. 형식(UUID 여부)은 검사하지 않는다. 서버가 발급하지 않은 키는 아래 3·4단계에서 장바구니 키와 맞지 않아 거절된다.
2. `orders`에서 `idempotency_key`가 같은 주문을 찾는다.
   - 있고 `customer_id`가 현재 고객이면 **재호출**로 본다. 새 주문을 만들지 않고 기존 주문을 `200 OK`로 반환한다. 이때 현재 고객의 장바구니가 같은 키를 가지고 있으면(이전 요청의 장바구니 삭제가 실패한 경우) 장바구니를 삭제한다. 현재 장바구니 내용이 주문과 달라도 기존 주문을 그대로 반환한다.
   - 있지만 다른 고객의 주문이면 `409 IDEMPOTENCY_KEY_MISMATCH`. 다른 고객 주문의 존재를 드러내지 않도록 아래 4단계와 같은 코드·메시지를 쓴다.
3. 현재 고객의 장바구니를 읽는다. 없으면(비었거나 TTL 만료) `404 CART_NOT_FOUND`.
4. 장바구니의 `idempotencyKey`가 요청 키와 다르면 `409 IDEMPOTENCY_KEY_MISMATCH`. 주문 화면에 머무는 동안 장바구니가 만료되었다가 다시 만들어진 경우 등이며, 클라이언트는 `GET /cart`로 새 키와 내용을 확인한 뒤 다시 요청한다.
5. 장바구니 라인마다 `ProductRepository`에서 상품명·단가를 얻어 주문 라인을 만든다. 카탈로그에 없는 상품의 라인은 `GET /cart` 응답과 같이 제외한다. 남는 라인이 없으면 `404 CART_NOT_FOUND`.
6. 하나의 DB 트랜잭션에서 `orders`(상태 `CREATED`) 한 행과 `order_lines`를 저장한다.
7. 커밋이 끝난 뒤 장바구니를 삭제한다(`DEL cart:{customerId}`). 삭제가 실패해도 주문은 이미 확정되었으므로 경고 로그만 남기고 성공 응답을 반환한다. 커밋이 실패하면 장바구니를 지우지 않는다.
8. `201 Created`, `Location: /orders/{orderId}`와 주문 본문을 반환한다.

같은 키의 재호출은 처음 요청이 `201`, 이후 요청이 `200`이고 본문은 같다. 주문 여부의 기준은 `orders.idempotency_key` 한 곳이며, 장바구니에 "주문됨" 상태를 기록하지 않는다([ADR-0007](../adr/0007-checkout-idempotency-key.md)).

주문 후 같은 고객이 다시 상품을 담으면 장바구니가 없는 상태에서 시작하므로 새 멱등키가 발급되고, 다음 구매는 새 주문이 된다.

### 5.2 `GET /orders/{orderId}`

1. `orderId`의 주문이 없거나 다른 고객의 주문이면 `404 ORDER_NOT_FOUND`. 두 경우를 구분하지 않는다.
2. 주문을 `200 OK`로 반환한다. 상품명·단가는 저장된 스냅샷이며 현재 카탈로그를 다시 조회하지 않는다.

`orderId`가 UUID 형식이 아니어도 별도 `400`을 쓰지 않고 `404 ORDER_NOT_FOUND`로 응답한다.

## 6. 오류

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `X-Customer-ID` 누락·공백, `Idempotency-Key` 누락·공백 |
| 404 | `CART_NOT_FOUND` | 주문할 장바구니가 없음(비었음, TTL 만료, 주문 가능한 라인 없음) |
| 404 | `ORDER_NOT_FOUND` | 조회할 주문이 없거나 다른 고객의 주문 |
| 409 | `IDEMPOTENCY_KEY_MISMATCH` | 요청 키가 현재 장바구니의 키와 다름, 또는 다른 고객의 주문 키 |

오류 본문은 공통 형식(`code`, `message`)을 따른다. 멱등키 값은 오류 메시지에 싣지 않는다.

## 7. 알려진 한계

이 단계에서는 막지 않고 [PRD 8. 알려진 한계](../prd.md#8-알려진-한계)에 기록한다. 이후 동시성·정합성 단계의 재현 대상이다.

- **같은 키의 동시 주문 요청.** 두 요청이 모두 2단계에서 "주문 없음"을 읽으면 둘 다 INSERT를 시도한다. `orders.idempotency_key` unique 제약으로 주문은 하나만 생기지만, 늦은 요청은 제약 위반 예외로 `500`이 된다. 기존 주문을 반환하도록 정리하는 것은 이후 단계에서 한다.
- **주문 커밋 후 장바구니 삭제 실패.** 장바구니가 같은 키로 남는다. 고객이 이 장바구니를 수정한 뒤 다시 주문하면 새 주문이 아니라 기존 주문이 반환된다(키가 같으므로). 장바구니는 TTL로 결국 사라지고, 같은 키로 다시 `POST /orders`를 보내면 2단계에서 정리된다.
- **장바구니 읽기와 삭제 사이의 장바구니 변경.** 3단계에서 장바구니를 읽은 뒤 7단계 삭제 전에 같은 고객의 `POST /cart`가 들어오면, 그 변경은 주문에 반영되지 않은 채 장바구니와 함께 삭제된다.
- **가격 확인 없음.** 고객이 장바구니에서 본 가격과 주문 시점 가격이 다르면 주문 시점 가격으로 주문된다. 클라이언트가 기대 총액을 보내 검증하는 방식은 이 단계에서 넣지 않는다(Mock 카탈로그 가격은 실행 중 바뀌지 않는다).

## 8. 테스트 수용 조건

자동 테스트는 장바구니 저장소를 Redis 없이 대체하고, RDB는 기존과 같이 Flyway 마이그레이션을 적용한 SQLite 파일 DB를 쓴다.

1. 장바구니가 있는 고객이 그 키로 `POST /orders`하면 `201 Created`, `Location` 헤더, 상태 `CREATED`, 카탈로그 이름·단가로 계산한 `lines`·`totalAmount`, 장바구니와 같은 `idempotencyKey`를 반환한다.
2. 주문 생성 후 `orders` 한 행과 장바구니 라인 수만큼의 `order_lines`가 저장되고, `total_amount`는 라인 금액의 합이다.
3. 주문 생성 커밋 후 장바구니 삭제가 호출된다. 트랜잭션이 실패하면 삭제가 호출되지 않는다.
4. 장바구니 삭제가 실패해도 `201`로 주문을 반환한다.
5. 같은 키로 다시 `POST /orders`하면 `200 OK`와 처음과 같은 주문을 반환하고, `orders` 행은 하나다. 장바구니가 없어도, 내용이 달라도 결과는 같다.
6. 재호출 시 같은 키를 가진 장바구니가 남아 있으면 삭제된다.
7. 장바구니가 없으면 `404 CART_NOT_FOUND`이고 주문이 저장되지 않는다.
8. 장바구니 키와 다른 키, 또는 다른 고객 주문의 키로 요청하면 `409 IDEMPOTENCY_KEY_MISMATCH`이고 주문이 저장되지 않는다.
9. `Idempotency-Key`·`X-Customer-ID` 누락·공백은 `400 VALIDATION_ERROR`이다.
10. 주문 후 카탈로그 가격이 바뀌어도 `GET /orders/{orderId}`는 주문 시점 단가와 금액을 반환한다(테스트용 카탈로그로 검증).
11. `GET /orders/{orderId}`는 본인 주문을 반환하고, 없는 주문·다른 고객의 주문·UUID가 아닌 ID는 `404 ORDER_NOT_FOUND`이다.
12. 마이그레이션의 DB 제약이 동작한다: 같은 `idempotency_key` 두 번 저장, `quantity` 0·100, `line_amount ≠ unit_price × quantity`는 저장에 실패한다.
