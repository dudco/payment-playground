# 주문·결제 API PRD

## 1. 목적

이 문서는 `payment-playground`의 첫 주문·결제 API 범위를 정의한다. REST API와 자동 테스트만 제공하며, 실제 PG/VAN 네트워크 통신이나 UI는 범위에 포함하지 않는다.

주문은 여러 상품 라인을 포함할 수 있고, 다음 세 결제 흐름을 지원한다.

1. 온라인 카드: 앱이 PG 토큰을 받은 뒤 서버에 전달하고 승인한다.
2. 오프라인 카드: POS/키오스크가 이미 수행한 VAN 승인 결과를 서버에 전송하고 주문에 반영한다.
3. 자체 포인트: 오프라인은 바코드, 온라인은 등록된 결제수단 식별자로 포인트를 차감한다.

각 외부 결제 의존성은 개발용 `Fake*Gateway`로 대체한다.

## 2. 목표와 비범위

### 목표

- 한 주문에 하나 이상의 상품(`OrderItem`)을 생성한다.
- 상품 라인의 합계와 요청 주문 총액을 서버에서 검증한다.
- 세 결제수단별 요청 데이터를 타입 안전하게 분리한다.
- 승인 결과를 결제 및 주문 상태에 원자적으로 반영한다.
- 승인과 취소에 필요한 최소 식별자·금액·시각을 저장한다.
- 카드 및 인증 민감정보를 저장하거나 로그에 남기지 않는다.

### 비범위

- 실제 PG SDK, VAN 소켓 통신, 실제 카드 승인
- 카드번호, Track2, PIN, 주민번호, OTC/OTC2, CAVV의 수집·저장·로깅
- 웹뷰/UI, 정산, 환불, 부분 취소, 분할 결제
- idempotency, 중복 웹훅 제거, outbox, 재시도, DLQ
- 포인트 잔액의 영구 원장 및 회원 시스템 연동

## 3. 사용자 흐름

### 3.1 주문 생성

1. 클라이언트가 고객 식별자와 한 개 이상의 상품 라인을 보낸다.
2. 서버는 `quantity × unitPrice`의 합이 `totalAmount`와 같은지 검증한다.
3. 서버는 `Order(CREATED)`와 `OrderItem`들을 생성하고 주문 식별자 및 금액을 반환한다.

### 3.2 온라인 카드

1. 클라이언트가 주문에 대한 온라인 카드 결제를 생성한다.
2. 앱의 웹뷰/PG가 발급한 `paymentToken`을 서버에 전달한다.
3. `FakeOnlineCardPaymentGateway`가 토큰 기반 승인을 수행한다.
4. 성공하면 `Payment`는 `APPROVED`, 주문은 `PAID`가 된다.

### 3.3 오프라인 카드

1. POS/키오스크는 VAN 승인 완료 뒤 결과를 서버에 전송한다.
2. 클라이언트는 승인번호, VAN 거래 고유번호, 응답코드, 승인시각, 금액·부가세, 카드사 코드를 보낸다.
3. `FakeOfflineCardPaymentGateway`는 성공 응답코드와 주문 금액 일치를 검증하고 승인 결과를 반영한다.
4. 성공하면 `Payment`는 `APPROVED`, 주문은 `PAID`가 된다.

오프라인 Gateway는 VAN 통신을 새로 실행하지 않는다. 단말이 이미 받은 승인 결과를 안전한 형태로 접수하는 책임만 가진다.

### 3.4 자체 포인트

1. 오프라인은 `barcode`, 온라인은 `pointPaymentMethodId`로 결제를 요청한다.
2. `FakePointPaymentGateway`가 참조값과 결제금액을 검증하고 차감 결과를 만든다.
3. 성공하면 `Payment`는 `APPROVED`, 주문은 `PAID`가 된다.

## 4. 도메인 및 상태 전이

### 주문 상태

- `CREATED`: 주문 생성 완료, 결제 전
- `PAYMENT_PENDING`: 결제 레코드 생성 후 승인 처리 중
- `PAID`: 하나의 결제가 승인되어 주문 금액 전액을 결제함
- `CANCELLED`: 승인 결제가 전액 취소됨

### 결제 상태

- `PENDING`: 승인 결과를 아직 받지 않음
- `APPROVED`: 승인 완료
- `FAILED`: 승인 거절 또는 유효성 검증 실패
- `CANCELLED`: 승인 건 취소 완료

### 전이 규칙

```text
Order:   CREATED -> PAYMENT_PENDING -> PAID -> CANCELLED
Payment: PENDING -> APPROVED -> CANCELLED
                 -> FAILED
```

- `PAID` 주문에는 새 결제를 생성할 수 없다.
- 결제 승인 금액은 주문 총액과 같아야 한다.
- 취소는 `APPROVED` 결제만 가능하고, 이 MVP에서는 전액 취소만 허용한다.
- 승인 실패는 주문을 `CREATED`로 되돌리며, 해당 결제만 `FAILED`로 기록한다.

## 5. Gateway 설계

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

개발 환경 구현체는 다음과 같다.

- `FakeOnlineCardPaymentGateway`
- `FakeOfflineCardPaymentGateway`
- `FakePointPaymentGateway`

각 Gateway는 공통 결과인 `PaymentApprovalResult`를 반환한다. 결과에는 승인 성공 여부, 외부 거래 식별자, 승인번호, 승인 시각, 결제수단, 승인 금액 및 필요한 비민감 메타데이터만 포함한다.

## 6. REST API 계약

### 주문 생성

`POST /orders`

```json
{
  "customerId": "customer-001",
  "items": [
    { "productId": "americano", "productName": "아메리카노", "quantity": 2, "unitPrice": 4500 },
    { "productId": "cake", "productName": "케이크", "quantity": 1, "unitPrice": 6000 }
  ],
  "totalAmount": 15000
}
```

응답: `201 Created`

```json
{
  "orderId": "uuid",
  "status": "CREATED",
  "totalAmount": 15000,
  "items": [
    { "productId": "americano", "quantity": 2, "unitPrice": 4500, "lineAmount": 9000 },
    { "productId": "cake", "quantity": 1, "unitPrice": 6000, "lineAmount": 6000 }
  ]
}
```

### 온라인 카드 승인

`POST /orders/{orderId}/payments/online-card`

```json
{
  "paymentToken": "fake-online-token",
  "amount": 15000
}
```

응답: `201 Created` (승인 성공) 또는 `422 Unprocessable Entity` (승인 거절)

### 오프라인 카드 승인 결과 접수

`POST /orders/{orderId}/payments/offline-card`

```json
{
  "terminalId": "CAT-001",
  "vanTransactionId": "van-transaction-001",
  "approvalNumber": "12345678",
  "approvedAt": "2026-09-13T10:20:30+09:00",
  "responseCode": "0000",
  "amount": 15000,
  "vatAmount": 1364,
  "issuerCode": "01",
  "acquirerCode": "01",
  "maskedCardNumber": "1234-****-****-5678"
}
```

응답: `201 Created` (승인 성공) 또는 `422 Unprocessable Entity` (응답코드·금액 검증 실패)

### 자체 포인트 승인

`POST /orders/{orderId}/payments/points`

오프라인 바코드:

```json
{
  "channel": "OFFLINE",
  "barcode": "point-barcode-reference",
  "amount": 15000
}
```

온라인 등록 결제수단:

```json
{
  "channel": "ONLINE",
  "pointPaymentMethodId": "point-method-001",
  "amount": 15000
}
```

응답: `201 Created` (승인 성공) 또는 `422 Unprocessable Entity` (잔액 부족·참조값 검증 실패)

### 결제 취소

`POST /payments/{paymentId}/cancellations`

```json
{
  "reason": "CUSTOMER_REQUEST"
}
```

응답: `200 OK`

```json
{
  "paymentId": "uuid",
  "status": "CANCELLED",
  "cancelledAt": "2026-09-13T10:25:00+09:00"
}
```

### 공통 오류

```json
{
  "code": "ORDER_AMOUNT_MISMATCH",
  "message": "상품 라인 합계와 주문 총액이 일치하지 않습니다."
}
```

- `400 Bad Request`: 필수값 누락, 잘못된 형식, 0 이하 수량/금액
- `404 Not Found`: 존재하지 않는 주문 또는 결제
- `409 Conflict`: 현재 상태에서 허용되지 않는 결제/취소
- `422 Unprocessable Entity`: Gateway 승인 거절 또는 금액 불일치

## 7. 보안 및 개인정보 정책

### 저장 가능

- 주문·결제 식별자, 주문/승인/취소 금액, 부가세
- VAN 거래 고유번호, 승인번호, 승인 시각, 응답코드
- 발급사·매입사 코드, 단말 식별자
- 이미 마스킹된 카드 식별값

### 저장 및 로그 금지

- 원문 카드번호, Track2 원문, PIN, 주민번호
- OTC, OTC2, XID, ECI, CAVV, 서명 바이너리, 원본 VAN 전문
- PG 토큰과 포인트 바코드 원문은 로그에 남기지 않으며 저장이 필요한 경우에도 암호화/해시 정책이 도입되기 전까지 영속화하지 않는다.

`metadata`는 JSON 형태의 비민감 부가 정보에만 쓴다. 요청 본문 전체나 원본 VAN 응답을 그대로 저장하는 용도로 사용하지 않는다.

## 8. 수용 조건

- 복수 상품 주문의 정상 생성 및 합계 불일치 거절을 자동 테스트한다.
- 온라인 카드, 오프라인 카드, 포인트 결제가 각각 올바른 Fake Gateway를 통해 승인된다.
- 각 승인 성공 시 결제는 `APPROVED`, 주문은 `PAID`가 된다.
- VAN의 성공 응답코드가 아니거나 승인금액이 주문과 다르면 오프라인 결제가 거절된다.
- 승인 결제를 취소하면 결제는 `CANCELLED`, 주문은 `CANCELLED`가 된다.
- 민감 필드가 엔티티, API 응답, 애플리케이션 로그에 나타나지 않는다.
