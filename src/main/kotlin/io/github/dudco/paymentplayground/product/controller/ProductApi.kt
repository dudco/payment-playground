package io.github.dudco.paymentplayground.product.controller

import io.github.dudco.paymentplayground.product.service.ProductResult

/** 상품 조회 응답. */
data class ProductResponse(val productId: String, val name: String, val price: Long) {

	companion object {
		fun from(result: ProductResult) = ProductResponse(result.productId, result.name, result.price)
	}
}
