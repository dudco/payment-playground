# 주문·결제 데이터 모델

## 1. 목적

이 문서는 주문·결제 API의 영속 모델과 데이터 보존 기준을 정의한다. SQLite/JPA 구현을 전제로 하되, 특정 ORM 어노테이션이나 컬럼 타입은 구현 계획에서 확정한다.

금액은 통화의 최소 단위(원)인 정수로 저장한다. 이 MVP의 `amount`는 모두 KRW이며, 통화 변환은 지원하지 않는다.

## 2. 엔티티 관계

```text
Order 1 --- N OrderLine
Order 1 --- N Payment
Payment 1 --- 0..1 PaymentCancellation

Future: Product 1 --- N Item
```

`Product`는 고객에게 판매하는 상품 카탈로그 단위이고, `Item`은 매입가·매입 거래처·재고를 관리하는 품목 단위다. 하나의 `Product`에 여러 `Item`이 연결될 수 있다. 주문은 `Item`이 아니라 판매 시점의 `Product`를 참조하는 `OrderLine`을 가진다.

이 MVP는 주문 전체 금액을 한 번에 결제하므로, 승인 가능한 `Payment`는 주문당 한 건이다. 결제 실패 기록은 남길 수 있다.

## 3. Order

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 식별자(UUID) | PK |
| `customerId` | 고객 참조 식별자 | 필수 |
| `status` | `CREATED`, `PAYMENT_PENDING`, `PAID`, `CANCELLED` | 필수 |
| `totalAmount` | 주문 총액 | 0보다 큼 |
| `createdAt` | 주문 생성 시각 | 필수 |
| `updatedAt` | 마지막 상태 변경 시각 | 필수 |

정합성 규칙: `totalAmount = Σ(OrderLine.quantity × OrderLine.unitPrice)`.

## 4. OrderLine

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 라인 식별자(UUID) | PK |
| `orderId` | 소속 주문 식별자 | FK, 필수 |
| `productId` | 상품 참조 식별자 | 필수 |
| `productName` | 주문 시점 상품명 스냅샷 | 필수 |
| `quantity` | 수량 | 0보다 큼 |
| `unitPrice` | 주문 시점 단가 | 0 이상 |
| `lineAmount` | `quantity × unitPrice` | 서버 계산 |

`productName`, `unitPrice`는 주문 당시 값을 보존한다. `itemId`는 이 MVP에 추가하지 않는다. 하나의 상품을 어떤 품목에서 출고·매입했는지는 재고/조달 기능과 함께 별도 할당 모델로 추가한다.

## 5. 향후 Product와 Item 모델

향후 카탈로그와 재고/매입 기능을 추가할 때 다음 책임으로 분리한다.

| 엔티티 | 책임 | 핵심 관계 |
| --- | --- | --- |
| `Product` | 고객 판매명, 판매가, 노출 상태를 관리하는 상품 | `Product 1 : N Item` |
| `Item` | 매입 거래처, 매입가, 공급사 SKU, 재고 관리 단위 | 하나의 `Product`에 귀속 |
| `OrderLine` | 주문 시점의 `Product`·수량·판매가 스냅샷 | `Order 1 : N OrderLine` |

따라서 `Item`은 주문 라인을 의미하지 않으며, `OrderItem`이라는 클래스·테이블·API 이름을 만들지 않는다.

## 6. Payment

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

`providerTransactionId`는 승인/취소 시 외부 거래를 연결하는 최소 식별자다. 포인트 결제는 Fake Gateway가 생성한 거래 식별자를 사용한다.

## 7. PaymentCancellation

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

## 8. 결제수단별 데이터 매핑

### 8.1 온라인 카드

| 요청/결과 | 저장 위치 | 보존 정책 |
| --- | --- | --- |
| 결제 토큰 | 저장하지 않음 | Gateway 호출 후 폐기, 로그 금지 |
| PG 거래 식별자 | `providerTransactionId` | 저장 |
| 승인번호 | `approvalNumber` | PG가 제공할 때 저장 |
| 승인 시각/금액 | `approvedAt`, `amount` | 저장 |

### 8.2 오프라인 카드 / VAN

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

### 8.3 자체 포인트

| 요청/결과 | 저장 위치 | 보존 정책 |
| --- | --- | --- |
| 바코드 원문 | 저장하지 않음 | 승인 후 폐기, 로그 금지 |
| 등록 결제수단 식별자 | 저장하지 않음 | 이 MVP에서는 요청 처리 후 폐기 |
| 포인트 거래 식별자 | `providerTransactionId` | 저장 |
| 차감 금액·승인 시각 | `amount`, `approvedAt` | 저장 |
| 채널(`ONLINE`/`OFFLINE`) | `metadata.channel` | 저장 가능 |

## 9. metadata 정책

`metadata`는 JSON 문자열로 저장하며, 검색·정합성의 핵심이 아닌 비민감 부가 정보만 담는다. 예시는 다음과 같다.

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

다음은 `metadata`에도 금지한다.

- API 요청/응답 원문 전체
- VAN 원본 전문과 바이너리/서명 데이터
- 카드번호, Track2, PIN, 주민번호 및 카드 인증 원문
- 온라인 PG 토큰 또는 포인트 바코드 원문

## 10. 인덱스와 무결성

- `OrderLine.orderId`, `Payment.orderId`, `PaymentCancellation.paymentId`에 인덱스를 둔다.
- `Payment.providerTransactionId`는 결제수단 범위에서 유일해야 한다.
- `PaymentCancellation.paymentId`는 하나의 승인 결제에 취소가 한 번만 연결되도록 unique 제약을 둔다.
- 금액과 수량은 애플리케이션 검증과 DB 제약을 함께 적용한다.
