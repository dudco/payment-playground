package io.github.dudco.paymentplayground.product

import org.springframework.stereotype.Service

/** 상품 조회 결과. */
data class ProductResult(val productId: String, val name: String, val price: Long)

private fun Product.toResult() = ProductResult(id, name, price)

/** 상품 목록·단건 조회. */
@Service
class ProductService(private val productRepository: ProductRepository) {

	fun findAll(): List<ProductResult> = productRepository.findAll().map { it.toResult() }

	/** 없는 상품이면 `404 PRODUCT_NOT_FOUND`. */
	fun get(productId: String): ProductResult =
		(productRepository.findById(productId) ?: throw productNotFound()).toResult()
}
