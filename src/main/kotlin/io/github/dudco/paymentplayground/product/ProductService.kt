package io.github.dudco.paymentplayground.product

import org.springframework.stereotype.Service

/** 상품 조회 결과. */
data class ProductResult(val productId: String, val name: String, val price: Long)

private fun Product.toResult() = ProductResult(id, name, price)

/** 상품 목록·단건 조회. */
@Service
class ProductService(private val productRepository: ProductRepository) {

	fun findAll(): List<ProductResult> = productRepository.findAll().map { it.toResult() }

	/** 있는 상품만 ID를 키로 담아 돌려준다. 없는 ID는 결과에서 빠진다. 다른 기능이 여러 상품을 한 번에 읽을 때 쓴다. */
	fun findByIds(productIds: Collection<String>): Map<String, ProductResult> =
		productIds.distinct().mapNotNull { productRepository.findById(it)?.toResult() }.associateBy { it.productId }

	/** 없는 상품이면 `404 PRODUCT_NOT_FOUND`. */
	fun get(productId: String): ProductResult =
		(productRepository.findById(productId) ?: throw productNotFound()).toResult()
}
