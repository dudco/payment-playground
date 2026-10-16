package io.github.dudco.paymentplayground.product.repository

import io.github.dudco.paymentplayground.product.domain.Product

/** 테스트에서 상품을 바꾸거나 지울 수 있는 카탈로그. `@Component`를 붙이지 않는다. */
class MutableProductRepository : ProductRepository {
	private val products = linkedMapOf<String, Product>()

	init {
		reset()
	}

	/** `FixedProductRepository`의 5개 상품으로 되돌린다. */
	fun reset() {
		products.clear()
		FixedProductRepository().findAll().forEach { products[it.id] = it }
	}

	/** 같은 ID의 상품이 있으면 교체한다. */
	fun put(product: Product) {
		products[product.id] = product
	}

	fun remove(productId: String) {
		products.remove(productId)
	}

	override fun findAll(): List<Product> = products.values.toList()

	override fun findById(productId: String): Product? = products[productId]
}
