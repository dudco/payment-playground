# ADR-0004: Flyway로 RDB 스키마 관리

- 상태: 승인됨
- 일자: 2026-10-09

## 맥락

주문·결제 데이터는 SQLite에 영구 저장되며, 이후 동시성·분할 결제·대사 기능을 붙일 때마다 테이블과 제약이 바뀐다. 이 프로젝트는 unique·`CHECK` 같은 DB 제약을 정합성 수단으로 직접 실험하므로, 스키마를 SQL 그대로 보고 이력으로 관리할 수 있어야 한다.

Hibernate `ddl-auto=update`는 생성되는 DDL을 통제하기 어렵고 제약 변경·삭제를 반영하지 못한다. 실행 환경마다 스키마가 달라질 수도 있다.

## 결정

- 스키마는 Flyway SQL 마이그레이션(`src/main/resources/db/migration/V{n}__{설명}.sql`)으로만 변경한다.
- JPA는 `spring.jpa.hibernate.ddl-auto=validate`로 엔티티와 스키마의 일치만 검사한다.
- 테스트도 같은 마이그레이션을 실행한다. 별도의 `create-drop` 스키마를 쓰지 않는다.
- 적용된 마이그레이션 파일은 수정하지 않는다. 변경은 항상 다음 버전 파일로 추가한다.
- 의존성은 `spring-boot-starter-flyway`다. Spring Boot 4부터 Flyway 자동 설정이 별도 모듈로 분리되었고, SQLite 지원은 `flyway-core`에 포함되어 있다.

## 고려한 대안

### 대안 1: Hibernate `ddl-auto=update`

- 장점: 설정이 필요 없다.
- 단점: DDL과 제약을 통제할 수 없고, 변경 이력이 남지 않는다.

채택하지 않는다.

### 대안 2: Liquibase

- 장점: DB 독립적인 changeset과 롤백 정의를 지원한다.
- 단점: XML/YAML 변경 기술과 설정이 늘어난다. 이 프로젝트는 단일 DB에서 SQL을 직접 다루므로 이점이 작다.

채택하지 않는다.

## 결과

- 스키마 변경이 버전 번호가 붙은 SQL 파일로 git에 남는다.
- `bootRun` 시 로컬 DB에 마이그레이션이 자동 적용된다.
- 엔티티를 바꿀 때 마이그레이션 파일도 함께 작성해야 하며, 누락하면 `validate`가 기동을 실패시킨다.
- 이후 DB를 PostgreSQL 등으로 바꾸면 해당 `flyway-database-*` 모듈 추가와 SQL 방언 검토가 필요하다.
