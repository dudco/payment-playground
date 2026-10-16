package io.github.dudco.paymentplayground.product.repository

import io.github.dudco.paymentplayground.product.domain.Product
import org.springframework.stereotype.Repository

/** 코드에 고정된 5개 상품을 가진 상품 저장소(ADR-0005). */
@Repository
class FixedProductRepository : ProductRepository {

	private val products: List<Product> = listOf(
		Product("americano", "아메리카노", 4500),
		Product("latte", "카페라테", 5000),
		Product("cake", "케이크", 6000),
		Product("sandwich", "샌드위치", 7500),
		Product("cookie", "쿠키", 2500),
	)

	private val productsById: Map<String, Product> = products.associateBy { it.id }

	override fun findAll(): List<Product> = products

	override fun findById(productId: String): Product? = productsById[productId]
}
