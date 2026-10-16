# 상품 조회 API 명세

> 상태: 승인됨 · 작성일: 2026-10-13 · 최종 갱신일: 2026-10-16 (2장 패키지와 책임을 [코드 컨벤션](../conventions.md)에 맞춤)

> [ADR-0005](../adr/0005-product-api-mock-catalog.md)에 따라 상품은 코드에 고정된 Mock 카탈로그로 제공한다. 장바구니가 상품을 참조하기 위한 최소 기능이다.

## 1. 범위

- `GET /products`: 전체 상품 목록
- `GET /products/{productId}`: 상품 한 건

상품 생성·수정·삭제, 노출 상태, 재고, `Item` 모델은 구현하지 않는다.

## 2. 패키지와 책임

[코드 컨벤션](../conventions.md)을 따른다.

```text
io.github.dudco.paymentplayground.product
├── ProductController    조회 API. 서비스 결과를 ProductResponse로 바꾼다
├── ProductApi           ProductResponse (HTTP 응답)
├── ProductService       조회, ProductResult 반환
├── Product              상품 도메인 값 (id, name, price), ProductCatalog 인터페이스
├── MockProductCatalog   코드에 고정된 목록을 가진 ProductCatalog 구현체 (@Component)
└── ProductErrors        productNotFound()
```

다른 기능(장바구니 등)은 `ProductCatalog`만 의존한다. `MockProductCatalog`를 직접 참조하지 않는다.

## 3. Mock 상품 목록

| `id` | `name` | `price` |
| --- | --- | --- |
| `americano` | 아메리카노 | 4500 |
| `latte` | 카페라테 | 5000 |
| `cake` | 케이크 | 6000 |
| `sandwich` | 샌드위치 | 7500 |
| `cookie` | 쿠키 | 2500 |

목록 순서가 `GET /products`의 응답 순서다.

## 4. API 계약

### `GET /products`

응답 `200 OK`:

```json
[
  { "productId": "americano", "name": "아메리카노", "price": 4500 },
  { "productId": "latte", "name": "카페라테", "price": 5000 }
]
```

### `GET /products/{productId}`

응답 `200 OK`:

```json
{ "productId": "americano", "name": "아메리카노", "price": 4500 }
```

없는 상품이면 `404 PRODUCT_NOT_FOUND`.

## 5. 테스트 수용 조건

1. `GET /products`는 Mock 목록 전체를 정의된 순서로 반환한다.
2. `GET /products/{productId}`는 해당 상품을 반환한다.
3. 없는 상품 ID는 `404 PRODUCT_NOT_FOUND`다.
