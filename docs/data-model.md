# 주문·결제 데이터 모델

## 1. 목적

이 문서는 장바구니 초안·주문·결제 API의 영속 모델과 데이터 보존 기준을 정의한다. SQLite/JPA 구현을 전제로 하되, 특정 ORM 어노테이션이나 컬럼 타입은 구현 계획에서 확정한다.

금액은 KRW 최소 단위인 정수로 저장한다. 통화 변환은 지원하지 않는다.

## 2. 엔티티 관계

```text
Cart 1 --- 0..1 Order
Order 1 --- N OrderLine
Order 1 --- N Payment
Payment 1 --- 0..1 PaymentCancellation

Future: Product 1 --- N Item
```

`Cart`는 별도 `CartLine` 테이블을 가지지 않는다. `Cart.orderLinesJson`에 주문 초안 라인을 JSON 스냅샷으로 보관하고, 주문 생성 시에만 `OrderLine` 행으로 복사한다.

`Product`는 고객에게 판매하는 상품 카탈로그 단위이고, `Item`은 매입가·매입 거래처·재고를 관리하는 품목 단위다. 하나의 `Product`에 여러 `Item`이 연결될 수 있다. 주문은 `Item`이 아니라 판매 시점의 `Product`를 참조하는 `OrderLine`을 가진다.

## 3. Cart

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 내부 Cart 식별자(UUID) | PK |
| `idempotencyKey` | Cart 생성·조회·주문 전환에 쓰는 키 | unique, 필수 |
| `customerId` | 고객 참조 식별자 | 필수 |
| `status` | `ACTIVE`, `ORDER_CREATED` | 필수 |
| `orderLinesJson` | 상품·수량·판매가의 주문 초안 스냅샷 | 필수, 비어 있지 않음 |
| `totalAmount` | `orderLinesJson`에서 계산한 합계 | 0보다 큼 |
| `orderId` | 전환 후 생성된 주문 식별자 | unique, nullable |
| `createdAt` | Cart 생성 시각 | 필수 |
| `updatedAt` | 마지막 갱신/상태 변경 시각 | 필수 |

`ACTIVE` Cart는 동일 `idempotencyKey`의 `POST /order/cart`로 주문 초안을 교체할 수 있다. `ORDER_CREATED` Cart는 갱신할 수 없다.

`orderLinesJson`은 다음 비민감 필드만 허용한다.

```json
[
  {
    "productId": "americano",
    "productName": "아메리카노",
    "quantity": 2,
    "unitPrice": 4500
  }
]
```

원 주문 요청 전체, 카드 정보, PG 토큰, 포인트 바코드, VAN 원문은 이 JSON에 저장하지 않는다.

## 4. Order

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 식별자(UUID) | PK |
| `cartId` | 원본 Cart 내부 식별자 | FK, unique, 필수 |
| `customerId` | 고객 참조 식별자 | 필수 |
| `status` | `CREATED`, `PAYMENT_PENDING`, `PAID`, `CANCELLED` | 필수 |
| `totalAmount` | 주문 총액 | 0보다 큼 |
| `createdAt` | 주문 생성 시각 | 필수 |
| `updatedAt` | 마지막 상태 변경 시각 | 필수 |

정합성 규칙: `totalAmount = Σ(OrderLine.quantity × OrderLine.unitPrice)`.

`POST /order`는 Cart·Order·OrderLine 생성과 `Cart.status = ORDER_CREATED`, `Cart.orderId` 설정을 하나의 트랜잭션에서 수행한다. `Order.cartId`의 unique 제약으로 하나의 Cart가 여러 주문을 만들지 못하게 한다.

## 5. OrderLine

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 라인 식별자(UUID) | PK |
| `orderId` | 소속 주문 식별자 | FK, 필수 |
| `productId` | 상품 참조 식별자 | 필수 |
| `productName` | 주문 시점 상품명 스냅샷 | 필수 |
| `quantity` | 수량 | 0보다 큼 |
| `unitPrice` | 주문 시점 단가 | 0 이상 |
| `lineAmount` | `quantity × unitPrice` | 서버 계산 |

`OrderLine`은 주문 후 변경하지 않는다. `itemId`는 이 MVP에 추가하지 않는다. 하나의 상품을 어떤 품목에서 출고·매입했는지는 재고/조달 기능과 함께 별도 할당 모델로 추가한다.

## 6. 향후 Product와 Item 모델

| 엔티티 | 책임 | 핵심 관계 |
| --- | --- | --- |
| `Product` | 고객 판매명, 판매가, 노출 상태를 관리하는 상품 | `Product 1 : N Item` |
| `Item` | 매입 거래처, 매입가, 공급사 SKU, 재고 관리 단위 | 하나의 `Product`에 귀속 |
| `OrderLine` | 주문 시점의 `Product`·수량·판매가 스냅샷 | `Order 1 : N OrderLine` |

`Item`은 주문 라인을 의미하지 않으며, `OrderItem`이라는 클래스·테이블·API 이름을 만들지 않는다.

## 7. Payment

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 결제 식별자(UUID) | PK |
| `orderId` | 대상 주문 식별자 | FK, 필수 |
| `method` | `ONLINE_CARD`, `OFFLINE_CARD`, `POINT` | 필수 |
| `status` | `PENDING`, `APPROVED`, `FAILED`, `CANCELLED` | 필수 |
| `amount` | 승인 요청/승인 금액 | 주문 총액과 일치 |
| `providerTransactionId` | 외부 결제/VAN 거래 고유번호 | 승인 뒤 필수, 결제수단별 유일 |
| `approvalNumber` | 승인번호 | 카드 승인에 사용, nullable |
| `approvedAt` | 승인 시각 | 승인 뒤 필수 |
| `failureCode` | 거절/검증 실패 코드 | 실패 시 필수 |
| `failureMessage` | 비민감 실패 설명 | 실패 시 사용 |
| `metadata` | 비민감 부가 정보 JSON | nullable |
| `createdAt` | 생성 시각 | 필수 |
| `updatedAt` | 마지막 상태 변경 시각 | 필수 |

## 8. PaymentCancellation

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 취소 식별자(UUID) | PK |
| `paymentId` | 원 승인 결제 식별자 | FK, unique, 필수 |
| `providerCancellationId` | 외부 취소 거래 식별자 | 필수 |
| `originalProviderTransactionId` | 원 승인 거래 식별자 | 필수 |
| `amount` | 취소 금액 | 원 결제 금액과 동일 |
| `reason` | 취소 사유 코드 | 필수 |
| `cancelledAt` | 취소 시각 | 필수 |
| `metadata` | 비민감 취소 부가 정보 JSON | nullable |

MVP는 전액 취소만 허용한다. `Payment.status = CANCELLED` 및 `Order.status = CANCELLED`와 취소 레코드 생성은 같은 트랜잭션에서 처리한다.

## 9. 결제수단별 데이터 매핑

### 9.1 온라인 카드

| 요청/결과 | 저장 위치 | 보존 정책 |
| --- | --- | --- |
| 결제 토큰 | 저장하지 않음 | Gateway 호출 후 폐기, 로그 금지 |
| PG 거래 식별자 | `providerTransactionId` | 저장 |
| 승인번호 | `approvalNumber` | PG가 제공할 때 저장 |
| 승인 시각/금액 | `approvedAt`, `amount` | 저장 |

### 9.2 오프라인 카드 / VAN

제공된 VAN 응답 모델에서 다음 값만 승인 저장 대상으로 삼는다.

| VAN 응답 값 | 대상 필드 | 비고 |
| --- | --- | --- |
| `transaction_number` 또는 `id` | `providerTransactionId` | VAN 거래 고유 식별자 |
| `accept_number` | `approvalNumber` | 승인번호 |
| `accept_date` | `approvedAt` | 파싱 가능해야 함 |
| `response_code` | `metadata.responseCode` | 성공 여부 검증에 사용 |
| `price`, `vat` | `amount`, `metadata.vatAmount` | 주문 금액과 대조 |
| `issuer_code`, `acquirer_code` | `metadata` | 카드사 코드 |
| `cat_id`, `device_number` | `metadata` | 단말 추적용 |
| 마스킹된 카드 식별값 | `metadata.maskedCardNumber` | 이미 마스킹된 값만 허용 |

다음 필드는 저장 및 로그 금지다: `track2`, `person_number`, `pin`, 원 카드번호, OTC/OTC2, XID, ECI, CAVV, 서명 데이터, 원본 전문 바이트.

`VANApproveResponse`는 현장 단말/연동 계층의 파싱 참조로만 사용한다. API·도메인 계층에는 이를 직접 노출하지 않고 `OfflineCardApprovalCommand`로 필요한 값만 변환한다.

### 9.3 자체 포인트

| 요청/결과 | 저장 위치 | 보존 정책 |
| --- | --- | --- |
| 바코드 원문 | 저장하지 않음 | 승인 후 폐기, 로그 금지 |
| 등록 결제수단 식별자 | 저장하지 않음 | 이 MVP에서는 요청 처리 후 폐기 |
| 포인트 거래 식별자 | `providerTransactionId` | 저장 |
| 차감 금액·승인 시각 | `amount`, `approvedAt` | 저장 |
| 채널(`ONLINE`/`OFFLINE`) | `metadata.channel` | 저장 가능 |

## 10. metadata 정책

`metadata`는 JSON 문자열로 저장하며, 검색·정합성의 핵심이 아닌 비민감 부가 정보만 담는다.

```json
{
  "responseCode": "0000",
  "vatAmount": 1364,
  "issuerCode": "01",
  "acquirerCode": "01",
  "terminalId": "CAT-001",
  "maskedCardNumber": "1234-****-****-5678"
}
```

`metadata`에도 API 요청/응답 원문 전체, VAN 원본 전문·바이너리·서명 데이터, 카드번호·Track2·PIN·주민번호·카드 인증 원문, 온라인 PG 토큰, 포인트 바코드 원문을 넣지 않는다.

## 11. 인덱스와 무결성

- `Cart.idempotencyKey`, `Cart.orderId`, `Order.cartId`, `OrderLine.orderId`, `Payment.orderId`, `PaymentCancellation.paymentId`에 unique 또는 조회 인덱스를 둔다.
- `Cart.idempotencyKey`, `Cart.orderId`, `Order.cartId`, `PaymentCancellation.paymentId`는 unique 제약을 둔다.
- `Payment.providerTransactionId`는 결제수단 범위에서 유일해야 한다.
- 금액과 수량은 애플리케이션 검증과 DB 제약을 함께 적용한다.
