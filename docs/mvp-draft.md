# MVP 설계 초안

## 목표

정상적인 단일 주문 결제 흐름을 REST API와 Fake PG로 끝까지 연결한다. 이 단계에서는 중복·지연·역순·장애 복구를 해결하지 않는다.

## 정상 흐름

```text
Client -> Order API: 주문 생성
Client -> Order API: 결제 요청
Order API -> Fake PG: 결제 승인 요청
Fake PG -> Order API: payment.succeeded 웹훅 1회 전송
Order API -> SQLite: 주문·결제 상태를 성공으로 갱신
```

## API 초안

### 1. 주문 생성

`POST /orders`

```json
{
  "customerId": "customer-001",
  "amount": 15000
}
```

응답 `201 Created`

```json
{
  "orderId": "uuid",
  "status": "CREATED",
  "amount": 15000
}
```

### 2. 결제 요청

`POST /orders/{orderId}/payments`

응답 `202 Accepted`

```json
{
  "paymentId": "uuid",
  "status": "PENDING"
}
```

### 3. Fake PG 웹훅 수신

`POST /webhooks/payments`

```json
{
  "providerPaymentId": "fake-pg-uuid",
  "eventType": "payment.succeeded"
}
```

응답 `204 No Content`

## 최소 상태

| 대상 | 상태 |
| --- | --- |
| Order | `CREATED` → `PAID` |
| Payment | `PENDING` → `SUCCEEDED` |

## MVP 통합 테스트

1. 주문을 생성한다.
2. 해당 주문의 결제를 요청한다.
3. Fake PG 성공 웹훅을 전달한다.
4. 주문과 결제가 각각 `PAID`, `SUCCEEDED`인지 검증한다.

## 이번 단계에서 하지 않는 것

- `Idempotency-Key`
- 중복 웹훅 처리
- 결제 실패·환불
- Outbox, 재시도, DLQ
- 운영 UI
