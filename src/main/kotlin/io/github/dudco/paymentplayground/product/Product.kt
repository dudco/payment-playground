package io.github.dudco.paymentplayground.product

import io.github.dudco.paymentplayground.support.NotFoundException

/** 판매 상품. 가격은 원 단위 정수다. */
data class Product(val id: String, val name: String, val price: Long)

/** 상품 조회 창구. 장바구니 등 다른 기능은 이 인터페이스로만 상품을 찾는다. */
interface ProductCatalog {

	/** 전체 상품을 정의된 순서대로 돌려준다. */
	fun findAll(): List<Product>

	/** 없는 상품이면 `null`을 돌려준다. */
	fun findById(productId: String): Product?
}

/** 없는 상품에 대한 `404 PRODUCT_NOT_FOUND` 오류. */
fun productNotFound(): NotFoundException = NotFoundException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.")
