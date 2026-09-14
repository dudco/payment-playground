# 주문·결제 API PRD

## 1. 목적

이 문서는 `payment-playground`의 첫 장바구니 초안·주문·결제 API 범위를 정의한다. REST API와 자동 테스트만 제공하며, 실제 PG/VAN 네트워크 통신이나 UI는 범위에 포함하지 않는다.

고객은 여러 상품을 장바구니 초안에 담고, 장바구니 생성에 사용한 멱등키로만 주문을 생성할 수 있다. 장바구니 조회는 선택 사항이며, 주문은 여러 주문 라인을 포함한다. 다음 세 결제 흐름을 지원한다.

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
| 장바구니 | `Cart` | 주문 전 변경 가능한 주문 초안. 멱등키와 `orderLines` JSON 스냅샷을 가진다. |
| 주문 라인 | `OrderLine` | 한 주문에서 고객이 선택한 `Product`와 수량·주문 시점 판매가를 기록한 불변 스냅샷이다. |

`CartLine` 엔티티·테이블·API 이름은 사용하지 않는다. Cart의 `orderLines`는 별도 도메인 엔티티가 아닌 JSON 스냅샷이며, 주문 생성 시에만 불변 `OrderLine` 엔티티로 복사한다. `OrderItem`도 사용하지 않는다.

이 MVP는 `Product`와 `Item`의 영속 모델을 구현하지 않는다. 이후 재고 할당이 필요해질 때 `Product 1 : N Item` 관계와 `OrderLine`의 품목 할당 정보를 별도 기능으로 추가한다.

## 3. 목표와 비범위

### 목표

- `POST /order/cart`에서 선택적 멱등키로 변경 가능한 주문 초안을 생성·갱신하고, `GET /order/cart`로 선택적으로 조회한다.
- `POST /order`는 장바구니 생성에 사용한 `Idempotency-Key`가 있을 때만 허용한다.
- Cart의 복수 상품을 불변 `OrderLine`으로 전환한다.
- 주문 라인의 합계와 주문 총액을 서버에서 계산·검증한다.
- 세 결제수단별 요청 데이터를 타입 안전하게 분리한다.
- 승인 결과를 결제 및 주문 상태에 원자적으로 반영한다.
- 승인과 취소에 필요한 최소 식별자·금액·시각을 저장한다.
- 카드 및 인증 민감정보를 저장하거나 로그에 남기지 않는다.

### 비범위

- 실제 PG SDK, VAN 소켓 통신, 실제 카드 승인
- 카드번호, Track2, PIN, 주민번호, OTC/OTC2, CAVV의 수집·저장·로깅
- 웹뷰/UI, 정산, 환불, 부분 취소, 분할 결제
- 중복 웹훅 제거, outbox, 재시도, DLQ
- 포인트 잔액의 영구 원장 및 회원 시스템 연동
- 쿠폰·할인, Cart 만료·병합, 고객당 활성 Cart 단일화

## 4. 사용자 흐름

### 4.1 Cart 생성·갱신과 선택적 조회

1. 클라이언트는 고객 식별자와 하나 이상의 `orderLines`를 `POST /order/cart`로 보낸다. `Idempotency-Key`는 선택 사항이다.
2. 키가 없으면 서버가 새 키를 발급하고 `Cart(ACTIVE)`를 생성한다.
3. 키가 있으면 해당 키의 활성 Cart를 생성하거나, 이미 존재하는 활성 Cart의 주문 내용을 최신 요청으로 교체한다. 동일 키에서 주문 내용은 변경 가능하다.
4. 클라이언트는 필요할 때 같은 `Idempotency-Key`로 `GET /order/cart`를 호출해 초안을 조회한다. 이 호출은 주문 생성의 선행 조건이 아니다.

### 4.2 Cart에서 주문 생성

1. 클라이언트는 `POST /order`에 `Idempotency-Key`를 필수 헤더로 보낸다.
2. 서버는 해당 키의 `ACTIVE` Cart가 있을 때만 주문 생성을 허용한다. `GET /order/cart` 호출 여부는 검사하지 않는다.
3. 서버는 Cart의 `orderLines`를 읽어 총액을 계산하고, `Order(CREATED)`와 불변 `OrderLine` 스냅샷들을 생성한다.
4. 주문과 Cart 상태 전이는 하나의 트랜잭션에서 처리하며 Cart를 `ORDER_CREATED`로 전이한다.
5. 동일 키로 `POST /order`를 재호출하면 새 주문을 만들지 않고 기존 주문을 반환한다. `ORDER_CREATED` Cart의 주문 내용은 더 이상 변경할 수 없다.

### 4.3 결제 생성

1. 클라이언트는 `POST /payments`에 대상 `orderId`, 결제수단 `method`, 수단별 승인 정보를 보낸다.
2. 서버는 `method`에 따라 하나의 전용 Fake Gateway를 선택한다.
3. 온라인 카드는 PG 토큰으로 승인하고, 오프라인 카드는 단말의 VAN 승인 결과를 접수하며, 포인트는 바코드 또는 등록 결제수단 참조값으로 차감한다.
4. 성공하면 `Payment`는 `APPROVED`, 주문은 `PAID`가 된다.

오프라인 Gateway는 VAN 통신을 새로 실행하지 않는다. 단말이 이미 받은 승인 결과를 안전한 형태로 접수하는 책임만 가진다.

## 5. 상태 전이와 멱등성

### Cart 상태

- `ACTIVE`: 생성·갱신·조회 및 주문 전환 가능한 상태
- `ORDER_CREATED`: 주문으로 전환된 상태. 주문 내용 갱신 및 새 주문 생성 불가

### 주문과 결제 상태

- `Order`: `CREATED` → `PAYMENT_PENDING` → `PAID` → `CANCELLED`
- `Payment`: `PENDING` → `APPROVED` → `CANCELLED`, 또는 `PENDING` → `FAILED`

```text
Cart:    ACTIVE -> ORDER_CREATED
Order:   CREATED -> PAYMENT_PENDING -> PAID -> CANCELLED
Payment: PENDING -> APPROVED -> CANCELLED
                 -> FAILED
```

- 동일 `Idempotency-Key`의 `POST /order/cart`는 `ACTIVE` Cart 주문 내용을 최신 요청으로 교체한다.
- `ORDER_CREATED` Cart는 갱신하거나 다시 주문으로 전환할 수 없다.
- 동일 키의 `POST /order` 재호출은 기존 주문을 반환한다.
- `PAID` 주문에는 새 결제를 생성할 수 없다.
- 결제 승인 금액은 주문 총액과 같아야 한다.
- 취소는 `APPROVED` 결제만 가능하고, 이 MVP에서는 전액 취소만 허용한다.
- 승인 실패는 주문을 `CREATED`로 되돌리며, 해당 결제만 `FAILED`로 기록한다.

## 6. Gateway 설계

결제수단별 입력이 서로 다르므로 하나의 nullable 요청 DTO를 쓰지 않고, 전용 인터페이스를 사용한다.

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

개발 환경 구현체는 `FakeOnlineCardPaymentGateway`, `FakeOfflineCardPaymentGateway`, `FakePointPaymentGateway`다. 각 Gateway는 승인 성공 여부, 외부 거래 식별자, 승인번호, 승인 시각, 결제수단, 승인 금액 및 비민감 메타데이터만 포함한 `PaymentApprovalResult`를 반환한다.

## 7. REST API 계약

### Cart 생성 또는 갱신

`POST /order/cart`

요청 헤더 `Idempotency-Key`는 선택 사항이다. 없으면 서버가 키를 발급해 응답 헤더에 반환한다. 있으면 해당 키의 `ACTIVE` Cart를 생성하거나 최신 주문 내용으로 갱신한다.

```json
{
  "customerId": "customer-001",
  "orderLines": [
    { "productId": "americano", "productName": "아메리카노", "quantity": 2, "unitPrice": 4500 },
    { "productId": "cake", "productName": "케이크", "quantity": 1, "unitPrice": 6000 }
  ]
}
```

응답: `201 Created` (새 Cart) 또는 `200 OK` (기존 `ACTIVE` Cart 갱신)

```json
{
  "idempotencyKey": "cart-key-001",
  "status": "ACTIVE",
  "totalAmount": 15000,
  "orderLines": [
    { "productId": "americano", "quantity": 2, "unitPrice": 4500, "lineAmount": 9000 },
    { "productId": "cake", "quantity": 1, "unitPrice": 6000, "lineAmount": 6000 }
  ]
}
```

### Cart 조회 (선택)

`GET /order/cart`

요청 헤더 `Idempotency-Key`가 필수다. 주문 생성 전에 호출할 필요는 없다.

응답: `200 OK`

```json
{
  "idempotencyKey": "cart-key-001",
  "customerId": "customer-001",
  "status": "ACTIVE",
  "totalAmount": 15000,
  "orderLines": [
    { "productId": "americano", "productName": "아메리카노", "quantity": 2, "unitPrice": 4500, "lineAmount": 9000 },
    { "productId": "cake", "productName": "케이크", "quantity": 1, "unitPrice": 6000, "lineAmount": 6000 }
  ]
}
```

### 주문 생성

`POST /order`

요청 헤더 `Idempotency-Key`가 필수다. 해당 키로 `POST /order/cart`가 먼저 성공한 경우에만 주문을 생성한다.

응답: 최초 생성은 `201 Created`, 같은 키로 재호출하면 `200 OK`와 기존 주문을 반환한다.

```json
{
  "orderId": "uuid",
  "idempotencyKey": "cart-key-001",
  "status": "CREATED",
  "totalAmount": 15000,
  "orderLines": [
    { "productId": "americano", "quantity": 2, "unitPrice": 4500, "lineAmount": 9000 },
    { "productId": "cake", "quantity": 1, "unitPrice": 6000, "lineAmount": 6000 }
  ]
}
```

### 결제 생성

`POST /payments`

`method`는 필수이며 `ONLINE_CARD`, `OFFLINE_CARD`, `POINT` 중 하나다. `paymentDetails`에는 `method`에 맞는 필드만 허용한다. 서버는 `method`에 따라 하나의 전용 Fake Gateway를 선택한다.

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

응답: `201 Created` (승인 성공) 또는 `422 Unprocessable Entity` (승인 거절·금액 검증 실패)

### 결제 취소

`POST /payments/{paymentId}/cancellations`

```json
{ "reason": "CUSTOMER_REQUEST" }
```

응답: `200 OK`

## 8. 공통 오류

```json
{
  "code": "CART_NOT_FOUND",
  "message": "해당 멱등키로 생성된 장바구니가 없습니다."
}
```

- `400 Bad Request`: 필수값 누락, 잘못된 형식, 0 이하 수량/금액
- `404 Not Found`: 해당 멱등키의 Cart, 주문 또는 결제가 존재하지 않음
- `409 Conflict`: `ORDER_CREATED` Cart의 주문 내용 갱신, 현재 상태에서 허용되지 않는 결제/취소
- `422 Unprocessable Entity`: Gateway 승인 거절, 결제수단별 필수값 누락 또는 금액 불일치

## 9. 보안 및 개인정보 정책

저장 가능: 주문·결제 식별자, 주문/승인/취소 금액, 부가세, VAN 거래 고유번호, 승인번호, 승인 시각, 응답코드, 카드사 코드, 단말 식별자, 이미 마스킹된 카드 식별값.

저장 및 로그 금지: 원문 카드번호, Track2 원문, PIN, 주민번호, OTC, OTC2, XID, ECI, CAVV, 서명 바이너리, 원본 VAN 전문, PG 토큰, 포인트 바코드 원문.

`metadata`는 JSON 형태의 비민감 부가 정보에만 쓴다. 요청 본문 전체나 원본 VAN 응답을 그대로 저장하는 용도로 사용하지 않는다.

## 10. 수용 조건

- 키 없는 `POST /order/cart`가 멱등키를 발급하고, 동일 키의 `POST /order/cart`가 `ACTIVE` Cart 주문 내용을 갱신하는지 자동 테스트한다.
- `POST /order`가 사전 Cart 없이 거절되고, `GET /order/cart` 호출 없이도 유효한 키로 주문을 생성하는지 자동 테스트한다.
- Cart의 복수 상품이 불변 주문 라인으로 전환되고, 동일 키의 재호출이 중복 주문을 만들지 않으며 주문 뒤 Cart 갱신이 거절되는지 자동 테스트한다.
- 온라인 카드, 오프라인 카드, 포인트 결제가 각각 올바른 Fake Gateway를 통해 승인된다.
- 각 승인 성공 시 결제는 `APPROVED`, 주문은 `PAID`가 된다.
- VAN의 성공 응답코드가 아니거나 승인금액이 주문과 다르면 오프라인 결제가 거절된다.
- 승인 결제를 취소하면 결제는 `CANCELLED`, 주문은 `CANCELLED`가 된다.
- 민감 필드가 엔티티, API 응답, 애플리케이션 로그에 나타나지 않는다.
