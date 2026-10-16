# 코드 컨벤션

> 상태: 초안 · 작성일: 2026-10-16 · 최종 갱신일: 2026-10-16

> 문서 역할과 갱신 원칙은 [문서 가이드](README.md)를 따른다. 이 문서는 기능 코드를 어떤 계층과 파일로 나누고, 계층 사이에 어떤 타입을 주고받는지 정한다. 새 기능의 스펙 "패키지와 책임" 절은 이 규칙을 따른다.

## 1. 패키지

- 기능별 패키지를 둔다: `product`, `cart`, `order`(이후 `payment`). 한 기능의 컨트롤러·서비스·도메인·저장소는 같은 패키지에 있다.
- `customer`: 현재 고객을 알아내는 웹 계층 장치(`@CurrentCustomer`, resolver). 기능이 아니라 요청 컨텍스트다.
- `support`: 특정 기능에 속하지 않는 기술 요소. 시간(`Clock`), ID·키 생성(`IdempotencyKeyGenerator`), 공통 오류 타입과 오류 응답, 공통 변환기. 비즈니스 규칙을 넣지 않는다.
- 기능 사이 의존은 한 방향으로만 흐르고 순환하지 않는다: `order → cart → product`. 다른 기능의 **도메인 모델, 저장소 인터페이스, 서비스**에는 의존할 수 있지만, 다른 기능의 **컨트롤러와 HTTP 계약 타입(`Request`·`Response`)**에는 의존하지 않는다.

## 2. 계층과 계층 사이의 타입

```text
[HTTP] ── XxxRequest ──▶ Controller ── XxxCommand ──▶ Service ──▶ Repository
                            │                          │   ◀── 엔티티 ──┘
[HTTP] ◀── XxxResponse ─────┘ ◀──────── XxxResult ─────┘
```

각 계층은 자기가 받는 입력과 내보내는 출력 타입을 스스로 선언한다. 의존은 아래로만 향한다.

| 계층 | 선언하는 타입 | 아는 타입 | 모르는 타입 |
| --- | --- | --- | --- |
| Controller | `XxxRequest`, `XxxResponse` | 서비스의 `XxxCommand`·`XxxResult` | 엔티티·도메인 모델, 저장소 |
| Service | `XxxCommand`, `XxxResult` | 엔티티·도메인 모델, 저장소 | `Request`·`Response`, HTTP(헤더, 상태 코드) |
| Repository | (엔티티·도메인 모델을 다룬다) | 엔티티·도메인 모델 | 위의 모든 것 |

### 들어오는 방향

- `XxxRequest`: HTTP 본문의 모양과 Bean Validation 애노테이션을 가진다. 컨트롤러 밖으로 나가지 않는다.
- 컨트롤러는 검증이 끝난 요청을 `XxxCommand`로 바꿔 서비스에 넘긴다. 서비스 입력이 ID나 헤더 값 몇 개뿐이면 `Command`를 만들지 않고 값으로 넘긴다.
- 헤더 공백 제거처럼 HTTP 입력을 정리하는 일은 컨트롤러(또는 `customer` resolver)가 한다.

### 나가는 방향

- 서비스는 저장소에서 엔티티·도메인 모델을 받아 비즈니스 로직을 수행하고, 그 결과를 `XxxResult`로 만들어 반환한다. **엔티티·도메인 모델을 서비스 밖으로 반환하지 않는다.**
  - 이유: `spring.jpa.open-in-view=false`라서 트랜잭션 밖에서는 지연 로딩 필드를 읽을 수 없다. `Result`는 트랜잭션 안에서 필요한 값을 모두 읽어 둔 값이다. 또 테이블 구조와 API 응답이 서로를 끌고 다니지 않게 한다.
  - 응답 분기에 필요한 서비스 판단(예: 새로 만들었는지 재호출인지)도 `Result`에 담는다.
- 컨트롤러는 `XxxResponse.from(result)`로 응답을 만들고 상태 코드·헤더(`Location` 등)를 정한다.
- `Result`와 `Response`의 필드가 지금 같더라도 두 타입을 합치지 않는다.

### 이름

| 종류 | 이름 | 예 |
| --- | --- | --- |
| HTTP 요청 본문 | `<동작><대상>Request` | `SaveCartRequest`, `SaveCartLineRequest` |
| HTTP 응답 본문 | `<대상>Response` | `CartResponse`, `OrderLineResponse` |
| 서비스 입력 | `<대상>Command` | `CartLineCommand` |
| 서비스 출력 | `<대상>Result`, 동작 결과는 `<동작><대상>Result` | `CartResult`, `PlaceOrderResult` |
| 도메인 모델·엔티티 | 접미사 없음 | `Cart`, `Order`, `OrderLine`, `Product` |

`View`, `Dto`, `Info` 같은 접미사는 쓰지 않는다.

## 3. 파일

기능 패키지 안의 파일은 역할별로 나눈다.

| 파일 | 내용 |
| --- | --- |
| `XxxController.kt` | 컨트롤러 |
| `XxxApi.kt` | 그 기능의 HTTP 계약: `Request`·`Response`와 `Response.from(...)`. 스펙의 요청·응답 절과 1:1로 대응한다. |
| `XxxService.kt` | 서비스와 그 `Command`·`Result` |
| `Xxx.kt` | 도메인 모델·엔티티(필요하면 관련 enum·값 타입) |
| `XxxRepository.kt` | 저장소 인터페이스(또는 Spring Data 인터페이스)와 저장소가 반환하는 값 타입 |
| `<기술>XxxRepository.kt` | 저장소 구현과, 그 구현만 아는 저장 형식(직렬화용 문서 클래스·인코딩 함수는 `private`) |
| `XxxErrors.kt` | 그 기능의 도메인 오류를 만드는 함수(`productNotFound()` 등) |
| `XxxProperties.kt` | 그 기능의 설정(`@ConfigurationProperties`) |

- 저장 형식(예: Redis JSON 모양)은 저장소 구현의 세부 사항이다. 별도 코덱·문서 클래스를 공개 타입으로 두지 않는다.
- 저장소 인터페이스는 구현을 바꿔 끼우거나(테스트의 메모리 구현 포함) 다른 기능이 구현이 아니라 계약에 의존해야 할 때 둔다. Spring Data JPA 저장소는 인터페이스 자체가 계약이다.

## 4. 테스트

- 테스트는 대상 계층의 타입으로 단언한다: 컨트롤러 테스트는 JSON 응답(필드 이름·값·상태 코드), 서비스 테스트는 `Result`, 저장소 테스트는 저장된 값.
- 여러 기능이 함께 쓰는 테스트 지원 코드는 테스트 소스의 `support` 패키지에 둔다(예: 순서대로 키를 만드는 `SequentialIdempotencyKeyGenerator`). 한 기능만 쓰면 그 기능의 `XxxTestFixtures.kt`에 둔다.

## 5. 예외로 두는 것

없다. 로직이 없는 읽기 전용 기능(현재 상품)도 `Service`·`Result`를 둔다. 기능이 커질 때 계층을 나중에 끼워 넣는 비용보다, 모든 기능이 같은 모양인 이점이 크다고 본다.
