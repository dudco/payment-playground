# 주문·결제 데이터 모델

> 문서 역할과 갱신 원칙은 [문서 가이드](README.md)를 따른다. 기능별 API 계약은 [기능 스펙](specs/order-payment-api.md), 제품 범위는 [PRD](prd.md)를 기준으로 한다.

## 1. 목적

이 문서는 장바구니 초안·주문·결제 API의 저장 모델과 데이터 보존 기준을 정의한다. Cart는 Redis, 주문·결제는 SQLite/JPA에 저장한다([ADR-0003](adr/0003-cart-in-redis-with-ttl.md)). RDB 스키마는 Flyway SQL 마이그레이션으로만 만들고 바꾼다([ADR-0004](adr/0004-flyway-schema-migration.md)). 이 문서의 필드·제약이 바뀌면 새 마이그레이션 버전 파일을 함께 추가한다.

금액은 KRW 최소 단위인 정수로 저장한다. 통화 변환은 지원하지 않는다.

## 2. 저장소와 관계

```text
[Redis]  Cart (cart:{idempotencyKey}, TTL)
            ┆ idempotencyKey로 논리 연결 (FK 없음)
[RDB]    Order 1 --- N OrderLine
         Order 1 --- N Payment

Future: Product 1 --- N Item
```

`Cart`는 별도 `CartLine`을 가지지 않는다. Cart JSON 안의 `orderLines` 배열에 주문 초안 라인을 보관하고, 주문 생성 시에만 `OrderLine` 행으로 복사한다.

"이 멱등키로 주문이 생성되었는가"의 유일한 기준은 `orders.idempotency_key`다. Redis Cart에는 상태를 두지 않는다.

`Product`는 고객에게 판매하는 상품 카탈로그 단위이고, `Item`은 매입가·매입 거래처·재고를 관리하는 품목 단위다. 하나의 `Product`에 여러 `Item`이 연결될 수 있다. 주문은 `Item`이 아니라 판매 시점의 `Product`를 참조하는 `OrderLine`을 가진다.

### 테이블 명명

`order`는 SQL 예약어이므로 주문 테이블은 `orders`로 고정한다. 엔티티 클래스 이름은 `Order`를 유지하고 `@Table(name = "orders")`로 매핑한다. 나머지 테이블은 `order_lines`, `payments`로 쓴다.

## 3. Cart (Redis)

- 키: `cart:{idempotencyKey}`
- 값: 아래 필드를 가진 JSON 문자열 하나
- TTL: 설정값 `payment-playground.cart.ttl`(기본 1시간). 생성·갱신할 때마다 다시 설정한다.

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `idempotencyKey` | Cart 생성·조회·주문 전환에 쓰는 키 | 필수, 키 이름과 동일 |
| `customerId` | 고객 참조 식별자 | 필수, 생성 후 변경 불가 |
| `orderLines` | 상품·수량·판매가의 주문 초안 스냅샷 배열 | 필수, 비어 있지 않음 |
| `totalAmount` | `orderLines`에서 계산한 합계 | 0보다 큼 |
| `createdAt` | Cart 생성 시각 | 필수 |
| `updatedAt` | 마지막 갱신 시각 | 필수 |

`expiresAt`은 저장하지 않고 응답할 때 Redis TTL로 계산한다.

`orderLines`의 각 원소는 다음 비민감 필드만 허용한다.

```json
{
  "productId": "americano",
  "productName": "아메리카노",
  "quantity": 2,
  "unitPrice": 4500
}
```

원 주문 요청 전체, 카드 정보, PG 토큰, 포인트 바코드, VAN 원문은 Cart에 저장하지 않는다.

Cart 생명주기:

1. `POST /order/cart`로 생성된다.
2. 같은 키의 `POST /order/cart`로 내용이 교체되고 TTL이 다시 설정된다. `customerId`가 다르면 거절한다.
3. `POST /order`가 커밋되면 삭제된다. 삭제되지 않더라도 TTL이 지나면 사라진다.

## 4. Order (`orders`)

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 주문 식별자(UUID) | PK |
| `idempotencyKey` | 원본 Cart의 멱등키 | unique, 필수 |
| `customerId` | 고객 참조 식별자 | 필수 |
| `status` | `CREATED`, `PAID` | 필수 |
| `totalAmount` | 주문 총액 | 0보다 큼 |
| `createdAt` | 주문 생성 시각 | 필수 |
| `updatedAt` | 마지막 상태 변경 시각 | 필수 |

정합성 규칙: `totalAmount = Σ(OrderLine.quantity × OrderLine.unitPrice)`.

`POST /order`는 Order·OrderLine 생성을 하나의 DB 트랜잭션에서 수행하고, 커밋 후 Redis Cart를 삭제한다. `idempotencyKey`의 unique 제약으로 하나의 키가 여러 주문을 만들지 못하게 한다.

상태:

- `CREATED`: 결제 가능. `FAILED` 결제가 있어도 이 상태를 유지한다.
- `PAID`: `APPROVED` 결제가 하나 있다. 새 결제 불가.

## 5. OrderLine (`order_lines`)

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

## 7. Payment (`payments`)

| 필드 | 설명 | 제약 |
| --- | --- | --- |
| `id` | 결제 식별자(UUID) | PK |
| `orderId` | 대상 주문 식별자 | FK, 필수 |
| `method` | `ONLINE_CARD`, `OFFLINE_CARD`, `POINT` | 필수 |
| `status` | `APPROVED`, `FAILED` | 필수 |
| `amount` | 승인 요청/승인 금액 | 주문 총액과 일치 |
| `providerTransactionId` | 외부 결제/VAN 거래 고유번호 | `APPROVED`면 필수, 결제수단별 유일 |
| `approvalNumber` | 승인번호 | 카드 승인에 사용, nullable |
| `approvedAt` | 승인 시각 | `APPROVED`면 필수 |
| `failureCode` | Gateway 거절 코드 | `FAILED`면 필수 |
| `failureMessage` | 비민감 거절 설명 | `FAILED`면 사용 |
| `metadata` | 비민감 부가 정보 JSON | nullable |
| `createdAt` | 생성 시각 | 필수 |

Payment는 Gateway 결과가 나온 뒤 한 번 저장되며 이후 상태가 바뀌지 않는다. 그래서 `updatedAt`을 두지 않는다. Gateway 호출 전 요청 검증에서 거절된 요청은 Payment를 만들지 않는다.

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

## 10. 인덱스와 무결성

- unique 제약: `orders.idempotency_key`, `(payments.method, payments.provider_transaction_id)`.
- 조회 인덱스: `order_lines.order_id`, `payments.order_id`.
- 금액과 수량은 애플리케이션 검증과 DB 제약(`CHECK`)을 함께 적용한다. 제약은 Flyway 마이그레이션 SQL에 정의한다.
- "주문당 `APPROVED` 결제는 최대 하나"는 이 단계에서 애플리케이션 검사로만 보장한다. 동시 요청에서는 깨질 수 있다([PRD 10. 알려진 한계](prd.md#10-알려진-한계)).
