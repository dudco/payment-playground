# 주문·결제 API 초안 명세

> 상태: 초안 · 작성일: 2026-09-13 · 최종 갱신일: 2026-10-09

> 이 문서는 [문서 가이드](../README.md), [PRD](../prd.md), [데이터 모델](../data-model.md), [ADR-0001](../adr/0001-payment-gateway-by-method.md), [ADR-0002](../adr/0002-cart-checkout-creates-order.md), [ADR-0003](../adr/0003-cart-in-redis-with-ttl.md), [ADR-0004](../adr/0004-flyway-schema-migration.md)를 바탕으로 기본적인 주문·결제 흐름을 실제 Spring Boot API로 만드는 기술 초안이다. 트래픽이 거의 없는 환경을 전제로 한 최소 뼈대이며, 동시성 제어는 넣지 않는다([PRD 10. 알려진 한계](../prd.md#10-알려진-한계)).

## 1. 범위

- `POST /order/cart`: 멱등키 기반 Cart 초안 생성 또는 갱신 (Redis)
- `GET /order/cart`: 멱등키 기반 Cart 초안 선택 조회
- `POST /order`: Cart 초안을 불변 주문으로 전환
- `POST /payments`: `method` 기반 결제 생성 및 즉시 승인

결제 취소, 실제 PG/VAN 네트워크, 웹뷰, 포인트 원장, 인증/인가, 장바구니 병합은 구현하지 않는다.

## 2. 패키지와 책임

```text
io.github.dudco.paymentplayground
├── cart       Cart 모델·Redis 저장소·Service·API
├── order      Order/OrderLine 엔티티·Repository·Service·API
├── payment    Payment 엔티티, API, 상태 전이
├── gateway    결제수단별 인터페이스와 Fake 구현체
└── support    API 오류 응답, 예외 처리, 시간/ID 지원
```

- Controller는 HTTP 요청 검증과 응답 변환만 담당한다.
- Service는 트랜잭션, 상태 전이, 금액 검증을 담당한다.
- Gateway는 승인 결과만 반환하며 JPA 엔티티를 직접 변경하지 않는다. 거절도 예외가 아닌 결과값이다.
- 주문·결제 Repository는 Spring Data JPA, Cart 저장소는 `StringRedisTemplate`과 JSON 직렬화로 구현한다.
- 시간은 테스트에서 고정할 수 있도록 `Clock` 빈으로 주입한다.

## 3. 저장 모델

RDB 스키마는 `src/main/resources/db/migration`의 Flyway SQL로 만든다. `orders`·`order_lines`는 `V1__create_orders.sql`, `payments`는 `V2__create_payments.sql`에 둔다. JPA는 `ddl-auto=validate`만 사용한다.

### 3.1 Cart (Redis)

- 키 `cart:{idempotencyKey}`, 값은 `idempotencyKey`, `customerId`, `orderLines`, `totalAmount`, `createdAt`, `updatedAt` JSON
- TTL은 `payment-playground.cart.ttl`(기본 `1h`). 쓰기마다 `SET key value EX ttl`로 다시 설정한다.
- 상태 필드는 없다. 주문 여부는 `orders.idempotency_key`로 판단한다.

### 3.2 Order와 OrderLine

- 테이블은 `orders`, `order_lines`. `Order` 엔티티는 `@Table(name = "orders")`로 매핑한다.
- `orders.idempotency_key`는 unique다.
- `POST /order` 시 Cart의 `orderLines`를 `OrderLine` 행으로 복사한다.
- `Order.totalAmount`와 모든 `OrderLine.lineAmount`의 합은 일치해야 한다.
- 상태는 `CREATED`, `PAID` 두 가지다.

### 3.3 Payment

- 테이블은 `payments`. `orderId`, `method`, `status`(`APPROVED`/`FAILED`), `amount`, 승인 결과, 거절 정보, 비민감 `metadata`를 가진다.
- Gateway 결과가 나온 뒤 한 번 insert하며 이후 수정하지 않는다.
- 원 카드번호, Track2, PIN, 주민번호, PG 토큰, 포인트 바코드는 영속화하지 않는다.

## 4. Cart와 Order 계약

### 4.1 `POST /order/cart`

- `Idempotency-Key` 헤더는 선택이다. 없으면 UUID 기반 키를 생성해 응답 헤더와 본문에 반환한다.
- 처리 순서:
  1. `orders`에 같은 키의 주문이 있으면 `409 CART_ALREADY_ORDERED`.
  2. Redis에 같은 키의 Cart가 있고 `customerId`가 다르면 `409 CART_CUSTOMER_MISMATCH`.
  3. Cart가 있으면 `orderLines`·`totalAmount`·`updatedAt`을 교체하고 TTL을 다시 설정한 뒤 `200`.
  4. 없으면 새 Cart를 저장하고 `201`.
- 응답에는 Redis TTL로 계산한 `expiresAt`을 포함한다.

### 4.2 `GET /order/cart`

- `Idempotency-Key` 헤더가 필수다.
- Redis에 Cart가 없으면(미생성·만료·주문 후 삭제) `404 CART_NOT_FOUND`다.
- 조회 호출 여부는 주문 생성에 영향을 주지 않는다.

### 4.3 `POST /order`

- `Idempotency-Key` 헤더가 필수다.
- 처리 순서:
  1. `orders`에 같은 키의 주문이 있으면 그 주문을 `200`으로 반환한다.
  2. Redis에서 Cart를 읽는다. 없으면 `404 CART_NOT_FOUND`.
  3. 하나의 DB 트랜잭션에서 `Order(CREATED)`와 `OrderLine`들을 저장한다.
  4. 커밋 후 Redis Cart를 삭제하고 `201`을 반환한다. 삭제 실패는 로그만 남기고 응답에 영향을 주지 않는다.

## 5. Payment 계약

`POST /payments` 요청은 공통 `orderId`, `method`, `amount`, `paymentDetails`를 가진다.

| method | Gateway | paymentDetails 필수값 |
| --- | --- | --- |
| `ONLINE_CARD` | `FakeOnlineCardPaymentGateway` | `paymentToken` |
| `OFFLINE_CARD` | `FakeOfflineCardPaymentGateway` | `terminalId`, `vanTransactionId`, `approvalNumber`, `approvedAt`, `responseCode`, `vatAmount`, `issuerCode`, `acquirerCode`, `maskedCardNumber` |
| `POINT` | `FakePointPaymentGateway` | `channel` 및 OFFLINE이면 `barcode`, ONLINE이면 `pointPaymentMethodId` |

처리 순서 (전체가 하나의 DB 트랜잭션):

1. 주문을 조회한다. 없으면 `404 ORDER_NOT_FOUND`.
2. 주문이 `PAID`면 `409 ORDER_ALREADY_PAID`.
3. `amount`가 주문 총액과 다르면 `422 ORDER_AMOUNT_MISMATCH`.
4. `method`별 세부 필드가 누락되거나 종류가 맞지 않으면 `422 PAYMENT_DETAILS_INVALID`.
5. 1–4에서 거절되면 Payment를 만들지 않는다.
6. `method`에 맞는 Gateway를 호출해 `PaymentApprovalResult`를 받는다.
7. 승인이면 `Payment(APPROVED)`를 저장하고 주문을 `PAID`로 바꾼다.
8. 거절이면 `Payment(FAILED)`를 저장하고 주문은 그대로 둔다.
9. 트랜잭션을 정상 커밋한 뒤 결과를 반환한다.

**거절 기록이 롤백되지 않도록 한다.** 8번에서 예외를 던지면 `@Transactional`이 롤백해 `FAILED` 행이 사라진다. Service는 거절을 예외가 아닌 결과값(예: `PaymentResult.Declined(payment)`)으로 반환하고, Controller가 이를 `422 PAYMENT_DECLINED`로 변환한다. 1–4의 검증 실패는 아직 아무것도 쓰지 않았으므로 예외로 처리해도 된다.

응답:

- 승인: `201`과 `paymentId`, `orderId`, `method`, `status`, `amount`, `approvedAt`
- 거절: `422`와 공통 오류 본문에 `paymentId`를 추가로 포함

## 6. Fake Gateway 결정 규칙

테스트 가능하고 예측 가능한 결과만 사용한다. 금액 검증은 Gateway가 아니라 5절 3번의 공통 규칙이 담당한다.

- 온라인 카드: 토큰이 `fake-decline`이면 거절하고, 그 외 빈 값이 아닌 토큰은 승인한다.
- 오프라인 카드: `responseCode == "0000"`일 때만 승인한다. 그 외 응답코드는 거절하며 `failureCode`에 해당 응답코드를 담는다.
- 포인트: 참조값이 `fake-decline`이면 거절하고, 그 외 빈 값이 아닌 참조값은 승인한다.
- 승인 시 온라인 카드·포인트는 UUID 기반 `providerTransactionId`를, 오프라인 카드는 요청의 `vanTransactionId`를 사용한다. 모두 승인 시각과 결제수단별 비민감 메타데이터를 반환한다.

## 7. 오류 응답

모든 도메인 오류는 다음 JSON 형식으로 반환한다.

```json
{
  "code": "CART_NOT_FOUND",
  "message": "해당 멱등키로 생성된 장바구니가 없습니다."
}
```

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 헤더/본문 누락, 빈 라인, 0 이하 수량·금액 |
| 404 | `CART_NOT_FOUND`, `ORDER_NOT_FOUND` | 대상 없음(Cart 만료 포함) |
| 409 | `CART_ALREADY_ORDERED`, `CART_CUSTOMER_MISMATCH`, `ORDER_ALREADY_PAID` | 허용되지 않는 상태 전이 |
| 422 | `PAYMENT_DETAILS_INVALID`, `ORDER_AMOUNT_MISMATCH`, `PAYMENT_DECLINED` | 비즈니스 검증 또는 승인 거절 |

## 8. 테스트 수용 조건

1. 키 없는 Cart 생성은 키와 `201`, `expiresAt`을 반환하고 Redis 키에 TTL이 설정된다.
2. 같은 키의 Cart 갱신은 `200` 및 최신 라인을 반환하고 TTL을 다시 설정한다.
3. 같은 키로 다른 `customerId`의 갱신은 `409 CART_CUSTOMER_MISMATCH`다.
4. Cart 없이(또는 Cart 만료 후) Order를 만들 수 없다.
5. Cart 조회 없이도 유효한 키로 Order를 만들 수 있다.
6. 같은 키로 Order를 두 번 생성해도 Order는 한 개이고 두 번째는 `200`이다.
7. 주문 후 Cart 갱신은 `409 CART_ALREADY_ORDERED`, Cart 조회는 `404`다.
8. 온라인·오프라인·포인트 결제는 각각 `POST /payments`와 `method`로 승인되고, 주문은 `PAID`가 된다.
9. Gateway 거절 시 `422 PAYMENT_DECLINED`와 함께 `FAILED` Payment가 DB에 남고 주문은 `CREATED`다. 이후 새 결제로 승인할 수 있다.
10. 금액 불일치·세부정보 오류·이미 결제된 주문은 Payment를 만들지 않고 거절된다.
11. 금지된 민감 필드는 응답, 엔티티, Redis Cart, 로그에 저장하지 않는다.
