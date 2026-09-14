# Payment Playground

멱등키 기반 주문 초안과 Fake Gateway 결제 흐름에서 시작해, 실제로 재현한 문제를 하나씩 해결하는 Spring Boot 실험 프로젝트입니다.

## MVP 초안 범위

- `POST /order/cart`로 멱등키 기반 주문 초안 생성·갱신
- `POST /order`로 Cart 초안을 불변 주문으로 전환
- `POST /payments`에서 `method`로 온라인 카드·오프라인 카드·자체 포인트 결제 생성
- 인프로세스 Fake Gateway 승인 뒤 주문·결제 상태 전이
- `POST /payments/{paymentId}/cancellations`로 승인 결제 전액 취소
- UI와 실제 PG/VAN 네트워크 없이 REST API와 자동 테스트만 제공

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

## 문서 구조

문서의 역할·갱신 시점·새 기능의 문서화 순서는 [문서 가이드](docs/README.md)를 확인합니다.

- [PRD](docs/prd.md): 현재 제품 범위, 사용자 흐름, 공개 API 수준의 계약과 수용 조건
- [데이터 모델](docs/data-model.md): 공유 영속 모델, 상태·금액 정합성, 민감정보 보존 규칙
- [ADR](docs/adr/): 되돌리기 어려운 기술적 결정과 대안
- [기능 스펙](docs/specs/): 기능별 상세 API·상태·오류·테스트 계약
- [구현 계획](docs/plans/): 구현 직전의 날짜 기반 TDD 실행 계획

## 기능 확장 원칙

문제를 먼저 재현하고, 그 문제를 해결하는 최소 기능만 다음 단계에 추가합니다.

1. 단일 결제 한계 재현 → 분할 결제, 결제 배분, 부분 취소
2. 실제 비동기 PG/VAN 연동 재현 → 웹훅 중복 제거, 상태 대사
3. 지연·역순·응답 유실 재현 → 상태 머신, `UNKNOWN`, 대사
4. 작업 처리 실패 재현 → Outbox, 재시도, DLQ
5. 장기 장바구니 요구 재현 → Cart 만료·병합, 고객당 활성 Cart 단일화
