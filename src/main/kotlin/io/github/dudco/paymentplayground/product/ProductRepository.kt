package io.github.dudco.paymentplayground.product

/** 상품 조회 창구. 장바구니 등 다른 기능은 이 인터페이스로만 상품을 찾는다. */
interface ProductRepository {

	/** 전체 상품을 정의된 순서대로 돌려준다. */
	fun findAll(): List<Product>

	/** 없는 상품이면 `null`을 돌려준다. */
	fun findById(productId: String): Product?
}
