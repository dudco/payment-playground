# 주문·결제 API PRD

> 문서 역할과 갱신 원칙은 [문서 가이드](README.md)를 따른다. 구현 상세는 [기능 스펙](specs/order-payment-api.md), 공유 영속 규칙은 [데이터 모델](data-model.md)을 기준으로 한다.

## 1. 목적

이 문서는 `payment-playground`의 첫 장바구니 초안·주문·결제 API 범위를 정의한다. REST API와 자동 테스트만 제공하며, 실제 PG/VAN 네트워크 통신이나 UI는 범위에 포함하지 않는다.

이 단계의 목표는 **트래픽이 거의 없는 환경에서 올바르게 동작하는 기본 API**다. 동시성·재시도·대사 같은 대용량 트래픽 대응은 의도적으로 넣지 않는다. 대신 이 구현이 막지 않는 문제를 [10. 알려진 한계](#10-알려진-한계)에 기록하고, 이후 단계에서 하나씩 재현한 뒤 해결한다.

고객은 여러 상품을 장바구니 초안(Cart)에 담고, 장바구니 생성에 사용한 멱등키로만 주문을 생성할 수 있다. Cart는 Redis에 TTL과 함께 저장되는 임시 데이터이고, 주문과 결제는 RDB에 영구 저장된다. 다음 세 결제 흐름을 지원한다.

1. 온라인 카드: 앱이 PG 토큰을 받은 뒤 서버에 전달하고 승인한다.
2. 오프라인 카드: POS/키오스크가 이미 수행한 VAN 승인 결과를 서버에 전송하고 주문에 반영한다.
3. 자체 포인트: 오프라인은 바코드, 온라인은 등록된 결제수단 식별자로 포인트를 차감한다.

각 외부 결제 의존성은 개발용 `Fake*Gateway`로 대체한다.

## 2. 용어와 명명 규칙

`Product`, `Item`, `Cart`, `OrderLine`은 서로 다른 의미로 고정한다.

| 용어 | 코드 명명 | 의미 |
| --- | --- | --- |
| 상품 | `Product` | 고객에게 판매하는 카탈로그 단위. 판매명·판매가의 기준이다. |
| 품목 | `Item` | 매입·재고 관리 단위. 하나의 `Product`에 서로 다른 매입가 또는 매입 거래처를 가진 여러 `Item`이 연결될 수 있다. |
| 장바구니 | `Cart` | 주문 전 변경 가능한 주문 초안. Redis에 TTL과 함께 저장되며 멱등키와 `orderLines` 스냅샷을 가진다. |
| 주문 | `Order` | Cart에서 한 번 생성되는 구매 의사 및 결제 대상의 불변 기록. 주문 총액과 주문 상태를 가진다. |
| 주문 라인 | `OrderLine` | 한 주문에서 고객이 선택한 `Product`와 수량·주문 시점 판매가를 기록한 불변 스냅샷이다. |
| 결제 | `Payment` | 특정 `Order`에 대한 한 번의 결제 시도. 승인 또는 실패 결과와 외부 거래 식별자를 기록한다. |

`CartLine` 엔티티·테이블·API 이름은 사용하지 않는다. Cart의 `orderLines`는 별도 도메인 엔티티가 아닌 JSON 스냅샷이며, 주문 생성 시에만 불변 `OrderLine` 엔티티로 복사한다. `OrderItem`도 사용하지 않는다.

이 MVP는 `Product`와 `Item`의 영속 모델을 구현하지 않는다. 이후 재고 할당이 필요해질 때 `Product 1 : N Item` 관계와 `OrderLine`의 품목 할당 정보를 별도 기능으로 추가한다.

## 3. 목표와 비범위

### 목표

- `POST /order/cart`에서 선택적 멱등키로 변경 가능한 주문 초안을 생성·갱신하고, `GET /order/cart`로 선택적으로 조회한다.
- Cart는 Redis에 TTL과 함께 저장하고, 주문되지 않은 Cart는 TTL이 지나면 자동으로 사라진다.
- `POST /order`는 장바구니 생성에 사용한 `Idempotency-Key`가 있을 때만 허용한다.
- Cart의 복수 상품을 불변 `OrderLine`으로 전환한다.
- 주문 라인의 합계와 주문 총액을 서버에서 계산·검증한다.
- 세 결제수단별 요청 데이터를 타입 안전하게 분리한다.
- 승인 결과를 결제 및 주문 상태에 원자적으로 반영하고, 거절된 결제 시도도 기록한다.
- 승인에 필요한 최소 식별자·금액·시각을 저장한다.
- 카드 및 인증 민감정보를 저장하거나 로그에 남기지 않는다.

### 비범위

- 실제 PG SDK, VAN 소켓 통신, 실제 카드 승인
- 결제 취소(전액·부분), 환불
- 카드번호, Track2, PIN, 주민번호, OTC/OTC2, CAVV의 수집·저장·로깅
- 웹뷰/UI, 정산, 분할 결제
- 비동기 승인, 웹훅, 중복 웹훅 제거, outbox, 재시도, DLQ
- 동시 요청 제어(락, 낙관적 버전, 분산 락). [10. 알려진 한계](#10-알려진-한계) 참고
- 포인트 잔액의 영구 원장 및 회원 시스템 연동
- 상품 카탈로그 기반 가격 검증, 쿠폰·할인
- Cart 병합, 고객당 활성 Cart 단일화

## 4. 사용자 흐름

### 4.1 Cart 생성·갱신과 선택적 조회

1. 클라이언트는 고객 식별자와 하나 이상의 `orderLines`를 `POST /order/cart`로 보낸다. `Idempotency-Key`는 선택 사항이다.
2. 키가 없으면 서버가 새 키를 발급하고 Cart를 생성한다.
3. 키가 있으면 다음 순서로 처리한다.
   1. 해당 키로 이미 주문이 생성되었으면 `409 CART_ALREADY_ORDERED`로 거절한다.
   2. 해당 키의 Cart가 있고 `customerId`가 다르면 `409 CART_CUSTOMER_MISMATCH`로 거절한다.
   3. 해당 키의 Cart가 있으면 주문 내용을 최신 요청으로 교체하고 TTL을 다시 설정한다.
   4. Cart가 없으면(처음이거나 TTL 만료) 그 키로 새 Cart를 생성한다.
4. 클라이언트는 필요할 때 같은 `Idempotency-Key`로 `GET /order/cart`를 호출해 초안을 조회한다. 이 호출은 주문 생성의 선행 조건이 아니다.

### 4.2 Cart에서 주문 생성

1. 클라이언트는 `POST /order`에 `Idempotency-Key`를 필수 헤더로 보낸다.
2. 해당 키로 생성된 주문이 이미 있으면 새 주문을 만들지 않고 기존 주문을 반환한다.
3. 없으면 해당 키의 Cart를 읽는다. Cart가 없거나 만료되었으면 `404 CART_NOT_FOUND`다. `GET /order/cart` 호출 여부는 검사하지 않는다.
4. 서버는 Cart의 `orderLines`로 총액을 계산하고, `Order(CREATED)`와 불변 `OrderLine`들을 하나의 DB 트랜잭션에서 생성한다.
5. 커밋 후 Redis의 Cart를 삭제한다. 이후 같은 키의 Cart 갱신은 거절된다.

### 4.3 결제 생성

1. 클라이언트는 `POST /payments`에 대상 `orderId`, 결제수단 `method`, 결제 금액, 수단별 승인 정보를 보낸다.
2. 서버는 주문 상태·금액·수단별 필수값을 검증하고, `method`에 따라 하나의 전용 Fake Gateway를 호출한다.
3. 온라인 카드는 PG 토큰으로 승인하고, 오프라인 카드는 단말의 VAN 승인 결과를 접수하며, 포인트는 바코드 또는 등록 결제수단 참조값으로 차감한다.
4. 승인되면 `Payment(APPROVED)`를 저장하고 주문을 `PAID`로 바꾼다.
5. 거절되면 `Payment(FAILED)`를 저장하고 주문은 `CREATED`로 남는다. 클라이언트는 새 결제를 다시 시도할 수 있다.

오프라인 Gateway는 VAN 통신을 새로 실행하지 않는다. 단말이 이미 받은 승인 결과를 안전한 형태로 접수하는 책임만 가진다.

## 5. 상태 전이와 멱등성

Fake Gateway는 동기로 즉시 결과를 반환하므로, 이 단계에서는 결과가 정해지기 전의 중간 상태를 저장하지 않는다. 비동기 승인이 도입되면 `PENDING` 같은 상태를 별도 스펙과 ADR로 추가한다.

```text
Cart:    (Redis, 상태 없음) 생성 → 갱신* → 주문 생성 시 삭제 또는 TTL 만료
Order:   CREATED -> PAID
Payment: APPROVED | FAILED   (생성 시점에 결과로 결정되는 종단 상태)
```

- `Order.CREATED`: 결제 가능한 상태. 거절된 결제가 있어도 이 상태로 남는다.
- `Order.PAID`: 승인된 결제가 하나 있는 상태. 새 결제를 만들 수 없다.
- `Payment.APPROVED`: Gateway가 승인한 결제.
- `Payment.FAILED`: Gateway가 거절한 결제 시도. 요청 검증 실패(금액 불일치, 필수값 누락, 이미 결제된 주문)는 Gateway 호출 전에 거절하며 Payment를 만들지 않는다.

멱등성 규칙:

- 동일 `Idempotency-Key`의 `POST /order/cart`는 주문 전까지 Cart 내용을 최신 요청으로 교체한다.
- 해당 키로 주문이 생성된 뒤에는 Cart를 갱신하거나 새 주문을 만들 수 없다.
- 동일 키의 `POST /order` 재호출은 기존 주문을 반환한다.
- 결제 승인 금액은 주문 총액과 같아야 한다.

## 6. Gateway 설계

결제수단별 입력이 서로 다르므로 하나의 nullable 요청 DTO를 쓰지 않고, 전용 인터페이스를 사용한다([ADR-0001](adr/0001-payment-gateway-by-method.md)).

```kotlin
interface OnlineCardPaymentGateway {
    fun approve(command: OnlineCardApprovalCommand): PaymentApprovalResult
}

interface OfflineCardPaymentGateway {
    fun recordApproval(command: OfflineCardApprovalCommand): PaymentApprovalResult
}

interface PointPaymentGateway {
    fun approve(command: PointApprovalCommand): PaymentApprovalResult
}
```

개발 환경 구현체는 `FakeOnlineCardPaymentGateway`, `FakeOfflineCardPaymentGateway`, `FakePointPaymentGateway`다. 각 Gateway는 승인 성공 여부, 외부 거래 식별자, 승인번호, 승인 시각, 결제수단, 승인 금액, 거절 코드 및 비민감 메타데이터만 포함한 `PaymentApprovalResult`를 반환한다. 거절도 예외가 아닌 결과값으로 반환한다.

## 7. REST API 계약

### Cart 생성 또는 갱신

`POST /order/cart`

요청 헤더 `Idempotency-Key`는 선택 사항이다. 없으면 서버가 키를 발급해 응답 헤더와 본문에 반환한다.

```json
{
  "customerId": "customer-001",
  "orderLines": [
    { "productId": "americano", "productName": "아메리카노", "quantity": 2, "unitPrice": 4500 },
    { "productId": "cake", "productName": "케이크", "quantity": 1, "unitPrice": 6000 }
  ]
}
```

응답: `201 Created` (새 Cart) 또는 `200 OK` (기존 Cart 갱신)

```json
{
  "idempotencyKey": "cart-key-001",
  "customerId": "customer-001",
  "totalAmount": 15000,
  "expiresAt": "2026-10-09T11:20:30+09:00",
  "orderLines": [
    { "productId": "americano", "productName": "아메리카노", "quantity": 2, "unitPrice": 4500, "lineAmount": 9000 },
    { "productId": "cake", "productName": "케이크", "quantity": 1, "unitPrice": 6000, "lineAmount": 6000 }
  ]
}
```

### Cart 조회 (선택)

`GET /order/cart`

요청 헤더 `Idempotency-Key`가 필수다. 주문 생성 전에 호출할 필요는 없다. 응답 본문은 생성·갱신 응답과 같다(`200 OK`). Cart가 없거나 만료되었거나 이미 주문으로 전환되었으면 `404 CART_NOT_FOUND`다.

### 주문 생성

`POST /order`

요청 헤더 `Idempotency-Key`가 필수다. 해당 키의 Cart가 있거나, 해당 키로 이미 주문이 생성된 경우에만 성공한다.

응답: 최초 생성은 `201 Created`, 같은 키로 재호출하면 `200 OK`와 기존 주문을 반환한다.

```json
{
  "orderId": "uuid",
  "idempotencyKey": "cart-key-001",
  "status": "CREATED",
  "totalAmount": 15000,
  "orderLines": [
    { "productId": "americano", "productName": "아메리카노", "quantity": 2, "unitPrice": 4500, "lineAmount": 9000 },
    { "productId": "cake", "productName": "케이크", "quantity": 1, "unitPrice": 6000, "lineAmount": 6000 }
  ]
}
```

### 결제 생성

`POST /payments`

`method`는 필수이며 `ONLINE_CARD`, `OFFLINE_CARD`, `POINT` 중 하나다. `paymentDetails`에는 `method`에 맞는 필드만 허용한다.

온라인 카드:

```json
{
  "orderId": "uuid",
  "method": "ONLINE_CARD",
  "amount": 15000,
  "paymentDetails": { "paymentToken": "fake-online-token" }
}
```

오프라인 카드:

```json
{
  "orderId": "uuid",
  "method": "OFFLINE_CARD",
  "amount": 15000,
  "paymentDetails": {
    "terminalId": "CAT-001",
    "vanTransactionId": "van-transaction-001",
    "approvalNumber": "12345678",
    "approvedAt": "2026-09-13T10:20:30+09:00",
    "responseCode": "0000",
    "vatAmount": 1364,
    "issuerCode": "01",
    "acquirerCode": "01",
    "maskedCardNumber": "1234-****-****-5678"
  }
}
```

오프라인 카드의 `amount`는 단말이 VAN에서 승인받은 금액이다. 별도 VAN 금액 필드는 두지 않는다.

자체 포인트:

```json
{
  "orderId": "uuid",
  "method": "POINT",
  "amount": 15000,
  "paymentDetails": {
    "channel": "OFFLINE",
    "barcode": "point-barcode-reference"
  }
}
```

`POINT`의 온라인 요청은 `paymentDetails.channel`을 `ONLINE`으로, `barcode` 대신 `pointPaymentMethodId`로 보낸다.

응답: 승인 성공은 `201 Created`.

```json
{
  "paymentId": "uuid",
  "orderId": "uuid",
  "method": "ONLINE_CARD",
  "status": "APPROVED",
  "amount": 15000,
  "approvedAt": "2026-10-09T10:20:30+09:00"
}
```

Gateway 거절은 `422 PAYMENT_DECLINED`이며 오류 본문에 기록된 `paymentId`를 포함한다.

## 8. 공통 오류

```json
{
  "code": "CART_NOT_FOUND",
  "message": "해당 멱등키로 생성된 장바구니가 없습니다."
}
```

- `400 Bad Request`: 필수값 누락, 잘못된 형식, 0 이하 수량/금액
- `404 Not Found`: 해당 멱등키의 Cart(만료 포함) 또는 주문이 존재하지 않음
- `409 Conflict`: 이미 주문된 키의 Cart 갱신, Cart의 고객 변경, 이미 결제된 주문의 결제
- `422 Unprocessable Entity`: Gateway 승인 거절, 결제수단별 필수값 누락 또는 금액 불일치

## 9. 보안 및 개인정보 정책

저장 가능: 주문·결제 식별자, 주문/승인 금액, 부가세, VAN 거래 고유번호, 승인번호, 승인 시각, 응답코드, 카드사 코드, 단말 식별자, 이미 마스킹된 카드 식별값.

저장 및 로그 금지: 원문 카드번호, Track2 원문, PIN, 주민번호, OTC, OTC2, XID, ECI, CAVV, 서명 바이너리, 원본 VAN 전문, PG 토큰, 포인트 바코드 원문.

`metadata`는 JSON 형태의 비민감 부가 정보에만 쓴다. 요청 본문 전체나 원본 VAN 응답을 그대로 저장하는 용도로 사용하지 않는다. Redis의 Cart에도 같은 규칙을 적용한다.

## 10. 알려진 한계

이 단계는 요청이 한 번에 하나씩 들어온다고 가정한다. 아래 항목은 의도적으로 막지 않으며, 이후 단계의 재현 대상이다. 해결할 때는 해당 항목을 재현하는 테스트를 먼저 작성한다.

### 동시성

- **같은 주문의 동시 결제 → 이중 승인.** `POST /payments`는 "주문이 `CREATED`인지 확인 → Gateway 승인 → `PAID` 저장" 순서로 동작하며 락이나 버전 검사가 없다. 같은 주문에 두 요청이 동시에 들어오면 둘 다 `CREATED`를 읽고 둘 다 승인되어 `APPROVED` 결제가 두 건 생길 수 있다. 이 프로젝트의 첫 번째 재현 대상이다.
- **같은 키의 동시 주문 생성 → 500.** `POST /order`는 "주문 없음 확인 → insert" 순서로 동작한다. 동시에 두 요청이 오면 둘 다 insert를 시도하고, `orders.idempotency_key` unique 제약 때문에 한쪽이 DB 오류(500)로 끝난다. 중복 주문은 생기지 않지만 응답이 정상 재시도 응답(`200`)이 아니다.
- **같은 키의 동시 Cart 갱신 → 마지막 쓰기 승리.** Cart 갱신은 Redis 읽기 → 쓰기이며 원자적이지 않아, 동시 요청 중 하나의 변경이 조용히 사라진다.
- **주문 생성과 Cart 갱신의 경합.** `POST /order/cart`가 "주문 없음"을 확인한 직후 주문이 커밋되면, 주문 후에도 Cart가 다시 쓰일 수 있다. 주문은 이미 불변이므로 주문 내용에는 영향이 없고, 남은 Cart는 TTL로 사라진다.
- **SQLite 단일 writer.** 쓰기 요청이 몰리면 `SQLITE_BUSY`로 실패하거나 대기한다. 부하 실험 전에 DB 교체가 필요할 수 있다.

### 재시도와 외부 연동

- **결제 요청에 멱등키가 없다.** 응답을 받지 못한 클라이언트가 같은 결제를 재시도하면 서버는 재시도인지 새 시도인지 구분하지 못한다. 첫 요청이 승인되었으면 재시도는 `409 ORDER_ALREADY_PAID`로 거절되지만, 클라이언트는 첫 결제의 결과를 다시 받을 방법이 없다.
- **Gateway 호출이 DB 트랜잭션 안에서 실행된다.** Fake Gateway는 즉시 반환하므로 문제가 없지만, 실제 외부 호출로 바뀌면 응답을 기다리는 동안 DB 커넥션을 붙잡고 있게 된다. 타임아웃이나 응답 유실 시 외부 승인 결과와 DB 상태가 어긋날 수 있다.
- **오프라인 카드는 서버가 거절해도 실제 승인이 남는다.** 단말은 이미 VAN 승인을 받은 상태로 결과를 보낸다. 서버가 금액 불일치 등으로 거절하면 카드 승인은 살아 있는데 주문은 결제되지 않은 상태가 된다. 취소 API와 대사가 없으므로 이 불일치를 바로잡을 수단이 없다.
- **DB 커밋 후 Redis Cart 삭제 실패.** 삭제가 실패하면 Cart가 TTL까지 남는다. 주문 여부는 DB 기준이라 정합성에는 영향이 없다.

### 데이터 신뢰

- **판매가를 클라이언트 입력으로 신뢰한다.** 상품 카탈로그가 없으므로 `unitPrice`와 `productName`을 검증하지 않는다. 서버는 합계 계산만 책임진다.
- **Cart 만료 후 주문 불가.** TTL이 지나면 같은 키로 주문할 수 없고(`404`), 같은 키로 Cart를 다시 만들면 빈 상태에서 새로 시작한다.

## 11. 수용 조건

- 키 없는 `POST /order/cart`가 멱등키를 발급하고, 동일 키의 `POST /order/cart`가 Cart 내용을 갱신하고 TTL을 다시 설정하는지 자동 테스트한다.
- 동일 키로 다른 `customerId`의 Cart 갱신이 `409`로 거절되는지 자동 테스트한다.
- `POST /order`가 사전 Cart 없이 거절되고, `GET /order/cart` 호출 없이도 유효한 키로 주문을 생성하는지 자동 테스트한다.
- Cart의 복수 상품이 불변 주문 라인으로 전환되고, 동일 키의 재호출이 중복 주문을 만들지 않으며, 주문 뒤 Cart 갱신이 거절되고 Cart 조회가 `404`인지 자동 테스트한다.
- 온라인 카드, 오프라인 카드, 포인트 결제가 각각 올바른 Fake Gateway를 통해 승인된다.
- 각 승인 성공 시 결제는 `APPROVED`, 주문은 `PAID`가 된다.
- Gateway 거절 시 `FAILED` 결제가 저장되고 주문은 `CREATED`로 남으며, 이후 새 결제로 승인될 수 있다.
- VAN 응답코드가 성공이 아니면 오프라인 결제가 거절되고, 결제 금액이 주문 총액과 다르면 모든 결제수단에서 Payment 생성 없이 거절된다.
- 민감 필드가 엔티티, Redis Cart, API 응답, 애플리케이션 로그에 나타나지 않는다.
