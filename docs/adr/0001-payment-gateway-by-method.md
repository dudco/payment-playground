# ADR-0001: 결제수단별 Payment Gateway 인터페이스 분리

- 상태: 승인됨
- 일자: 2026-09-13

## 맥락

주문 API는 온라인 카드, 오프라인 카드, 자체 포인트 결제를 지원해야 한다. 세 방식은 승인에 필요한 입력과 외부 결과의 성격이 다르다.

- 온라인 카드는 웹뷰/PG가 발급한 일회성 토큰으로 승인한다.
- 오프라인 카드는 POS/키오스크가 이미 받은 VAN 승인 결과를 접수한다.
- 포인트는 바코드 또는 등록된 결제수단 식별자로 차감한다.

단일 결제 Gateway에 모든 필드를 넣으면 카드 토큰, VAN 승인번호, 바코드, 등록 수단 ID가 한 요청 DTO에 섞이고, 대부분의 필드가 nullable이 된다. 이는 잘못된 결제 요청을 만들기 쉽고 향후 실제 연동체 교체를 어렵게 한다.

## 결정

결제수단별 전용 인터페이스를 둔다.

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

개발용 구현체는 각각 다음과 같다.

- `FakeOnlineCardPaymentGateway`
- `FakeOfflineCardPaymentGateway`
- `FakePointPaymentGateway`

애플리케이션 서비스는 결제수단에 맞는 Gateway만 호출하고, 공통 `PaymentApprovalResult`를 `Payment` 도메인 상태 전이에 사용한다.

## 고려한 대안

### 대안 1: 단일 `PaymentGateway`와 하나의 nullable 요청 DTO

모든 결제수단이 `PaymentGateway.approve(command)`을 호출하되, `paymentToken`, `vanTransactionId`, `barcode`, `pointPaymentMethodId`를 한 DTO에 둔다.

- 장점: 인터페이스 수가 적다.
- 단점: 유효하지 않은 필드 조합을 타입으로 막을 수 없고, 검증 분기가 커진다.

채택하지 않는다.

### 대안 2: 단일 `PaymentGateway`와 sealed command

`PaymentApprovalCommand`의 하위 타입으로 결제수단별 입력을 표현한다.

- 장점: 공통 진입점과 타입 안전성을 함께 얻는다.
- 단점: 하나의 Gateway 구현이 서로 다른 PG/VAN/포인트 책임을 모두 알아야 하며, 실제 연동체 교체 시 조건 분기가 남는다.

현재 MVP에서는 채택하지 않는다. 여러 결제수단을 동적으로 조합·플러그인화해야 할 때 재검토한다.

### 대안 3: 결제수단별 전용 Gateway 인터페이스

각 결제수단의 승인 명령과 책임을 별도 인터페이스로 나눈다.

- 장점: 입력 타입이 명확하고, 실제 PG·VAN·포인트 구현체로 독립 교체하기 쉽다.
- 단점: 인터페이스와 테스트 대상 수가 늘어난다.

채택한다.

## 결과

- 각 REST 요청 DTO는 해당 결제수단에 필요한 비민감 필드만 가진다.
- VAN 원본 응답과 PG 토큰은 도메인 엔티티에 들어가지 않는다.
- Fake 구현체로 테스트를 시작하고 실제 연동은 같은 인터페이스의 구현체로 추가할 수 있다.
- Gateway가 실제 외부 통신을 시작하거나 비동기 웹훅이 필요해질 경우, 동기 상태 전이 전략을 별도 ADR로 재검토한다.
