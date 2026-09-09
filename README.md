# Order Reliability Lab

주문 생성과 Fake PG의 정상 결제 흐름에서 시작해, 실제로 재현한 문제를 하나씩 해결하는 Spring Boot 실험 프로젝트입니다.

## MVP 범위

- `POST /orders`로 주문 생성
- 주문의 결제를 Fake PG에 요청
- Fake PG가 `payment.succeeded` 웹훅을 한 번 전송
- 웹훅 처리 뒤 주문·결제가 성공 상태로 전이
- UI 없음: REST API와 자동 테스트만 제공

## 기술 스택

- Kotlin, Spring Boot, Java 21
- SQLite: 주문·결제 데이터를 로컬 파일로 저장
- Redis: 현재는 연결 설정만 두며, 이후 단기 중복 완화·작업 처리 보조에 사용
- Gradle Wrapper

## 실행

```bash
./gradlew test
./gradlew bootRun
```

Redis가 기본 주소(`localhost:6379`)에서 실행 중이어야 합니다. 다른 주소는 `REDIS_HOST`, `REDIS_PORT` 환경 변수로 지정합니다.

## 설계 초안

[MVP API와 상태 전이](docs/mvp-draft.md)를 먼저 확인합니다.

## 기능 확장 원칙

문제를 먼저 재현하고, 그 문제를 해결하는 최소 기능만 다음 단계에 추가합니다.

1. 중복 주문 재현 → `Idempotency-Key`
2. 중복 웹훅 재현 → 이벤트 deduplication
3. 지연·역순·응답 유실 재현 → 상태 머신, `UNKNOWN`, 대사
4. 작업 처리 실패 재현 → Outbox, 재시도, DLQ
