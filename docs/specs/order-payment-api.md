# 주문·결제 API 초안 명세

> 상태: 초안 · 작성일: 2026-09-13 · 최종 갱신일: 2026-09-13

> 이 문서는 [문서 가이드](../README.md), [PRD](../prd.md), [데이터 모델](../data-model.md), [ADR-0001](../adr/0001-payment-gateway-by-method.md), [ADR-0002](../adr/0002-cart-checkout-creates-order.md)를 바탕으로 기본적인 주문·결제 수직 흐름을 실제 Spring Boot API로 만드는 기술 초안이다. 운영 수준의 완성형 설계가 아니라, 자동 테스트로 검증되는 최소 뼈대를 먼저 만들고 이후 기능을 작은 단위로 붙여 나간다.

## 1. 범위

이번 초안 구현은 다음 HTTP API와 인프로세스 Fake Gateway를 제공한다. 각 API는 향후 실제 PG/VAN 연동, 할인, 재시도·대사 기능으로 확장할 수 있도록 최소 책임만 가진다.

- `POST /order/cart`: 멱등키 기반 Cart 초안 생성 또는 갱신
- `GET /order/cart`: 멱등키 기반 Cart 초안 선택 조회
- `POST /order`: Cart 초안을 불변 주문으로 전환
- `POST /payments`: `method` 기반 결제 생성 및 즉시 승인
- `POST /payments/{paymentId}/cancellations`: 전액 취소

실제 PG/VAN 네트워크, 웹뷰, 포인트 원장, 인증/인가, 장바구니 만료·병합은 구현하지 않는다.

## 2. 패키지와 책임

```text
io.github.dudco.paymentplayground
├── cart       Cart 엔티티·Repository·Service·API
├── order      Order/OrderLine 엔티티·Repository·Service·API
├── payment    Payment/PaymentCancellation, API, 상태 전이
├── gateway    결제수단별 인터페이스와 Fake 구현체
└── support    API 오류 응답, 예외 처리, 시간/ID 지원
```

- Controller는 HTTP 요청 검증과 응답 변환만 담당한다.
- Service는 트랜잭션, 상태 전이, 금액 검증을 담당한다.
- Gateway는 승인 결과만 반환하며 JPA 엔티티를 직접 변경하지 않는다.
- Repository는 Spring Data JPA로 구현한다.

## 3. 영속 모델

### 3.1 Cart

- `id`: UUID 문자열 PK
- `idempotencyKey`: unique
- `customerId`
- `status`: `ACTIVE`, `ORDER_CREATED`
- `orderLinesJson`: `productId`, `productName`, `quantity`, `unitPrice` 배열 JSON
- `totalAmount`: 서버 계산값
- `orderId`: 주문 전환 후 설정, unique
- 생성·수정 시각

Cart 라인은 별도 테이블을 만들지 않는다. `orderLinesJson`은 비민감 주문 초안만 보관한다.

### 3.2 Order와 OrderLine

- Order는 `cartId`를 unique FK로 가진다.
- `POST /order` 시 Cart JSON을 파싱해 `OrderLine` 행으로 복사한다.
- `Order.totalAmount`와 모든 `OrderLine.lineAmount`의 합은 일치해야 한다.

### 3.3 Payment와 PaymentCancellation

- Payment는 `orderId`, `method`, `status`, `amount`, 승인 결과, 실패 정보, 비민감 `metadata`를 가진다.
- PaymentCancellation은 승인 결제당 하나이며 전액 취소만 기록한다.
- 원 카드번호, Track2, PIN, 주민번호, PG 토큰, 포인트 바코드는 영속화하지 않는다.

## 4. Cart와 Order 계약

### 4.1 `POST /order/cart`

- `Idempotency-Key` 헤더는 선택이다.
- 없으면 UUID 기반 키를 생성해 응답 헤더 및 본문에 반환한다.
- 동일 키의 `ACTIVE` Cart가 있으면 요청의 `customerId`, `orderLines`, 합계를 최신 값으로 교체한다.
- `ORDER_CREATED` Cart에 같은 키로 갱신을 시도하면 `409 CART_ALREADY_ORDERED`를 반환한다.
- 새 Cart는 `201`, 기존 활성 Cart 갱신은 `200`을 반환한다.

### 4.2 `GET /order/cart`

- `Idempotency-Key` 헤더가 필수다.
- 일치하는 Cart가 없으면 `404 CART_NOT_FOUND`다.
- 조회 호출 여부는 주문 생성에 영향을 주지 않는다.

### 4.3 `POST /order`

- `Idempotency-Key` 헤더가 필수다.
- `ACTIVE` Cart가 없으면 `404 CART_NOT_FOUND`다.
- 하나의 트랜잭션에서 Order, OrderLine을 만들고 Cart를 `ORDER_CREATED`로 전이한다.
- 이미 `ORDER_CREATED`인 Cart는 연결된 기존 Order를 `200`으로 반환한다.

## 5. Payment 계약

`POST /payments` 요청은 공통 `orderId`, `method`, `amount`, `paymentDetails`를 가진다.

| method | Gateway | paymentDetails 필수값 |
| --- | --- | --- |
| `ONLINE_CARD` | `FakeOnlineCardPaymentGateway` | `paymentToken` |
| `OFFLINE_CARD` | `FakeOfflineCardPaymentGateway` | `terminalId`, `vanTransactionId`, `approvalNumber`, `approvedAt`, `responseCode`, `vatAmount`, `issuerCode`, `acquirerCode`, `maskedCardNumber` |
| `POINT` | `FakePointPaymentGateway` | `channel` 및 OFFLINE이면 `barcode`, ONLINE이면 `pointPaymentMethodId` |

공통 규칙:

- `amount`는 Order 총액과 같아야 한다.
- `method`별 세부 필드가 누락되거나 종류가 맞지 않으면 `422 PAYMENT_DETAILS_INVALID`다.
- Fake Gateway 거절은 `422 PAYMENT_DECLINED`다.
- 승인 성공 시 Payment는 `APPROVED`, Order는 `PAID`다.
- `PAID` Order에는 새 Payment를 만들 수 없다.
- 오프라인 성공 응답코드는 MVP에서 `0000`으로 고정한다.

## 6. Fake Gateway 결정 규칙

테스트 가능하고 예측 가능한 결과만 사용한다.

- 온라인 카드: 빈 토큰이 아니면 승인한다.
- 오프라인 카드: `responseCode == "0000"`이고 VAN 승인 금액과 요청 금액이 일치할 때만 승인한다.
- 포인트: 필수 참조값이 있고 금액이 양수이면 승인한다.
- 모든 Fake Gateway는 UUID 기반 `providerTransactionId`, 승인 시각, 결제수단별 비민감 메타데이터를 반환한다.

## 7. 오류 응답

모든 도메인 오류는 다음 JSON 형식으로 반환한다.

```json
{
  "code": "CART_NOT_FOUND",
  "message": "해당 멱등키로 생성된 장바구니가 없습니다."
}
```

| HTTP | 코드 예시 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 헤더/본문 누락, 빈 라인, 0 이하 수량·금액 |
| 404 | `CART_NOT_FOUND`, `ORDER_NOT_FOUND`, `PAYMENT_NOT_FOUND` | 대상 없음 |
| 409 | `CART_ALREADY_ORDERED`, `ORDER_ALREADY_PAID`, `PAYMENT_NOT_CANCELLABLE` | 허용되지 않는 상태 전이 |
| 422 | `PAYMENT_DETAILS_INVALID`, `PAYMENT_DECLINED`, `ORDER_AMOUNT_MISMATCH` | 비즈니스 검증 또는 승인 거절 |

## 8. 테스트 수용 조건

1. 키 없는 Cart 생성은 키와 `201`을 반환한다.
2. 같은 키의 활성 Cart 갱신은 `200` 및 최신 라인을 반환한다.
3. Cart 없이 Order를 만들 수 없다.
4. Cart 조회 없이도 유효한 키로 Order를 만들 수 있다.
5. 같은 키로 Order를 두 번 생성해도 Order는 한 개다.
6. 주문 후 Cart 갱신은 `409`다.
7. 온라인·오프라인·포인트 결제는 각각 `POST /payments`와 `method`로 승인된다.
8. 오프라인 비성공 응답코드와 금액 불일치는 거절된다.
9. 승인 결제를 전액 취소하면 Payment와 Order가 `CANCELLED`가 된다.
10. 금지된 민감 필드는 응답, 엔티티, 로그에 저장하지 않는다.
