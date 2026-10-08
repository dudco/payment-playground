# ADR-0003: Cart 초안을 Redis에 TTL과 함께 저장

- 상태: 승인됨
- 일자: 2026-10-09
- 관계: [ADR-0002](0002-cart-checkout-creates-order.md)의 Cart 저장소·`ORDER_CREATED` 상태 결정을 대체한다. "멱등키 기반 Cart에서만 주문을 생성한다"는 결정은 유지한다.

## 맥락

Cart는 주문 전에만 의미가 있는 짧은 수명의 초안이다. 주문으로 전환되지 않은 Cart는 일정 시간이 지나면 사라져야 하지만, 관계형 DB에는 행 단위 TTL이 없다. 그래서 `expires_at` 컬럼, 조회할 때 만료 행을 거르는 필터, 주기적 삭제 작업을 직접 만들어야 한다.

반면 주문(`orders`)은 영구 기록이며 결제와 함께 트랜잭션으로 다뤄야 한다.

## 결정

- Cart는 Redis에 `cart:{idempotencyKey}` 키로 JSON 하나를 저장하고 TTL을 건다. 생성·갱신할 때마다 TTL을 다시 설정한다.
- Cart에는 상태 필드를 두지 않는다. "이 키로 이미 주문이 생성되었는가"의 기준은 DB의 `orders.idempotency_key`(unique)다.
- `POST /order`는 먼저 DB에서 같은 키의 주문을 찾는다. 있으면 그 주문을 반환하고, 없으면 Redis Cart를 읽어 `orders`·`order_lines`를 하나의 DB 트랜잭션으로 만든다. 커밋 후 Redis Cart를 삭제한다.
- `POST /order/cart`는 같은 키의 주문이 DB에 있으면 `409 CART_ALREADY_ORDERED`로 거절한다.

## 고려한 대안

### 대안 1: Cart를 SQLite(RDB)에 두고 상태로 관리 (ADR-0002 원안)

- 장점: Cart 전이와 주문 생성을 하나의 트랜잭션으로 처리할 수 있다.
- 단점: 만료를 컬럼·필터·배치 삭제로 직접 구현해야 한다.

채택하지 않는다.

### 대안 2: Redis Cart에 `ORDER_CREATED` 상태를 기록

- 장점: Cart만 조회해도 주문 여부를 알 수 있다.
- 단점: DB 커밋과 Redis 갱신은 원자적이지 않다. 두 저장소가 "주문됨"을 따로 기록하면 서로 어긋날 수 있다. TTL이 만료되면 상태도 함께 사라진다.

채택하지 않는다.

## 결과

- Cart 만료는 Redis TTL이 처리하며, 별도 정리 작업이 없다.
- 주문 여부의 기준이 DB 한 곳이므로 DB 커밋 후 Redis 삭제가 실패해도 정합성은 깨지지 않는다. 남은 Cart는 TTL로 사라진다.
- Cart와 주문 사이에 DB FK는 없고, 멱등키로만 논리적으로 연결한다.
- 주문 후 `GET /order/cart`는 Cart가 삭제되어 `404`를 반환한다.
- 애플리케이션 실행과 테스트에 Redis가 필요하다.
